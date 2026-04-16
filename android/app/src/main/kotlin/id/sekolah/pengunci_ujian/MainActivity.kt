package id.sekolah.pengunci_ujian

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val channel = "id.sekolah.pengunci_ujian/kiosk"
    private val overlayEventChannel = "id.sekolah.pengunci_ujian/overlay"
    private var isLocked = false
    private var isKioskActive = false
    private val handler = Handler(Looper.getMainLooper())
    private var overlaySink: EventChannel.EventSink? = null
    private var lastObscuredState = false

    private val focusChecker = object : Runnable {
        override fun run() {
            if (isKioskActive) {
                bringToFront()
                clearClipboard()
                handler.postDelayed(this, 500)
            }
        }
    }

    private fun clearClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        // JANGAN pakai filterTouchesWhenObscured — konflik dengan dispatchTouchEvent
        // Kita handle sendiri di dispatchTouchEvent agar bisa kirim event ke Flutter
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // EventChannel: stream overlay status ke Flutter
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, overlayEventChannel)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    overlaySink = events
                }
                override fun onCancel(arguments: Any?) {
                    overlaySink = null
                }
            })
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
                        // Android 12+: sembunyikan SEMUA overlay dari app lain
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            window.setHideOverlayWindows(true)
                        }
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("KIOSK_FAIL", e.message, null)
                    }
                }
                "stopKiosk" -> {
                    try {
                        isKioskActive = false
                        handler.removeCallbacks(focusChecker)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            window.setHideOverlayWindows(false)
                        }
                        if (isLocked) {
                            stopLockTask()
                            isLocked = false
                        }
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("KIOSK_FAIL", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private var bringToFrontThrottled = false

    private fun bringToFront() {
        if (bringToFrontThrottled) return
        bringToFrontThrottled = true
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
        } catch (_: Exception) {}
        handler.postDelayed({ bringToFrontThrottled = false }, 500)
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

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            val obscured = (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0
            val partiallyObscured = if (Build.VERSION.SDK_INT >= 29) {
                (event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
            } else false
            val isObscured = obscured || partiallyObscured

            if (isObscured != lastObscuredState) {
                lastObscuredState = isObscured
                try {
                    handler.post { overlaySink?.success(isObscured) }
                } catch (_: Exception) {}
            }
            if (isObscured && isKioskActive) {
                return true
            }
        }
        return super.dispatchTouchEvent(event)
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
