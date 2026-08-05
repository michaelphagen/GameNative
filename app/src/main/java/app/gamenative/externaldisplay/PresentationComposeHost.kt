package app.gamenative.externaldisplay

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Hosts Compose content inside a display Presentation (the foldable cover screen). A ComposeView
 * needs ViewTree lifecycle / viewmodel / saved-state owners to run; a Presentation window doesn't
 * provide them, so this supplies a minimal self-managed set. Without this, ComposeView crashes on
 * the cover with "ViewTreeLifecycleOwner not found".
 *
 * Use [attach] to add the content to a parent view (full-screen), and [detach] to tear it down.
 */
class PresentationComposeHost private constructor(
    context: Context,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore = ViewModelStore()

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val composeView = ComposeView(context)
    private var ownerHostRoot: View? = null

    private fun init(parent: ViewGroup, content: @Composable () -> Unit) {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // Compose installs its window recomposer on the window's ROOT view and reads the
        // ViewTree owners from there — so they must be set on the presentation root, not just on
        // the ComposeView, or attaching crashes with "ViewTreeLifecycleOwner not found".
        val root = parent.rootView
        ownerHostRoot = root
        setOwners(root)
        setOwners(composeView)

        composeView.setContent { content() }
    }

    private fun setOwners(target: View) {
        target.setViewTreeLifecycleOwner(this)
        target.setViewTreeViewModelStoreOwner(this)
        target.setViewTreeSavedStateRegistryOwner(this)
    }

    companion object {
        /** Create a host, add its ComposeView to [parent] full-screen, and render [content]. */
        fun attach(parent: ViewGroup, content: @Composable () -> Unit): PresentationComposeHost {
            val host = PresentationComposeHost(parent.context)
            host.init(parent, content)
            parent.addView(
                host.composeView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            return host
        }
    }

    fun detach() {
        (composeView.parent as? ViewGroup)?.removeView(composeView)
        // Clear the owners we planted on the presentation root so a stale (destroyed) owner
        // isn't left referenced there between editor sessions.
        ownerHostRoot?.let { root ->
            root.setViewTreeLifecycleOwner(null)
            root.setViewTreeViewModelStoreOwner(null)
            root.setViewTreeSavedStateRegistryOwner(null)
        }
        ownerHostRoot = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    val view: View get() = composeView
}
