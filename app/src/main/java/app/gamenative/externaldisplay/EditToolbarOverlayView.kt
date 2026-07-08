package app.gamenative.externaldisplay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lightweight Android-view editing toolbar for control-layout editing, usable on both the inner
 * screen and the foldable cover presentation (which can't host Compose). Buttons act on whichever
 * screen currently holds edit focus; the manager toggles which toolbar is visible.
 */
class EditToolbarOverlayView(
    context: Context,
    onAdd: () -> Unit,
    onEditBinding: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) : LinearLayout(context) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val pad = dp(8)
        setPadding(pad, dp(4), pad, dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(0xCC1E1E1E.toInt())
        }
        addButton("＋ Add", onAdd)
        addButton("✎ Edit", onEditBinding)
        addButton("🗑 Delete", onDelete)
        addButton("✕ Cancel", onCancel)
        addButton("✓ Done", onDone, accent = true)
    }

    private fun addButton(label: String, onClick: () -> Unit, accent: Boolean = false) {
        val tv = TextView(context).apply {
            text = label
            setTextColor(if (accent) 0xFF7ED97E.toInt() else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val h = dp(10)
            val v = dp(8)
            setPadding(h, v, h, v)
            isClickable = true
            setOnClickListener { onClick() }
        }
        addView(tv)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
