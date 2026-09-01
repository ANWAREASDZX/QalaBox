package com.qalabox.emu.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.qalabox.emu.BuildConfig
import com.qalabox.emu.R
import com.qalabox.emu.core.LogStore
import com.qalabox.emu.core.RuntimeManager
import com.qalabox.emu.core.SettingsStore

/**
 * شاشة الإعدادات — كل مفاتيح الأداء التي كان ExaGear يحجبها عن المستخدم:
 * توجيه الأنوية، معايرة المترجم، تخزين الصوت، الحساسية، وتثبيت وقت التشغيل.
 */
class SettingsFragment : Fragment() {

    private lateinit var settings: SettingsStore
    private lateinit var container: LinearLayout
    private var versionLabel: TextView? = null

    private val runtimePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { installRuntime(it) } }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsStore(requireContext())
        container = view.findViewById(R.id.settings_container)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // إعادة فحص حالة وقت التشغيل عند كل عودة للشاشة — لا تسميات قديمة
        versionLabel?.let { refreshRuntimeStatus(it) }
    }

    /** تحديث سطر حالة وقت التشغيل — مع السبب الدقيق عند عدم التثبيت */
    private fun refreshRuntimeStatus(v: TextView) {
        if (!isAdded) return
        val ctx = requireContext()
        v.text = if (RuntimeManager.isInstalled(ctx)) {
            getString(R.string.runtime_installed, RuntimeManager.version(ctx) ?: "1.0")
        } else {
            val reason = RuntimeManager.diagnose(ctx)
            if (reason.isNullOrEmpty()) getString(R.string.runtime_not_installed)
            else getString(R.string.runtime_not_installed_reason, reason)
        }
    }

    /* ───────── أدوات بناء سريعة ───────── */
    private fun header(title: String): TextView = TextView(requireContext()).apply {
        text = title
        textSize = 16f
        setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
        setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, 8)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun label(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        setPadding(0, 4, 0, 4)
    }

    private fun slider(from: Float, to: Float, step: Float, value: Float, onChange: (Int) -> Unit): Slider =
        Slider(requireContext()).apply {
            valueFrom = from; valueTo = to; stepSize = step; this.value = value
            addOnChangeListener { _, v, fromUser -> if (fromUser) onChange(v.toInt()) }
        }

    private fun switch(text: String, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch =
        MaterialSwitch(requireContext()).apply {
            this.text = text
            this.isChecked = checked
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }

    private fun button(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(requireContext()).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.primary))
            setOnClickListener { onClick() }
        }

    private fun radioGroup(options: List<Pair<String, String>>, selected: String, onChange: (String) -> Unit): RadioGroup {
        val rg = RadioGroup(requireContext())
        rg.orientation = RadioGroup.VERTICAL
        options.forEach { (value, labelText) ->
            val rb = android.widget.RadioButton(requireContext())
            rb.text = labelText
            rb.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            rb.tag = value
            rb.isChecked = value == selected
            rg.addView(rb)
        }
        rg.setOnCheckedChangeListener { _, checkedId ->
            val rb = rg.findViewById<android.widget.RadioButton>(checkedId)
            onChange(rb.tag as String)
        }
        return rg
    }

    /* ───────── بناء الواجهة ───────── */
    private fun buildUi() {
        val ctx = requireContext()

        container.addView(header(getString(R.string.settings_title)))

        container.addView(header(getString(R.string.runtime_section)))
        versionLabel = label("").also { refreshRuntimeStatus(it) }
        container.addView(versionLabel)
        container.addView(button(getString(R.string.runtime_install)) {
            Toast.makeText(ctx, R.string.runtime_pick_hint, Toast.LENGTH_LONG).show()
            runtimePicker.launch(arrayOf("*/*", "application/octet-stream"))
        })
        container.addView(button(getString(R.string.runtime_check)) { runEnvironmentCheck() })
        container.addView(button(getString(R.string.logs_export)) {
            val i = LogStore.exportIntent(ctx)
            if (i != null) startActivity(Intent.createChooser(i, getString(R.string.logs_export)))
            else Toast.makeText(ctx, R.string.error, Toast.LENGTH_SHORT).show()
        })
        container.addView(button(getString(R.string.logs_clear)) {
            LogStore.clear()
            Toast.makeText(ctx, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
        })

        // ── الأداء ──
        container.addView(header(getString(R.string.perf_section)))
        container.addView(label(getString(R.string.cpu_cores) + ": " +
                if (settings.cpuCores < 0) "تلقائي" else "${settings.cpuCores}"))
        container.addView(slider(0f, 8f, 1f, (settings.cpuCores + 1).coerceAtLeast(0).toFloat()) {
            settings.cpuCores = it - 1
        })
        container.addView(switch(getString(R.string.big_cores), settings.bigCores) { settings.bigCores = it })
        container.addView(label(getString(R.string.dynarec_preset)))
        container.addView(radioGroup(
            listOf(
                "fast" to getString(R.string.preset_fast),
                "balanced" to getString(R.string.preset_balanced),
                "compat" to getString(R.string.preset_compat)
            ), settings.dynarecPreset
        ) { settings.dynarecPreset = it })

        // ── الرسوميات ──
        container.addView(header(getString(R.string.graphics_section)))
        container.addView(switch(getString(R.string.fps_overlay), settings.fpsOverlay) { settings.fpsOverlay = it })
        container.addView(label(getString(R.string.container_gpu_driver)))
        container.addView(radioGroup(
            listOf(
                "turnip" to getString(R.string.driver_turnip),
                "virgl" to getString(R.string.driver_virgl),
                "llvm" to getString(R.string.driver_llvm)
            ), settings.defaultDriver
        ) { settings.defaultDriver = it })

        // ── اللمس ──
        container.addView(header(getString(R.string.input_section)))
        container.addView(label(getString(R.string.touch_mode)))
        container.addView(radioGroup(
            listOf(
                "touchpad" to getString(R.string.mode_touchpad),
                "direct" to getString(R.string.mode_direct)
            ), settings.touchMode
        ) { settings.touchMode = it })
        container.addView(label(getString(R.string.sensitivity) + ": ${settings.sensitivity}"))
        container.addView(slider(1f, 10f, 1f, settings.sensitivity.toFloat()) { settings.sensitivity = it })
        container.addView(label(getString(R.string.tap_delay) + ": ${settings.tapDelayMs}"))
        container.addView(slider(100f, 400f, 20f, settings.tapDelayMs.toFloat()) { settings.tapDelayMs = it })
        container.addView(label(getString(R.string.buttons_size) + ": ${settings.buttonSizeDp}dp"))
        container.addView(slider(36f, 84f, 4f, settings.buttonSizeDp.toFloat()) { settings.buttonSizeDp = it })
        container.addView(label(getString(R.string.buttons_opacity) + ": ${settings.buttonOpacity}%"))
        container.addView(slider(30f, 100f, 5f, settings.buttonOpacity.toFloat()) { settings.buttonOpacity = it })

        // ── الصوت ──
        container.addView(header(getString(R.string.audio_section)))
        container.addView(label(getString(R.string.audio_buffer) + ": ${settings.audioBufferBytes} بايت"))
        container.addView(slider(1024f, 16384f, 512f, settings.audioBufferBytes.toFloat()) { settings.audioBufferBytes = it })

        // ── حول ──
        container.addView(header(getString(R.string.about_section)))
        container.addView(label(getString(R.string.about_version, BuildConfig.VERSION_NAME)))
        container.addView(button(getString(R.string.about_licenses)) { showLicenses() })
        container.addView(button(getString(R.string.about_legal)) { showLegal() })
    }

    private fun showLicenses() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.about_licenses)
            .setMessage(
                getString(R.string.license_wine) + "\n" +
                getString(R.string.license_box64) + "\n" +
                getString(R.string.license_cncddraw) + "\n" +
                getString(R.string.license_proot)
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showLegal() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.about_legal)
            .setMessage(R.string.legal_text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** v1.4: فحص ذاتي لمكونات وقت التشغيل داخل الجذر — يكشف بالضبط أي
     *  مكوّن ناقص (Xvfb/qalarender/wine/box86/…) بدل أعطال صامتة عند الإطلاق */
    private fun runEnvironmentCheck() {
        if (!isAdded) return
        val ctx = requireContext()
        val progress = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.runtime_check)
            .setMessage(R.string.runtime_checking)
            .setCancelable(false).create()
        progress.show()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val lines = RuntimeManager.environmentCheck(ctx)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!isAdded) return@withContext
                progress.dismiss()
                val missing = lines.count { it.startsWith("MISSING") }
                val summary = if (missing == 0) getString(R.string.runtime_check_ok)
                              else getString(R.string.runtime_check_missing, missing)
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.runtime_check)
                    .setMessage(summary + "\n\n" + lines.joinToString("\n"))
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    /* ───────── تثبيت وقت التشغيل ───────── */
    private fun installRuntime(uri: Uri) {
        // حماية: رد المُنتقي قد يصل بعد انفصال الشظية (خروج سريع) — v1.3
        if (!isAdded) return
        val ctx = requireContext()
        val progress = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.runtime_pkg_title)
            .setMessage(R.string.runtime_installing)
            .setCancelable(false).create()
        progress.show()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val r = RuntimeManager.installFromPackage(ctx, uri) { msg ->
                // تقدم حي في الحوار — مراحل الفك الطويلة لم تعد صندوقاً أسود
                // (رد الاتصال من خيط عادي — النشر إلى Main عبر Handler)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (progress.isShowing) progress.setMessage(msg)
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!isAdded) return@withContext // الشظية انفصلت أثناء التثبيت — v1.3
                progress.dismiss()
                r.fold(onSuccess = { _ ->
                    versionLabel?.let { refreshRuntimeStatus(it) }
                    Toast.makeText(ctx, R.string.runtime_ready, Toast.LENGTH_SHORT).show()
                }, onFailure = {
                    versionLabel?.let { refreshRuntimeStatus(it) }
                    // التفاصيل الدقيقة (المكوّن الناقص / سبب الفشل) داخل الرسالة نفسها
                    val detail = it.message?.let { m -> "\n" + m } ?: ""
                    Toast.makeText(ctx,
                        getString(R.string.runtime_invalid) + detail,
                        Toast.LENGTH_LONG).show()
                })
            }
        }
    }
}
