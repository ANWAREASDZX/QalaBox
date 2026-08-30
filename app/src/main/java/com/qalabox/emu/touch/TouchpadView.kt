package com.qalabox.emu.touch

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * لوحة اللمس الافتراضية — بأسلوب ExaGear الأصلي لكن أذكى:
 * - وضع لوحة اللمس: اسحب في أي مكان لتحريك المؤشر، نقرة قصيرة = نقر
 * - وضع مباشر: اضغط حيث تريد أن يكون النقر
 * - ضغط مطوّل + سحب = سحب/إفلات Drag
 * - إصبعان: نقرة = زر أيمن، سحب عمودي = عجلة تمرير
 */
class TouchpadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callback {
        fun onMouseMove(dx: Float, dy: Float)
        fun onMouseMoveAbsolute(x: Float, y: Float)
        fun onLeftTap()
        fun onRightTap()
        fun onScroll(dy: Float)
        fun onDragStart()      // زر أيسر مضغوط (سحب)
        fun onDragEnd()
        fun onTouchPoint(x: Float, y: Float)
        fun onTouchEnd()
    }

    var callback: Callback? = null
    var mode = "touchpad"          // touchpad | direct
    var tapDelayMs = 180
    var longPressMs = 400

    private var startX = 0f; private var startY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var downTime = 0L
    private var totalDist = 0f
    private var dragging = false
    private var longPressFired = false
    private var secondFingerDownTime = 0L
    private var secondFingerLastY = 0f
    private var twoFingerUsed = false
    private var longPressRunnable: Runnable? = null

    override fun onDetachedFromWindow() {
        // إلغاء أي مؤقّت معلّق حتى لا يطلق بعد انتهاء العرض (زر مثبت!)
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
        super.onDetachedFromWindow()
    }

    private fun cancelLongPressTimer() {
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                lastX = startX; lastY = startY
                downTime = System.currentTimeMillis()
                totalDist = 0f
                dragging = false
                longPressFired = false
                twoFingerUsed = false
                callback?.onTouchPoint(event.x, event.y)

                if (mode == "direct") {
                    callback?.onMouseMoveAbsolute(event.x, event.y)
                }
                // مؤقّت الضغط المطوّل — مع مرجع يُلغى عند رفع الإصبع
                cancelLongPressTimer()
                val r = Runnable {
                    longPressRunnable = null
                    if (!twoFingerUsed && System.currentTimeMillis() - downTime >= longPressMs &&
                        totalDist < 24f && !longPressFired
                    ) {
                        longPressFired = true
                        dragging = true
                        callback?.onDragStart()
                    }
                }
                longPressRunnable = r
                postDelayed(r, longPressMs.toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    if (!twoFingerUsed) cancelLongPressTimer()
                    twoFingerUsed = true
                    val y = event.getY(1)
                    if (secondFingerDownTime == 0L) {
                        secondFingerDownTime = System.currentTimeMillis()
                        secondFingerLastY = y
                    } else {
                        val dy = y - secondFingerLastY
                        if (Math.abs(dy) > 2f) {
                            callback?.onScroll(dy)
                            secondFingerLastY = y
                        }
                    }
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    totalDist += Math.abs(dx) + Math.abs(dy)
                    lastX = event.x; lastY = event.y
                    if (!twoFingerUsed) {
                        if (mode == "direct") {
                            callback?.onMouseMoveAbsolute(event.x, event.y)
                        } else {
                            callback?.onMouseMove(dx, dy)
                        }
                        callback?.onTouchPoint(event.x, event.y)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    twoFingerUsed = true
                    cancelLongPressTimer()
                    secondFingerDownTime = System.currentTimeMillis()
                    secondFingerLastY = event.getY(1)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2 &&
                    System.currentTimeMillis() - secondFingerDownTime < tapDelayMs
                ) {
                    callback?.onRightTap()
                }
                if (event.pointerCount == 2) {
                    secondFingerDownTime = 0L
                }
            }

            MotionEvent.ACTION_UP -> {
                cancelLongPressTimer()
                val elapsed = System.currentTimeMillis() - downTime
                if (!twoFingerUsed) {
                    if (dragging) {
                        callback?.onDragEnd()
                    } else if (elapsed < tapDelayMs && totalDist < 24f) {
                        callback?.onLeftTap()
                    }
                }
                callback?.onTouchEnd()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                if (dragging) callback?.onDragEnd()
                callback?.onTouchEnd()
            }
        }
        return true
    }
}
