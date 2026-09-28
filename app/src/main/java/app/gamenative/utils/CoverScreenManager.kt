package app.gamenative.utils

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Presents a view on the rear (cover) display of a foldable while the game runs on the inner
 * display. Only run it while the activity is resumed: vendor extensions don't reliably call
 * onSessionEnded when the device folds, so the window area status drives the session instead.
 */
@OptIn(ExperimentalWindowApi::class)
class CoverScreenManager(private val activity: Activity) {

    private var session: WindowAreaSessionPresenter? = null
    private var presenting = false
    private var watchJob: Job? = null
    private var contentFactory: ((Context) -> View)? = null

    // Callbacks from superseded requests are ignored
    private var currentCallback: WindowAreaPresentationSessionCallback? = null

    fun start(scope: CoroutineScope, contentFactory: (Context) -> View) {
        this.contentFactory = contentFactory
        watchJob?.cancel()
        watchJob = scope.launch {
            try {
                val areas = WindowAreaController.getOrCreate().windowAreaInfos
                // While folded the "rear" area is the inner display, so only present when unfolded
                val layouts = WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
                combine(areas, layouts) { infos, layout ->
                    infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING } to
                        layout.displayFeatures.any { it is FoldingFeature }
                }.collect { (rearArea, unfolded) ->
                    val status = rearArea?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status
                    when {
                        !unfolded -> closeSession()

                        status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE -> {
                            // AVAILABLE while we hold a session means it ended without a callback
                            if (session != null) closeSession()
                            if (!presenting) present(rearArea)
                        }

                        status != WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> closeSession()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "CoverScreenManager: failed to observe window areas")
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        contentFactory = null
        closeSession()
    }

    private fun closeSession() {
        currentCallback = null
        try {
            session?.close()
        } catch (e: Exception) {
            Timber.w(e, "CoverScreenManager: error closing session")
        }
        session = null
        presenting = false
    }

    private fun present(areaInfo: WindowAreaInfo) {
        presenting = true
        val callback = object : WindowAreaPresentationSessionCallback {
            override fun onSessionStarted(session: WindowAreaSessionPresenter) {
                if (currentCallback !== this) {
                    runCatching { session.close() }
                    return
                }
                Timber.i("CoverScreenManager: cover screen session started")
                this@CoverScreenManager.session = session
                try {
                    val view = contentFactory?.invoke(session.context) ?: error("no content factory")
                    session.setContentView(view)
                    makeWindowNotFocusable(view)
                } catch (e: Exception) {
                    Timber.e(e, "CoverScreenManager: failed to attach cover screen content")
                    closeSession()
                }
            }

            override fun onSessionEnded(t: Throwable?) {
                if (currentCallback !== this) return
                Timber.i(t, "CoverScreenManager: cover screen session ended")
                currentCallback = null
                session = null
                presenting = false
            }

            override fun onContainerVisibilityChanged(isVisible: Boolean) = Unit
        }
        currentCallback = callback
        try {
            WindowAreaController.getOrCreate().presentContentOnWindowArea(
                areaInfo.token,
                activity,
                ContextCompat.getMainExecutor(activity),
                callback,
            )
        } catch (e: Exception) {
            Timber.e(e, "CoverScreenManager: failed to present on cover screen")
            currentCallback = null
            presenting = false
        }
    }

    // A focusable presentation window steals key focus from the game, which breaks physical
    // controller and keyboard input
    private fun makeWindowNotFocusable(contentView: View) {
        contentView.post {
            val root = contentView.rootView
            val lp = root.layoutParams as? WindowManager.LayoutParams ?: return@post
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            runCatching {
                contentView.context.getSystemService(WindowManager::class.java).updateViewLayout(root, lp)
            }.onFailure { Timber.w(it, "CoverScreenManager: could not make cover window non-focusable") }
        }
    }
}
