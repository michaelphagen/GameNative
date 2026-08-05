package app.gamenative.utils

import android.app.Activity
import androidx.core.content.ContextCompat
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import app.gamenative.PluviaApp
import app.gamenative.externaldisplay.CoverScreenShoulderView
import com.winlator.inputcontrols.Binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Presents shoulder buttons on the rear/cover display of a foldable while a game runs on the
 * inner display, using the Jetpack WindowArea rear-display presentation ("dual screen") mode.
 * On devices without a rear display (or without vendor support) this is a no-op.
 *
 * Lifecycle: the caller starts this only while the activity is RESUMED and stops it when the
 * activity pauses (folding/unfolding recreates the activity, so sessions must not outlive a
 * resumed state). Within a resumed period, the window-area status drives the state machine —
 * vendor extensions don't reliably deliver onSessionEnded when the device folds, so callbacks
 * alone can't be trusted.
 */
@OptIn(ExperimentalWindowApi::class)
class CoverScreenManager(private val activity: Activity) {

    private var session: WindowAreaSessionPresenter? = null
    private var presenting = false
    private var watchJob: Job? = null
    private var onButtonEvent: ((Binding, Boolean) -> Unit)? = null

    // Identity of the most recent present() request; stale session callbacks (from a request
    // that was superseded or cleaned up) are ignored so they can't clobber current state.
    private var currentCallback: WindowAreaPresentationSessionCallback? = null

    /**
     * Watches for a rear-facing window area and presents the shoulder buttons on it whenever
     * it becomes available (e.g. each time the device is unfolded). Safe to call on any device.
     */
    fun start(scope: CoroutineScope, onButtonEvent: (binding: Binding, pressed: Boolean) -> Unit) {
        this.onButtonEvent = onButtonEvent
        watchJob?.cancel()
        watchJob = scope.launch {
            try {
                val areaFlow = WindowAreaController.getOrCreate().windowAreaInfos
                // FoldingFeature is only reported while the app is on the hinge-spanning inner
                // display, i.e. the device is unfolded. While folded, the "rear-facing" area is
                // the INNER display — presenting onto it would fight the fold transition.
                val layoutFlow = WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
                combine(areaFlow, layoutFlow) { infos, layout ->
                    val rearArea = infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
                    val unfolded = layout.displayFeatures.any { it is FoldingFeature }
                    Pair(rearArea, unfolded)
                }.collect { (rearArea, unfolded) ->
                    val status = rearArea?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status
                    when {
                        !unfolded -> {
                            if (session != null || presenting) {
                                Timber.i("CoverScreenManager: device folded, closing session")
                                closeSession()
                            }
                        }
                        status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE -> {
                            if (session != null) {
                                // AVAILABLE while we think a session is live: the fold ended the
                                // session without a callback. Reset and re-present.
                                Timber.w("CoverScreenManager: stale session detected, re-presenting")
                                closeSession()
                            }
                            if (session == null && !presenting) present(rearArea)
                        }
                        status != WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> {
                            // Unavailable/unsupported: drop any session or in-flight request so
                            // on-screen buttons return and we can retry later.
                            if (session != null || presenting) {
                                Timber.i("CoverScreenManager: window area no longer available (status=$status), closing session")
                                closeSession()
                            }
                        }
                        // ACTIVE with our live session: steady state, nothing to do.
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
        onButtonEvent = null
        closeSession()
    }

    private fun closeSession() {
        currentCallback = null // orphan any in-flight callback
        try {
            session?.close()
        } catch (e: Exception) {
            Timber.w(e, "CoverScreenManager: error closing session")
        }
        session = null
        presenting = false
        setSessionActive(false)
    }

    private fun present(areaInfo: WindowAreaInfo) {
        presenting = true
        val callback = object : WindowAreaPresentationSessionCallback {
            override fun onSessionStarted(session: WindowAreaSessionPresenter) {
                if (currentCallback !== this) {
                    Timber.i("CoverScreenManager: ignoring stale session start")
                    try {
                        session.close()
                    } catch (_: Exception) {
                    }
                    return
                }
                Timber.i("CoverScreenManager: cover screen session started")
                this@CoverScreenManager.session = session
                presenting = false
                try {
                    val view = CoverScreenShoulderView(session.context) { binding, pressed ->
                        onButtonEvent?.invoke(binding, pressed)
                    }
                    session.setContentView(view)
                    makeWindowNotFocusable(view)
                    setSessionActive(true)
                } catch (e: Exception) {
                    Timber.e(e, "CoverScreenManager: failed to attach cover screen content")
                    closeSession()
                }
            }

            override fun onSessionEnded(t: Throwable?) {
                if (currentCallback !== this) return
                if (t != null) Timber.w(t, "CoverScreenManager: cover screen session ended with error")
                else Timber.i("CoverScreenManager: cover screen session ended")
                currentCallback = null
                session = null
                presenting = false
                setSessionActive(false)
            }

            override fun onContainerVisibilityChanged(isVisible: Boolean) {
                if (currentCallback !== this) return
                Timber.d("CoverScreenManager: cover screen visibility changed: $isVisible")
                // While the cover content is hidden (e.g. mid-fold), restore on-screen buttons
                setSessionActive(isVisible && session != null)
            }
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

    /**
     * The presentation window is focusable by default and would steal input focus from the
     * game activity, killing physical controller/keyboard input (dispatchKeyEvent only fires
     * on the focused window). Non-focusable windows still receive touch on their own display.
     * Mirrors GamePresentation's FLAG_NOT_FOCUSABLE setup for external displays.
     */
    private fun makeWindowNotFocusable(contentView: android.view.View) {
        contentView.post {
            try {
                val root = contentView.rootView
                val lp = root.layoutParams
                if (lp is android.view.WindowManager.LayoutParams) {
                    lp.flags = lp.flags or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    val wm = contentView.context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                    wm.updateViewLayout(root, lp)
                    Timber.i("CoverScreenManager: cover window set non-focusable")
                }
            } catch (e: Exception) {
                Timber.w(e, "CoverScreenManager: could not make cover window non-focusable")
            }
        }
    }

    private fun setSessionActive(active: Boolean) {
        if (sessionActive == active) return
        sessionActive = active
        // Redraw the touch overlay so on-screen shoulder buttons hide/reappear
        PluviaApp.inputControlsView?.postInvalidate()
    }

    companion object {
        @Volatile
        private var sessionActive = false

        /** Whether shoulder buttons are currently shown on the cover screen. */
        @JvmStatic
        fun isSessionActive(): Boolean = sessionActive
    }
}
