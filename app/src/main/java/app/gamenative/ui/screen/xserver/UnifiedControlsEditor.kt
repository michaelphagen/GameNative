package app.gamenative.ui.screen.xserver

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.gamenative.externaldisplay.EditToolbarOverlayView
import com.winlator.inputcontrols.ControlElement
import com.winlator.widget.InputControlsView
import timber.log.Timber

/**
 * Drives a single control-editing session spanning the inner screen and the foldable cover
 * screen at once (unfolded only). Both InputControlsViews are in edit mode, but exactly one is
 * "active"; the other is locked (via [InputControlsView.setEditLocked]) showing a swipe-to-unlock
 * overlay drawn inside the view itself — which is the only place that reliably intercepts touches,
 * since the inner screen routes touches through Compose rather than the view hierarchy.
 *
 * The active screen shows an editing toolbar. The cover's toolbar is a native Android view (its
 * Presentation can't host Compose); the inner screen reuses the existing Compose toolbar, so this
 * class only owns the cover toolbar and drives which screen is active.
 */
class UnifiedControlsEditor(
    private val innerView: InputControlsView,
    private val coverParent: ViewGroup,
    private val coverView: InputControlsView,
    private val lockMessage: String,
    private val lockHint: String,
    onEditBinding: () -> Unit,
    private val onActiveChanged: (activeIsCover: Boolean) -> Unit,
    private val onFinished: () -> Unit,
) {
    var activeIsCover = false
        private set

    private val coverToolbar = EditToolbarOverlayView(
        coverParent.context,
        onAdd = { if (coverView.addElement()) coverView.invalidate() },
        onEditBinding = onEditBinding,
        onDelete = { coverView.removeElement(); coverView.invalidate() },
        onCancel = { cancel() },
        onDone = { done() },
    )

    private val innerSnapshot = HashMap<ControlElement, Pair<Int, Int>>()
    private val coverSnapshot = HashMap<ControlElement, Pair<Int, Int>>()

    fun activeView(): InputControlsView = if (activeIsCover) coverView else innerView

    fun start() {
        enterEdit(innerView)
        enterEdit(coverView)
        innerView.setEditLockLabels(lockMessage, lockHint)
        coverView.setEditLockLabels(lockMessage, lockHint)
        innerView.setOnUnlockListener { activate(false) }
        coverView.setOnUnlockListener { activate(true) }
        addTopCenter(coverParent, coverToolbar)
        innerView.post { snapshot(innerView, innerSnapshot) }
        coverView.post { snapshot(coverView, coverSnapshot) }
        activate(false) // inner active first
    }

    private fun enterEdit(view: InputControlsView) {
        view.setEditMode(true)
        view.post {
            view.profile?.loadElements(view)
            view.invalidate()
        }
    }

    private fun snapshot(view: InputControlsView, into: MutableMap<ControlElement, Pair<Int, Int>>) {
        into.clear()
        view.profile?.elements?.forEach { into[it] = it.x.toInt() to it.y.toInt() }
    }

    private fun activate(cover: Boolean) {
        activeIsCover = cover
        innerView.setEditLocked(cover)
        coverView.setEditLocked(!cover)
        coverToolbar.visibility = if (cover) View.VISIBLE else View.GONE
        innerView.invalidate()
        coverView.invalidate()
        onActiveChanged(cover)
        Timber.i("Unified editor: active screen = %s", if (cover) "cover" else "inner")
    }

    fun done() {
        innerView.profile?.save()
        coverView.profile?.save()
        cleanup()
        onFinished()
    }

    fun cancel() {
        restore(innerView, innerSnapshot)
        restore(coverView, coverSnapshot)
        cleanup()
        onFinished()
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

    private fun cleanup() {
        innerView.setEditLocked(false)
        coverView.setEditLocked(false)
        innerView.setOnUnlockListener(null)
        coverView.setOnUnlockListener(null)
        innerView.setEditMode(false)
        coverView.setEditMode(false)
        removeFrom(coverParent, coverToolbar)
        innerView.invalidate()
        coverView.invalidate()
    }

    /** Tear down without saving (device folded or app minimized mid-edit). */
    fun abort() {
        cleanup()
    }

    private fun addTopCenter(parent: ViewGroup, view: View) {
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = (parent.resources.displayMetrics.density * 12).toInt() }
        parent.addView(view, lp)
    }

    private fun removeFrom(parent: ViewGroup, view: View) {
        if (view.parent === parent) parent.removeView(view)
    }
}
