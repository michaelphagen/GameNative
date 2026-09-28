package app.gamenative.ui.screen.xserver

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.gamenative.R
import app.gamenative.externaldisplay.EditToolbarOverlayView
import com.winlator.inputcontrols.ControlElement
import com.winlator.widget.InputControlsView

/**
 * Edits the inner and cover screen layouts in one session. Both views are in edit mode but only
 * one is active; the other is locked until the user swipes it open. The inner screen keeps using
 * the regular edit toolbar, the cover gets a plain-view one.
 */
class UnifiedControlsEditor(
    private val innerView: InputControlsView,
    private val coverParent: ViewGroup,
    private val coverView: InputControlsView,
    onEditCover: () -> Unit,
    private val onActiveChanged: (activeIsCover: Boolean) -> Unit,
    private val onFinished: () -> Unit,
) {
    private val lockMessage = coverParent.context.getString(R.string.unified_lock_message)
    private val lockHint = coverParent.context.getString(R.string.unified_lock_hint)

    private val coverToolbar = EditToolbarOverlayView(
        coverParent.context,
        onAdd = { if (coverView.addElement()) coverView.invalidate() },
        onEdit = onEditCover,
        onDelete = { coverView.removeElement() },
        onCancel = ::cancel,
        onDone = ::done,
    )

    private val innerSnapshot = HashMap<ControlElement, Pair<Int, Int>>()
    private val coverSnapshot = HashMap<ControlElement, Pair<Int, Int>>()

    fun start() {
        enterEdit(innerView, innerSnapshot)
        enterEdit(coverView, coverSnapshot)
        coverParent.addView(
            coverToolbar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = (coverParent.resources.displayMetrics.density * 12).toInt() },
        )
        activate(cover = false)
    }

    private fun enterEdit(view: InputControlsView, snapshot: MutableMap<ControlElement, Pair<Int, Int>>) {
        view.setEditMode(true)
        view.post {
            view.profile?.loadElements(view)
            snapshot.clear()
            view.profile?.elements?.forEach { snapshot[it] = it.x.toInt() to it.y.toInt() }
            view.invalidate()
        }
    }

    private fun activate(cover: Boolean) {
        innerView.setEditLocked(cover, lockMessage, lockHint) { activate(false) }
        coverView.setEditLocked(!cover, lockMessage, lockHint) { activate(true) }
        coverToolbar.visibility = if (cover) View.VISIBLE else View.GONE
        onActiveChanged(cover)
    }

    fun done() {
        innerView.profile?.save()
        innerView.onControlsProfileContentChanged(false)
        coverView.profile?.save()
        stop(innerView)
        onFinished()
    }

    fun cancel() {
        restore(innerView, innerSnapshot)
        restore(coverView, coverSnapshot)
        stop(innerView)
        onFinished()
    }

    // The cover went away mid-edit, so the inner screen goes back to a normal edit session
    fun abort() {
        innerView.setEditLocked(false, null, null, null)
        stop(null)
    }

    private fun restore(view: InputControlsView, snapshot: Map<ControlElement, Pair<Int, Int>>) {
        snapshot.forEach { (element, pos) ->
            element.setX(pos.first)
            element.setY(pos.second)
        }
        view.profile?.save()
        view.post {
            view.profile?.loadElements(view)
            view.invalidate()
        }
    }

    private fun stop(innerView: InputControlsView?) {
        for (view in listOfNotNull(innerView, coverView)) {
            view.setEditLocked(false, null, null, null)
            view.setEditMode(false)
        }
        if (coverToolbar.parent === coverParent) coverParent.removeView(coverToolbar)
        onActiveChanged(false)
    }
}
