package app.gamenative.externaldisplay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.winlator.inputcontrols.Binding

/**
 * Grid of shoulder/stick buttons rendered on the cover screen of a foldable while a game
 * runs on the inner display. The cover screen faces away from the player, so left/right are
 * mirrored: the player's left index finger lands on the right side of this layout.
 */
class CoverScreenShoulderView(
    context: Context,
    private val onButton: (binding: Binding, pressed: Boolean) -> Unit,
) : View(context) {

    private class ButtonCell(val label: String, val binding: Binding) {
        val rect = RectF()
        var pressCount = 0
    }

    // Row-major, 2 columns x 3 rows. R column on the (rendered) left, L column on the
    // right, so buttons sit under the matching hand when holding the unfolded device.
    private val cells = arrayOf(
        ButtonCell("R1", Binding.GAMEPAD_BUTTON_R1), ButtonCell("L1", Binding.GAMEPAD_BUTTON_L1),
        ButtonCell("R2", Binding.GAMEPAD_BUTTON_R2), ButtonCell("L2", Binding.GAMEPAD_BUTTON_L2),
        ButtonCell("R3", Binding.GAMEPAD_BUTTON_R3), ButtonCell("L3", Binding.GAMEPAD_BUTTON_L3),
    )

    // pointerId -> cell index, -1 when the pointer is not over any cell
    private val pointerCells = HashMap<Int, Int>()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x40FFFFFF
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val columns = 2
        val rows = 3
        val margin = minOf(w, h) * 0.02f
        val cellWidth = (w - margin * (columns + 1)) / columns
        val cellHeight = (h - margin * (rows + 1)) / rows
        for ((index, cell) in cells.withIndex()) {
            val col = index % columns
            val row = index / columns
            val left = margin + col * (cellWidth + margin)
            val top = margin + row * (cellHeight + margin)
            cell.rect.set(left, top, left + cellWidth, top + cellHeight)
        }
        textPaint.textSize = cellHeight * 0.4f
        strokePaint.strokeWidth = margin * 0.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val corner = minOf(width, height) * 0.05f
        for (cell in cells) {
            fillPaint.color = if (cell.pressCount > 0) 0x60FFFFFF else 0x20FFFFFF
            canvas.drawRoundRect(cell.rect, corner, corner, fillPaint)
            canvas.drawRoundRect(cell.rect, corner, corner, strokePaint)
            val textY = cell.rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(cell.label, cell.rect.centerX(), textY, textPaint)
        }
    }

    private fun cellIndexAt(x: Float, y: Float): Int =
        cells.indexOfFirst { it.rect.contains(x, y) }

    private fun setPointerCell(pointerId: Int, cellIndex: Int) {
        val previous = pointerCells[pointerId] ?: -1
        if (previous == cellIndex) return
        if (previous >= 0) releaseCell(previous)
        if (cellIndex >= 0) pressCell(cellIndex)
        pointerCells[pointerId] = cellIndex
    }

    private fun pressCell(index: Int) {
        val cell = cells[index]
        if (++cell.pressCount == 1) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onButton(cell.binding, true)
            invalidate()
        }
    }

    private fun releaseCell(index: Int) {
        val cell = cells[index]
        if (cell.pressCount > 0 && --cell.pressCount == 0) {
            onButton(cell.binding, false)
            invalidate()
        }
    }

    private fun releasePointer(pointerId: Int) {
        val cellIndex = pointerCells.remove(pointerId) ?: return
        if (cellIndex >= 0) releaseCell(cellIndex)
    }

    private fun releaseAll() {
        for (pointerId in pointerCells.keys.toList()) releasePointer(pointerId)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                setPointerCell(event.getPointerId(index), cellIndexAt(event.getX(index), event.getY(index)))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    setPointerCell(event.getPointerId(index), cellIndexAt(event.getX(index), event.getY(index)))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                releasePointer(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }
}
