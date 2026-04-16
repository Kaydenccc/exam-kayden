package id.sekolah.pengunci_ujian

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val channel = "id.sekolah.pengunci_ujian/kiosk"
    private var isLocked = false
    private var isKioskActive = false
    private val handler = Handler(Looper.getMainLooper())

    // Timer yang terus cek apakah app masih di foreground
    private val focusChecker = object : Runnable {
        override fun run() {
            if (isKioskActive) {
                bringToFront()
                handler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channel).setMethodCallHandler { call, result ->
            when (call.method) {
                "startKiosk" -> {
                    try {
                        if (!isLocked) {
                            startLockTask()
                            isLocked = true
                        }
                        isKioskActive = true
                        handler.removeCallbacks(focusChecker)
                        handler.post(focusChecker)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("KIOSK_FAIL", e.message, null)
                    }
                }
                "stopKiosk" -> {
                    try {
                        isKioskActive = false
                        handler.removeCallbacks(focusChecker)
                        if (isLocked) {
                            stopLockTask()
                            isLocked = false
                        }
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("KIOSK_FAIL", e.message, null)
                    }
                }
                "hasOverlayApps" -> {
                    result.success(getOverlayApps())
                }
                "openOverlaySettings" -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            startActivity(intent)
                        }
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("SETTINGS_FAIL", e.message, null)
                    }
                }
                "killBackgroundApps" -> {
                    try {
                        killFloatingApps()
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("KILL_FAIL", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    // Paksa app kembali ke depan — blokir floating overlay
    private fun bringToFront() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
        } catch (_: Exception) {}
    }

    // Dapatkan daftar app yang punya izin overlay (selain app kita)
    private fun getOverlayApps(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()

        val pm = packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val overlayApps = mutableListOf<String>()
        val myPackage = packageName

        for (app in installedApps) {
            if (app.packageName == myPackage) continue
            // Skip system apps
            if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            try {
                // Cek apakah app punya izin SYSTEM_ALERT_WINDOW
                val hasPermission = pm.checkPermission(
                    android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                    app.packageName
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    val label = pm.getApplicationLabel(app).toString()
                    overlayApps.add(label)
                }
            } catch (_: Exception) {}
        }
        return overlayApps
    }

    // Kill background apps yang bukan milik kita
    private fun killFloatingApps() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val myPackage = packageName

        for (app in installedApps) {
            if (app.packageName == myPackage) continue
            if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            try {
                am.killBackgroundProcesses(app.packageName)
            } catch (_: Exception) {}
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        } else if (isKioskActive) {
            // Kehilangan fokus saat kiosk = ada overlay/floating app
            // Paksa kembali ke depan
            handler.postDelayed({ bringToFront() }, 100)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isKioskActive) {
            // App di-pause (ada overlay/floating app muncul), paksa kembali
            handler.postDelayed({
                if (isKioskActive) {
                    bringToFront()
                }
            }, 200)
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isKioskActive) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_BACK -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
