package com.qalabox.emu.touch

import android.view.KeyEvent

/**
 * جدول مفاتيح X11 (Keysyms) — يترجم أحداث لوحة مفاتيح أندرويد
 * إلى مفاتيح X ليُرسلها الجسر الأصلي عبر XTest.
 * يشمل دعم الحروف اللاتينية والحساسة للحالة التي تعتمدها معظم الألعاب.
 */
object Keysyms {

    // اختصارات الأزرار على الشاشة (تُستخدم بالاسم في ملفات التهيئة)
    val NAMED: Map<String, Long> = mapOf(
        "esc" to 0xFF1BL, "tab" to 0xFF09L, "enter" to 0xFF0DL,
        "space" to 0x0020L, "ctrl" to 0xFFE3L, "shift" to 0xFFE1L,
        "alt" to 0xFFE9L, "backspace" to 0xFF08L,
        "up" to 0xFF52L, "down" to 0xFF53L, "left" to 0xFF51L, "right" to 0xFF54L,
        "home" to 0xFF50L, "end" to 0xFF57L, "pgup" to 0xFF55L, "pgdn" to 0xFF56L,
        "del" to 0xFFFFL, "f1" to 0xFFBEL, "f2" to 0xFFBFL, "f3" to 0xFFC0L,
        "f4" to 0xFFC1L, "f5" to 0xFFC2L, "f6" to 0xFFC3L,
        "plus" to 0x002BL, "minus" to 0x002DL, "pause" to 0xFF13L
    )

    /** ترجمة حدث لوحة المفاتيح الفعلي إلى Keysym (0 = غير مدعوم) */
    fun fromKeyEvent(e: KeyEvent): Long {
        val uc = e.unicodeChar
        if (uc in 0x20..0x7E) return uc.toLong()          // ASCII مباشرة (يدعم حالة الأحرف)
        return when (e.keyCode) {
            KeyEvent.KEYCODE_ENTER -> 0xFF0DL
            KeyEvent.KEYCODE_ESCAPE -> 0xFF1BL
            KeyEvent.KEYCODE_TAB -> 0xFF09L
            KeyEvent.KEYCODE_SPACE -> 0x0020L
            KeyEvent.KEYCODE_DEL -> 0xFF08L   // KEYCODE_DEL هو زر Backspace في أندرويد
            KeyEvent.KEYCODE_DPAD_UP -> 0xFF52L
            KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF53L
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51L
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF54L
            KeyEvent.KEYCODE_MOVE_HOME -> 0xFF50L
            KeyEvent.KEYCODE_MOVE_END -> 0xFF57L
            KeyEvent.KEYCODE_PAGE_UP -> 0xFF55L
            KeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56L
            KeyEvent.KEYCODE_F1 -> 0xFFBEL
            KeyEvent.KEYCODE_F2 -> 0xFFBFL
            KeyEvent.KEYCODE_F3 -> 0xFFC0L
            KeyEvent.KEYCODE_F4 -> 0xFFC1L
            KeyEvent.KEYCODE_F5 -> 0xFFC2L
            KeyEvent.KEYCODE_F6 -> 0xFFC3L
            KeyEvent.KEYCODE_F7 -> 0xFFC4L
            KeyEvent.KEYCODE_F8 -> 0xFFC5L
            KeyEvent.KEYCODE_F9 -> 0xFFC6L
            KeyEvent.KEYCODE_F10 -> 0xFFC7L
            KeyEvent.KEYCODE_F11 -> 0xFFC8L
            KeyEvent.KEYCODE_F12 -> 0xFFC9L
            else -> 0L
        }
    }

    /** زر لوحة أرقام افتراضي للألعاب الاستراتيجية (اختصارات مجموعات الجنود مثلاً) */
    fun digit(n: Int): Long = if (n in 0..9) (0x30 + n).toLong() else 0L
}
