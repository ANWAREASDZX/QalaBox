package com.qalabox.emu.touch

import android.annotation.SuppressLint
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.qalabox.emu.core.SettingsStore

/**
 * أزرار على الشاشة قابلة للتخصيص (حجم/شفافية من الإعدادات).
 * الأزرار الافتراضية مصممة لألعاب الاستراتيجية:
 * زر أيمن، Esc للقوائم، Ctrl للتحديد المتعدد، أرقام لمجموعات الوحدات…
 */
object OnScreenControls {

    /** أسماء الأزرار الافتراضية — يمكن توسيعها من الإعدادات لاحقاً */
    val DEFAULT_BUTTONS = listOf(
        "rmb", "esc", "ctrl", "shift", "tab",
        "1", "2", "3", "enter", "space"
    )

    private val LABELS = mapOf(
        "rmb" to "يمين", "esc" to "Esc", "ctrl" to "Ctrl", "shift" to "Shift",
        "tab" to "Tab", "enter" to "Enter", "space" to "مسافة",
        "1" to "1", "2" to "2", "3" to "3", "4" to "4", "5" to "5",
        "f1" to "F1", "f2" to "F2", "wheelup" to "▲", "wheeldn" to "▼"
    )

    @SuppressLint("ClickableViewAccessibility")
    fun build(
        container: LinearLayout,
        settings: SettingsStore,
        onPress: (name: String, down: Boolean) -> Unit
    ) {
        container.removeAllViews()
        val size = settings.buttonSizeDp
        val opacity = settings.buttonOpacity / 100f

        for (name in DEFAULT_BUTTONS) {
            val tv = TextView(container.context).apply {
                text = LABELS[name] ?: name
                textSize = 11f
                setTextColor(0xFFECEFF4.toInt())
                gravity = Gravity.CENTER
                setSingleLine()
                val px = (size * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(px, px).apply {
                    topMargin = (6 * resources.displayMetrics.density).toInt()
                }
                // خلفية دائرية شبه شفافة
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = px / 3f
                    setColor(0xB3202731.toInt())
                    setStroke(1, 0xFFE0A83E.toInt())
                }
                alpha = opacity
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(60).start()
                            onPress(name, true); true
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(60).start()
                            onPress(name, false); true
                        }
                        else -> false
                    }
                }
            }
            container.addView(tv)
        }
    }
}
