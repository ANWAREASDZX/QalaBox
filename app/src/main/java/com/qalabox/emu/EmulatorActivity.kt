package com.qalabox.emu

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qalabox.emu.audio.AudioStreamClient
import com.qalabox.emu.core.ContainerManager
import com.qalabox.emu.core.EmulatorService
import com.qalabox.emu.core.Launcher
import com.qalabox.emu.core.LogStore
import com.qalabox.emu.core.RuntimeManager
import com.qalabox.emu.core.SessionStore
import com.qalabox.emu.core.SettingsStore
import com.qalabox.emu.touch.Keysyms
import com.qalabox.emu.touch.OnScreenControls
import com.qalabox.emu.touch.OverlayView
import com.qalabox.emu.touch.TouchpadView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * شاشة المحاكاة الفعلية — تعرض إطار اللعبة عبر الجسر الأصلي (xbridge)،
 * وتدير اللمس الافتراضي، الصوت، الأزرار على الشاشة، والجلسة.
 */
class EmulatorActivity : AppCompatActivity(), TouchpadView.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var touchpad: TouchpadView
    private lateinit var topBar: LinearLayout
    private lateinit var logPanel: View
    private lateinit var logText: TextView
    private lateinit var fpsLabel: TextView
    private lateinit var bootLabel: TextView
    private lateinit var oskContainer: LinearLayout

    private lateinit var settings: SettingsStore
    private var containerId: String? = null
    private var gameTitle: String = "قلعة بوكس"

    private var xserverProcs: List<java.lang.Process> = emptyList()
    private var audio: AudioStreamClient? = null
    private var sessionStarted = false
    private var surfaceReady = false
    private var xbridgeAttached = false
    private var gotFirstFrame = false
    private val ui = Handler(Looper.getMainLooper())

    private var scrollAccum = 0f

    // ═══════════════════ JNI ═══════════════════
    companion object {
        init { System.loadLibrary("xbridge") }
    }

    private external fun nativeAttach(surface: Surface, socketPath: String, maxFps: Int): Boolean
    private external fun nativeDetach()
    private external fun nativeMoveRelative(dx: Int, dy: Int)
    private external fun nativeMoveAbsolute(x: Int, y: Int)
    private external fun nativeButton(button: Int, down: Boolean)
    private external fun nativeScroll(dx: Int, dy: Int)
    private external fun nativeKey(keysym: Long, down: Boolean)
    private external fun nativeScreenWidth(): Int
    private external fun nativeScreenHeight(): Int

    // استدعاء عكسي من C — لا تغيّر الاسم/التوقيع (يحميه proguard)
    private fun onCursorData(x: Int, y: Int, hotX: Int, hotY: Int, w: Int, h: Int, pixels: IntArray?) {
        // مزامنة مقياس المؤشر مع أبعاد الجلسة الفعلية
        val gw = nativeScreenWidth()
        val gh = nativeScreenHeight()
        if (gw > 0 && gh > 0) overlay.setGuestSize(gw, gh)
        overlay.onCursorData(x, y, w, h, hotX, hotY, pixels)
    }

    // استدعاء عكسي من C — لا تغيّر الاسم/التوقيع (يحميه proguard)
    private fun onFps(fps: Int) {
        ui.post {
            if (settings.fpsOverlay) {
                fpsLabel.visibility = View.VISIBLE
                fpsLabel.text = getString(R.string.emu_fps, fps)
            }
            if (!gotFirstFrame && fps > 0) {
                gotFirstFrame = true
                bootLabel.visibility = View.GONE
            }
        }
    }

    // ═══════════════════ دورة الحياة ═══════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emulator)
        settings = SettingsStore(this)

        // رجوع = تأكيد خروج (نمط OnBackPressedDispatcher الحديث بدل onBackPressed المهمل)
        onBackPressedDispatcher.addCallback(this) { confirmExit() }

        // منع نوم الشاشة أثناء اللعب (اللعبة ستتوقف لو نامت)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNotificationPermission()

        containerId = intent.getStringExtra("container_id")
        gameTitle = intent.getStringExtra("title") ?: "قلعة بوكس"

        surfaceView = findViewById(R.id.game_surface)
        overlay = findViewById(R.id.overlay_view)
        touchpad = findViewById(R.id.touchpad)
        topBar = findViewById(R.id.top_bar)
        logPanel = findViewById(R.id.log_panel)
        logText = findViewById(R.id.log_text)
        fpsLabel = findViewById(R.id.fps_label)
        bootLabel = findViewById(R.id.boot_label)
        oskContainer = findViewById(R.id.osk_container)

        touchpad.callback = this
        touchpad.mode = settings.touchMode
        touchpad.tapDelayMs = settings.tapDelayMs
        OnScreenControls.build(oskContainer, settings) { name, down -> onOskButton(name, down) }
        setupTopBar()

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                attachXbridge()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                if (xbridgeAttached) {
                    nativeDetach()
                    xbridgeAttached = false
                }
            }
        })

        // خدمة أمامية + بدء الجلسة
        startService(Intent(this, EmulatorService::class.java).putExtra("title", gameTitle))
        bootSession()
    }

    @SuppressLint("SetTextI18n")
    private fun setupTopBar() {
        findViewById<TextView>(R.id.btn_exit).setOnClickListener { confirmExit() }
        findViewById<TextView>(R.id.btn_keyboard).setOnClickListener { toggleKeyboard() }
        findViewById<TextView>(R.id.btn_log).setOnClickListener {
            logPanel.visibility = if (logPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            logText.text = LogStore.tail(150)
        }
        findViewById<TextView>(R.id.btn_screenshot).setOnClickListener { takeScreenshot() }

        // إخفاء الشريط بعد 4 ثوانٍ من آخر لمس (يظهر مجدداً بلمسة أعلى الشاشة)
        autoHideTopBar()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7001)
        }
    }

    private fun autoHideTopBar() {
        topBar.animate().alpha(1f).setDuration(150).start()
        ui.removeCallbacks(hideBarRunnable)
        ui.postDelayed(hideBarRunnable, 4000)
    }

    private val hideBarRunnable = Runnable {
        topBar.animate().alpha(0.12f).setDuration(300).start()
    }

    private fun attachXbridge() {
        if (xbridgeAttached || !surfaceReady || !sessionStarted) return
        // مقبس جسر العرض الذي ينشئه qalarender داخل المجلد الملزم
        val sockPath = RuntimeManager.sockDir(this).absolutePath + "/.xbridge.sock"
        val ok = nativeAttach(surfaceView.holder.surface, sockPath, 60)
        xbridgeAttached = ok
        if (!ok) {
            LogStore.append("XBridge", "فشل الربط مع خادم X")
            Toast.makeText(this, R.string.emu_xbridge_error, Toast.LENGTH_LONG).show()
        } else {
            LogStore.append("XBridge", "الربط ناجح — بدأ التقاط الإطارات")
        }
    }

    /** بدء جلسة التشغيل: خادم X → بيئة Wine → اللعبة → الصوت */
    private fun bootSession() {
        if (!RuntimeManager.isInstalled(this)) {
            Toast.makeText(this, R.string.runtime_not_installed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val container = containerId?.let { id -> ContainerManager.list(this).firstOrNull { it.id == id } }
        if (container == null || SessionStore.peek() == null) {
            Toast.makeText(this, R.string.emu_start_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // 1) خادم X + جسر العرض (قد يكونان عمليتين أو واحدة)
            withContext(Dispatchers.Main) { autoHideTopBar() }
            val procs = RuntimeManager.startXServer(this@EmulatorActivity, container.screenWidth, container.screenHeight)
            xserverProcs = procs
            if (procs.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EmulatorActivity, R.string.emu_xbridge_error, Toast.LENGTH_LONG).show()
                    finish()
                }
                return@launch
            }

            // 2) اللعبة (البيئة جُهزت مسبقاً في ProfileEngine)
            val result = Launcher.launchGame(this@EmulatorActivity, container) { }
            result.getOrNull() ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EmulatorActivity, R.string.emu_start_failed, Toast.LENGTH_LONG).show()
                    finish()
                }
                return@launch
            }

            // 3) الصوت
            audio = AudioStreamClient(bufferSizeBytes = settings.audioBufferBytes).also { it.start() }
            sessionStarted = true
            withContext(Dispatchers.Main) { attachXbridge() }
        }
    }

    // ═══════════════════ اللمس → الإدخال ═══════════════════
    override fun onMouseMove(dx: Float, dy: Float) {
        if (!xbridgeAttached) return
        val tw = touchpad.width.coerceAtLeast(1)
        val th = touchpad.height.coerceAtLeast(1)
        val rootW = nativeScreenWidth().coerceAtLeast(1)
        val rootH = nativeScreenHeight().coerceAtLeast(1)
        val sens = settings.sensitivity / 5f
        val sx = (dx * (rootW.toFloat() / tw) * sens).toInt()
        val sy = (dy * (rootH.toFloat() / th) * sens).toInt()
        nativeMoveRelative(sx, sy)
    }

    override fun onMouseMoveAbsolute(x: Float, y: Float) {
        if (!xbridgeAttached) return
        val rootW = nativeScreenWidth().coerceAtLeast(1)
        val rootH = nativeScreenHeight().coerceAtLeast(1)
        val ax = (x / touchpad.width.coerceAtLeast(1)) * rootW
        val ay = (y / touchpad.height.coerceAtLeast(1)) * rootH
        nativeMoveAbsolute(ax.toInt(), ay.toInt())
        // موضع المؤشر العتادي يُحدَّث عبر حزم QBCU القادمة من الجسر الأصلي
    }

    override fun onLeftTap() {
        if (!xbridgeAttached) return
        nativeButton(1, true)
        ui.postDelayed({ nativeButton(1, false) }, 25)
    }

    override fun onRightTap() {
        if (!xbridgeAttached) return
        nativeButton(3, true)
        ui.postDelayed({ nativeButton(3, false) }, 25)
    }

    override fun onScroll(dy: Float) {
        if (!xbridgeAttached) return
        scrollAccum += dy
        while (scrollAccum <= -24f) { nativeScroll(0, -1); scrollAccum += 24f }
        while (scrollAccum >= 24f) { nativeScroll(0, 1); scrollAccum -= 24f }
    }

    override fun onDragStart() {
        if (xbridgeAttached) nativeButton(1, true)
    }

    override fun onDragEnd() {
        if (xbridgeAttached) nativeButton(1, false)
    }

    override fun onTouchPoint(x: Float, y: Float) {
        overlay.onTouchFeedback(x, y)
        autoHideTopBar()
    }

    override fun onTouchEnd() {
        overlay.onTouchFeedbackEnd()
    }

    // ═══════════════════ أزرار الشاشة ═══════════════════
    private fun onOskButton(name: String, down: Boolean) {
        if (!xbridgeAttached) return
        when (name) {
            "rmb" -> nativeButton(3, down)
            "wheelup" -> if (down) nativeScroll(0, 1)
            "wheeldn" -> if (down) nativeScroll(0, -1)
            else -> {
                val ks: Long = name.toLongOrNull()?.let { Keysyms.digit(it.toInt()) }
                    ?: Keysyms.NAMED[name.lowercase()] ?: 0L
                if (ks != 0L) nativeKey(ks, down)
            }
        }
    }

    // ═══════════════════ لوحة المفاتيح الفعلية ═══════════════════
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        event ?: return super.onKeyDown(keyCode, event)
        val ks = Keysyms.fromKeyEvent(event)
        return if (ks != 0L && xbridgeAttached) {
            nativeKey(ks, true); true
        } else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        event ?: return super.onKeyUp(keyCode, event)
        val ks = Keysyms.fromKeyEvent(event)
        return if (ks != 0L && xbridgeAttached) {
            nativeKey(ks, false); true
        } else super.onKeyUp(keyCode, event)
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val view = currentFocus ?: surfaceView
        // نمط Toggle غير المهمل: أخفِ إن كان ظاهراً، وإلا أظهره
        val hidden = imm.hideSoftInputFromWindow(view.windowToken, 0)
        if (!hidden) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        Toast.makeText(this, R.string.emu_kbd_hint, Toast.LENGTH_SHORT).show()
    }

    // ═══════════════════ لقطة شاشة ═══════════════════
    private fun takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val bmp = Bitmap.createBitmap(
            surfaceView.width.coerceAtLeast(1), surfaceView.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        android.view.PixelCopy.request(surfaceView, bmp, { res ->
            if (res == android.view.PixelCopy.SUCCESS) {
                saveBitmap(bmp)
            }
        }, ui)
    }

    private fun saveBitmap(bmp: Bitmap) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    "QalaBox_${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QalaBox")
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Toast.makeText(this, R.string.emu_screenshot_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LogStore.append("Shot", "فشل حفظ اللقطة: ${e.message}")
        }
    }

    // ═══════════════════ الخروج ═══════════════════
    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setMessage(R.string.emu_exit_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> cleanup() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun cleanup() {
        audio?.stop()
        audio = null
        if (xbridgeAttached) {
            nativeDetach()
            xbridgeAttached = false
        }
        Launcher.killSession()
        for (p in xserverProcs) {
            try { p.destroy() } catch (_: Exception) {}
        }
        xserverProcs = emptyList()
        stopService(Intent(this, EmulatorService::class.java))
        finish()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        // حماية من تسريب الجلسة: لو خرج المستخدم بسحب التطبيق من قائمة المهام
        // يجب إنهاء proot + الخدمة + الخادم — غير نُظّفت في confirmExit
        if (sessionStarted || xbridgeAttached || xserverProcs.isNotEmpty()) {
            cleanup()
        }
        super.onDestroy()
    }
}
