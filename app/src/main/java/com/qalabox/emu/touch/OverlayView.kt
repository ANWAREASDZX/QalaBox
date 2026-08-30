package com.qalabox.emu.touch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * طبقة الرسم فوق إطار اللعبة:
 * - مؤشر الماوس الافتراضي (مُستقبل عتادي عبر XFixes — علاج تأخر المؤشر في ExaGear)
 * - مؤشر ضغط بصري عند اللمس
 *
 * v1.1: التعيين الآن يطابق letterbox الخاص بالجسر الأصلي (xbridge.c):
 * مستطيل الملاءمة محسوب بنفس معادلة aspect-fit — فينطبق المؤشر على
 * موضع اللعبة الحقيقي بدقة على أي شاشة.
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var cursor: Bitmap? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var hotspotX = 0
    private var hotspotY = 0
    private var touchX = -1f
    private var touchY = -1f

    private var guestW = 0
    private var guestH = 0

    /* مستطيل إطار اللعبة على الشاشة (يُحسب مثل blitFrame الأصلي) */
    private var rectLeft = 0f
    private var rectTop = 0f
    private var rectW = 1f
    private var rectH = 1f

    private val cursorPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // مستطيل مُعاد استخدامه في كل إطار (منع التخصيص داخل onDraw)
    private val cursorRect = Rect()
    private val touchPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(160, 224, 168, 62)
        isAntiAlias = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeRect()
    }

    /** أبعاد جلسة الضيف لحساب مستطيل العرض على الشاشة */
    fun setGuestSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == guestW && h == guestH) return
        guestW = w
        guestH = h
        recomputeRect()
        postInvalidateOnAnimation()
    }

    /** نفس معادلة letterbox في xbridge.c */
    private fun recomputeRect() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || guestW <= 0 || guestH <= 0) {
            rectLeft = 0f; rectTop = 0f; rectW = vw.coerceAtLeast(1f); rectH = vh.coerceAtLeast(1f)
            return
        }
        val lhs = vw * guestH
        val rhs = vh * guestW
        if (lhs > rhs) {
            rectH = vh
            rectW = vh * guestW / guestH
            rectLeft = (vw - rectW) / 2f
            rectTop = 0f
        } else {
            rectW = vw
            rectH = vw * guestH / guestW
            rectLeft = 0f
            rectTop = (vh - rectH) / 2f
        }
    }

    /** استلام صورة المؤشر من الجسر الأصلي (ARGB) — الإحداثيات بإحداثيات الضيف */
    fun onCursorData(x: Int, y: Int, w: Int, h: Int, hotX: Int, hotY: Int, pixels: IntArray?) {
        if (w <= 0 || h <= 0 || pixels == null || pixels.size < w * h) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        cursor = bmp
        hotspotX = hotX
        hotspotY = hotY
        /* تحويل موضع الجذر X إلى الشاشة عبر مستطيل العرض */
        cursorX = rectLeft + (x.coerceIn(0, guestW.coerceAtLeast(1))) * rectW / guestW.coerceAtLeast(1)
        cursorY = rectTop + (y.coerceIn(0, guestH.coerceAtLeast(1))) * rectH / guestH.coerceAtLeast(1)
        postInvalidateOnAnimation()
    }

    fun onTouchFeedback(x: Float, y: Float) {
        touchX = x; touchY = y
        postInvalidateOnAnimation()
    }

    fun onTouchFeedbackEnd() {
        touchX = -1f; touchY = -1f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // حلقة اللمس
        if (touchX >= 0) {
            canvas.drawCircle(touchX, touchY, 22f, touchPaint)
        }
        // المؤشر — يُحجَّم ليطابق مقياس إطار اللعبة نفسه (لا تكبير مضاعف ثابت)
        val c = cursor
        if (c != null && rectW > 0f && guestW > 0) {
            val s = rectW / guestW
            val drawX = cursorX - hotspotX * s
            val drawY = cursorY - hotspotY * s
            canvas.drawBitmap(
                c, null,
                cursorRect.apply {
                    set(
                        drawX.toInt(), drawY.toInt(),
                        drawX.toInt() + (c.width * s).toInt().coerceAtLeast(1),
                        drawY.toInt() + (c.height * s).toInt().coerceAtLeast(1)
                    )
                },
                cursorPaint
            )
        }
    }
}
