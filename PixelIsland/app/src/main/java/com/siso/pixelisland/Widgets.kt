package com.siso.pixelisland

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** Four little bars that dance while music is playing. */
class BarsView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val phase = floatArrayOf(0f, 1.3f, 2.4f, 0.7f)
    private val rect = RectF()

    var playing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var tint: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val n = 4
        val gap = width * 0.14f
        val barW = (width - gap * (n - 1)) / n
        val t = System.nanoTime() / 1_000_000_000f
        for (i in 0 until n) {
            val amp = if (playing) {
                0.28f + 0.72f * abs(sin(t * (3.2f + i * 0.9f) + phase[i]))
            } else {
                0.2f
            }
            val h = height * amp
            val x = i * (barW + gap)
            rect.set(x, (height - h) / 2f, x + barW, (height + h) / 2f)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
        if (playing && isShown) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }
}

/** Thin progress bar that can be dragged to seek. */
class SeekView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private var dragging = false
    private var dragFraction = 0f

    var onSeek: ((Float) -> Unit)? = null

    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (!dragging) invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val h = 4f * density
        val top = (height - h) / 2f
        rect.set(0f, top, width.toFloat(), top + h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, track)
        val f = if (dragging) dragFraction else fraction
        rect.set(0f, top, width * f, top + h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0) return false
        val f = (event.x / width).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragFraction = f
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                dragFraction = f
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                fraction = f
                onSeek?.invoke(f)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }
}
