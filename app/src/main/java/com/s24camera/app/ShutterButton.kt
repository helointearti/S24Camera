package com.s24camera.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Déclencheur dessiné à la main, façon appareil photo Samsung. */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { PHOTO, VIDEO, RECORDING }

    var state: State = State.PHOTO
        set(value) {
            field = value
            invalidate()
        }

    private val red = Color.parseColor("#E53935")
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val stroke = r * 0.08f
        ringPaint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, r - stroke / 2f, ringPaint)

        val pressScale = if (isPressed) 0.88f else 1f
        when (state) {
            State.PHOTO -> {
                fillPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, r * 0.78f * pressScale, fillPaint)
            }
            State.VIDEO -> {
                fillPaint.color = red
                canvas.drawCircle(cx, cy, r * 0.78f * pressScale, fillPaint)
            }
            State.RECORDING -> {
                fillPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, r * 0.78f, fillPaint)
                fillPaint.color = red
                val half = r * 0.30f * pressScale
                rect.set(cx - half, cy - half, cx + half, cy + half)
                canvas.drawRoundRect(rect, r * 0.08f, r * 0.08f, fillPaint)
            }
        }
    }
}
