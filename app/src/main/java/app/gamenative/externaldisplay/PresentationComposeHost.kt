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

// Hosts Compose content on the cover screen presentation, which has no ViewTree owners of its own
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

        // Compose looks the owners up from the window's root view
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
        ownerHostRoot?.let { root ->
            root.setViewTreeLifecycleOwner(null)
            root.setViewTreeViewModelStoreOwner(null)
            root.setViewTreeSavedStateRegistryOwner(null)
        }
        ownerHostRoot = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
