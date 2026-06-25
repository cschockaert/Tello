package com.tellopilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * A reusable, self-centering touch joystick drawn entirely with Canvas (no image
 * assets). Reports its position as normalized axes in [-1, 1]:
 *  - x: -1 full left .. +1 full right
 *  - y: -1 full down .. +1 full up   (screen-up is positive, intuitive for sticks)
 *
 * Both sticks spring back to center on release, matching how the official Tello
 * apps behave on a touchscreen.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface Listener {
        /** @param x normalized horizontal [-1,1], @param y normalized vertical [-1,1] */
        fun onMove(x: Float, y: Float)
    }

    var listener: Listener? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0x29, 0x79, 0xFF)
        style = Paint.Style.FILL
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var thumbRadius = 0f

    private var thumbX = 0f
    private var thumbY = 0f

    private var activePointerId = -1

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = min(w, h) / 2f * 0.82f
        thumbRadius = baseRadius * 0.38f
        thumbX = centerX
        thumbY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius, ringPaint)
        // crosshair
        canvas.drawLine(centerX - baseRadius, centerY, centerX + baseRadius, centerY, crossPaint)
        canvas.drawLine(centerX, centerY - baseRadius, centerX, centerY + baseRadius, crossPaint)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                if (activePointerId == -1) {
                    activePointerId = event.getPointerId(idx)
                    updateThumb(event.getX(idx), event.getY(idx))
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    updateThumb(event.getX(pointerIndex), event.getY(pointerIndex))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == activePointerId || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    activePointerId = -1
                    recenter()
                }
            }
        }
        return true
    }

    private fun updateThumb(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val dist = hypot(dx, dy)
        if (dist > baseRadius) {
            val scale = baseRadius / dist
            thumbX = centerX + dx * scale
            thumbY = centerY + dy * scale
        } else {
            thumbX = x
            thumbY = y
        }
        emit()
        invalidate()
    }

    private fun recenter() {
        thumbX = centerX
        thumbY = centerY
        emit()
        invalidate()
    }

    private fun emit() {
        val nx = if (baseRadius == 0f) 0f else ((thumbX - centerX) / baseRadius).coerceIn(-1f, 1f)
        // Invert Y so that pushing the stick up yields a positive value.
        val ny = if (baseRadius == 0f) 0f else (-(thumbY - centerY) / baseRadius).coerceIn(-1f, 1f)
        listener?.onMove(nx, ny)
    }
}
