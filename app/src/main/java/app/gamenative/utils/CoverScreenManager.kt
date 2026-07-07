package app.gamenative.utils

import android.app.Activity
import androidx.core.content.ContextCompat
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import app.gamenative.PluviaApp
import app.gamenative.externaldisplay.CoverScreenShoulderView
import com.winlator.inputcontrols.Binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Presents shoulder buttons on the rear/cover display of a foldable while a game runs on the
 * inner display, using the Jetpack WindowArea rear-display presentation ("dual screen") mode.
 * On devices without a rear display (or without vendor support) this is a no-op.
 */
@OptIn(ExperimentalWindowApi::class)
class CoverScreenManager(private val activity: Activity) {

    private var session: WindowAreaSessionPresenter? = null
    private var presenting = false
    private var watchJob: Job? = null
    private var onButtonEvent: ((Binding, Boolean) -> Unit)? = null

    /**
     * Watches for a rear-facing window area and presents the shoulder buttons on it whenever
     * it becomes available (e.g. each time the device is unfolded). Safe to call on any device.
     */
    fun start(scope: CoroutineScope, onButtonEvent: (binding: Binding, pressed: Boolean) -> Unit) {
        this.onButtonEvent = onButtonEvent
        watchJob?.cancel()
        watchJob = scope.launch {
            try {
                WindowAreaController.getOrCreate().windowAreaInfos.collect { infos ->
                    val rearArea = infos.firstOrNull { info ->
                        info.type == WindowAreaInfo.Type.TYPE_REAR_FACING &&
                            info.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)
                                ?.status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE
                    }
                    if (rearArea != null && session == null && !presenting) {
                        present(rearArea)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "CoverScreenManager: failed to observe window areas")
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        onButtonEvent = null
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
        try {
            WindowAreaController.getOrCreate().presentContentOnWindowArea(
                areaInfo.token,
                activity,
                ContextCompat.getMainExecutor(activity),
                object : WindowAreaPresentationSessionCallback {
                    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
                        Timber.i("CoverScreenManager: cover screen session started")
                        this@CoverScreenManager.session = session
                        try {
                            val view = CoverScreenShoulderView(session.context) { binding, pressed ->
                                onButtonEvent?.invoke(binding, pressed)
                            }
                            session.setContentView(view)
                            setSessionActive(true)
                        } catch (e: Exception) {
                            Timber.e(e, "CoverScreenManager: failed to attach cover screen content")
                            try {
                                session.close()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    override fun onSessionEnded(t: Throwable?) {
                        if (t != null) Timber.w(t, "CoverScreenManager: cover screen session ended with error")
                        else Timber.i("CoverScreenManager: cover screen session ended")
                        session = null
                        presenting = false
                        setSessionActive(false)
                    }

                    override fun onContainerVisibilityChanged(isVisible: Boolean) {
                        Timber.d("CoverScreenManager: cover screen visibility changed: $isVisible")
                    }
                },
            )
        } catch (e: Exception) {
            Timber.e(e, "CoverScreenManager: failed to present on cover screen")
            presenting = false
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
