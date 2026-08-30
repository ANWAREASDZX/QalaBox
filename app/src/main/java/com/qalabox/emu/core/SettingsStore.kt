package com.qalabox.emu.core

import android.content.Context
import android.content.SharedPreferences

/**
 * مخزن الإعدادات العامة (اللمس، الأداء، الصوت…)
 * قيم افتراضية مصممة خصيصاً لألعاب الاستراتيجية الكلاسيكية.
 */
class SettingsStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("qalabox_settings", Context.MODE_PRIVATE)

    // ═══════════ الإدخال ═══════════
    var touchMode: String          // touchpad | direct
        get() = sp.getString("touch_mode", "touchpad") ?: "touchpad"
        set(v) = sp.edit().putString("touch_mode", v).apply()

    var sensitivity: Int           // 1..10
        get() = sp.getInt("sensitivity", 5)
        set(v) = sp.edit().putInt("sensitivity", v).apply()

    var tapDelayMs: Int            // زمن التمييز بين النقر والسحب
        get() = sp.getInt("tap_delay", 180)
        set(v) = sp.edit().putInt("tap_delay", v).apply()

    var buttonSizeDp: Int
        get() = sp.getInt("button_size", 52)
        set(v) = sp.edit().putInt("button_size", v).apply()

    var buttonOpacity: Int         // 30..100 %
        get() = sp.getInt("button_opacity", 65)
        set(v) = sp.edit().putInt("button_opacity", v).apply()

    // ═══════════ الأداء ═══════════
    var cpuCores: Int              // -1 = تلقائي
        get() = sp.getInt("cpu_cores", -1)
        set(v) = sp.edit().putInt("cpu_cores", v).apply()

    var bigCores: Boolean          // تفضيل الأنوية الكبيرة — يقتل بطء الماوس
        get() = sp.getBoolean("big_cores", true)
        set(v) = sp.edit().putBoolean("big_cores", v).apply()

    var dynarecPreset: String      // fast | balanced | compat
        get() = sp.getString("dynarec_preset", "balanced") ?: "balanced"
        set(v) = sp.edit().putString("dynarec_preset", v).apply()

    var fpsOverlay: Boolean
        get() = sp.getBoolean("fps_overlay", true)
        set(v) = sp.edit().putBoolean("fps_overlay", v).apply()

    // ═══════════ الصوت ═══════════
    var audioBufferBytes: Int      // 1024..16384 — الأكبر = ثبات أكثر تأخيراً
        get() = sp.getInt("audio_buffer", 4096)
        set(v) = sp.edit().putInt("audio_buffer", v).apply()

    // ═══════════ الرسوميات ═══════════
    var defaultDriver: String
        get() = sp.getString("default_driver", "turnip") ?: "turnip"
        set(v) = sp.edit().putString("default_driver", v).apply()

    var selectedContainerId: String?
        get() = sp.getString("selected_container", null)
        set(v) = sp.edit().putString("selected_container", v).apply()
}
