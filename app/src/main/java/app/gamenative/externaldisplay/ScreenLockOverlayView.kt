package app.gamenative.externaldisplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Full-screen scrim shown on the control-editing screen that is currently LOCKED (input goes to
 * the other display). Displays a lock glyph and message, and a swipe-to-unlock track: dragging
 * the knob across the track fires [onUnlock], which switches active editing to this screen.
 *
 * Consumes all touches so the InputControlsView beneath it can't be edited while locked.
 */
@SuppressLint("ClickableViewAccessibility")
class ScreenLockOverlayView(
    context: Context,
    private val message: String,
    private val hint: String,
    private val onUnlock: () -> Unit,
) : View(context) {

    private val scrimPaint = Paint().apply { color = 0xB0000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x40FFFFFF
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private val track = RectF()
    private var knobX = 0f
    private var knobRadius = 0f
    private var dragging = false
    private var trackFilled = false

    init {
        isClickable = true
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val trackWidth = w * 0.6f
        val trackHeight = minOf(h, w) * 0.09f
        knobRadius = trackHeight * 0.45f
        val left = (w - trackWidth) / 2f
        val top = h * 0.62f
        track.set(left, top, left + trackWidth, top + trackHeight)
        knobX = track.left + knobRadius + track.height() * 0.1f
        textPaint.textSize = trackHeight * 0.8f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // Lock glyph
        val glyphPaint = Paint(textPaint).apply { textSize = height * 0.12f }
        canvas.drawText("🔒", width / 2f, height * 0.4f, glyphPaint)
        // Message
        val msgPaint = Paint(textPaint).apply { textSize = height * 0.05f }
        canvas.drawText(message, width / 2f, height * 0.52f, msgPaint)

        // Swipe track + knob
        val radius = track.height() / 2f
        canvas.drawRoundRect(track, radius, radius, trackPaint)
        val hintPaint = Paint(textPaint).apply { textSize = track.height() * 0.42f; color = 0x99FFFFFF.toInt() }
        canvas.drawText(hint, track.centerX(), track.centerY() + hintPaint.textSize * 0.35f, hintPaint)
        canvas.drawCircle(knobX, track.centerY(), knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = abs(event.x - knobX) < knobRadius * 2.5f && abs(event.y - track.centerY()) < track.height()
                trackFilled = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    knobX = event.x.coerceIn(track.left + knobRadius, track.right - knobRadius)
                    if (knobX >= track.right - knobRadius * 1.2f && !trackFilled) {
                        trackFilled = true
                        onUnlock()
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (!trackFilled) {
                    // snap back
                    knobX = track.left + knobRadius + track.height() * 0.1f
                    invalidate()
                }
                return true
            }
        }
        return true
    }
}
