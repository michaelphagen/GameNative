package app.gamenative.externaldisplay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import app.gamenative.R

// Plain-view edit toolbar for the cover screen presentation
class EditToolbarOverlayView(
    context: Context,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) : LinearLayout(context) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(0xCC1E1E1E.toInt())
        }
        addButton(R.string.add, onAdd)
        addButton(R.string.edit, onEdit)
        addButton(R.string.delete, onDelete)
        addButton(R.string.cancel, onCancel)
        addButton(R.string.done, onDone, accent = true)
    }

    private fun addButton(labelRes: Int, onClick: () -> Unit, accent: Boolean = false) {
        addView(
            TextView(context).apply {
                setText(labelRes)
                setTextColor(if (accent) 0xFF7ED97E.toInt() else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { onClick() }
            },
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
