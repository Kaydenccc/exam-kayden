package id.sekolah.pengunci_ujian

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
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

    private companion object {
        // Berapa lama status "terhalangi" bertahan tanpa sentuhan obscured baru.
        const val OBSCURED_LINGER_MS = 1200L
    }

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
                handler.postDelayed(this, 500)
            }
        }
    }

    // Clipboard dibersihkan secara event-driven, BUKAN polling.
    // Menulis ke clipboard berulang kali memunculkan overlay sistem
    // "Disalin / Kirim ke perangkat" (Android 13+ & OEM skin) yang
    // menutupi keyboard. Kita hanya bereaksi saat isinya benar-benar berubah.
    private val clipboardManager: ClipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private var isSelfClearing = false

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (isKioskActive && !isSelfClearing) clearClipboard()
    }

    private fun clearClipboard() {
        try {
            val cm = clipboardManager
            // Tidak ada isi = tidak perlu menyentuh clipboard sama sekali.
            if (!cm.hasPrimaryClip()) return
            isSelfClearing = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // clearPrimaryClip() menghapus tanpa memicu overlay "Disalin"
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {
        } finally {
            handler.post { isSelfClearing = false }
        }
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
                    // Proteksi didaftarkan LEBIH DULU. startLockTask() bisa
                    // gagal/dilarang di sebagian OEM (screen pinning dimatikan
                    // di Setelan, ROM China, dsb) — kalau gagal, sisa proteksi
                    // tetap harus jalan, bukan ikut batal.
                    isKioskActive = true
                    handler.removeCallbacks(focusChecker)
                    handler.post(focusChecker)
                    registerClipListener()
                    clearClipboard()
                    // Android 12+: sembunyikan SEMUA overlay dari app lain
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try { window.setHideOverlayWindows(true) } catch (_: Exception) {}
                    }

                    var lockTaskOk = false
                    try {
                        if (!isLocked) {
                            allowLockTaskIfDeviceOwner()
                            startLockTask()
                            isLocked = true
                        }
                        lockTaskOk = true
                    } catch (e: Exception) {
                        // Biarkan ujian tetap berjalan tanpa pinning.
                        android.util.Log.w("Kiosk", "startLockTask gagal: ${e.message}")
                    }
                    result.success(lockTaskOk)
                }
                "stopKiosk" -> {
                    try {
                        isKioskActive = false
                        handler.removeCallbacks(focusChecker)
                        handler.removeCallbacks(clearObscured)
                        setObscured(false)
                        unregisterClipListener()
                        releaseDeviceOwnerPolicy()
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
                "clearClipboard" -> {
                    clearClipboard()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    // ---------------------------------------------------------------
    // Device Owner (opsional). Kalau sekolah mem-provision perangkat via
    //   adb shell dpm set-device-owner id.sekolah.pengunci_ujian/.AdminReceiver
    // maka kunci ujian naik ke level OS: lock task tanpa dialog pinning,
    // dan aplikasi lain DILARANG menggambar overlay/floating sama sekali.
    // Tanpa device owner semuanya di-skip diam-diam — app tetap jalan.
    // ---------------------------------------------------------------
    private val dpm: DevicePolicyManager by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val adminComponent by lazy { ComponentName(this, AdminReceiver::class.java) }

    private fun isDeviceOwner(): Boolean = try {
        dpm.isDeviceOwnerApp(packageName)
    } catch (_: Exception) { false }

    private fun allowLockTaskIfDeviceOwner() {
        if (!isDeviceOwner()) return
        try {
            dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_NONE
                )
            }
            // Larang aplikasi lain menggambar overlay/floating window.
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CREATE_WINDOWS)
        } catch (e: Exception) {
            android.util.Log.w("Kiosk", "Device owner policy gagal: ${e.message}")
        }
    }

    private fun releaseDeviceOwnerPolicy() {
        if (!isDeviceOwner()) return
        try {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CREATE_WINDOWS)
        } catch (_: Exception) {}
    }

    private var clipListenerRegistered = false

    private fun registerClipListener() {
        if (clipListenerRegistered) return
        try {
            clipboardManager.addPrimaryClipChangedListener(clipListener)
            clipListenerRegistered = true
        } catch (_: Exception) {}
    }

    private fun unregisterClipListener() {
        if (!clipListenerRegistered) return
        try {
            clipboardManager.removePrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {}
        clipListenerRegistered = false
    }

    override fun onDestroy() {
        unregisterClipListener()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
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

    // FLAG_WINDOW_IS_PARTIALLY_OBSCURED menyala untuk window sistem yang sah —
    // keyboard/IME, toast, dialog sistem, bilah status — sehingga memicu
    // alarm palsu "ada aplikasi floating" padahal siswa tidak membuka apa pun.
    // Hanya FLAG_WINDOW_IS_OBSCURED (overlay tepat di titik sentuh) yang
    // benar-benar menandakan tapjacking.
    private val clearObscured = Runnable { setObscured(false) }

    private fun setObscured(value: Boolean) {
        if (value == lastObscuredState) return
        lastObscuredState = value
        try {
            handler.post { overlaySink?.success(value) }
        } catch (_: Exception) {}
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            val isObscured = (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0
            handler.removeCallbacks(clearObscured)
            if (isObscured) {
                setObscured(true)
                // Pulih otomatis: status merah tidak menggantung menunggu
                // siswa mengetuk layar lagi.
                handler.postDelayed(clearObscured, OBSCURED_LINGER_MS)
                if (isKioskActive) return true
            } else if (lastObscuredState) {
                setObscured(false)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // Matikan menu konteks seleksi teks (Salin / Tempel / Bagikan) yang
    // muncul saat teks ditekan lama di dalam WebView.
    override fun onWindowStartingActionMode(
        callback: android.view.ActionMode.Callback?
    ): android.view.ActionMode? = if (isKioskActive) null
        else super.onWindowStartingActionMode(callback)

    override fun onWindowStartingActionMode(
        callback: android.view.ActionMode.Callback?,
        type: Int
    ): android.view.ActionMode? = if (isKioskActive) null
        else super.onWindowStartingActionMode(callback, type)

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
