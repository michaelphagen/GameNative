package app.gamenative.ui.screen.xserver

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.externaldisplay.CoverScreenShoulderView
import app.gamenative.ui.enums.Orientation
import app.gamenative.utils.CoverScreenManager
import com.winlator.container.Container
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.widget.InputControlsView
import com.winlator.xserver.XServer
import java.util.EnumSet
import kotlinx.coroutines.awaitCancellation
import timber.log.Timber

/**
 * Foldable-specific in-game state: fold posture, cover-layout editing, and the view hosted on
 * the cover presentation. Lives outside XServerScreen's (enormous) composable body — adding
 * locals there risks tripping the ART bytecode verifier's register limits.
 */
class FoldableControlsState internal constructor(val deviceIsFoldable: Boolean) {
    var isDeviceUnfolded by mutableStateOf(false)
    var editingCoverProfile by mutableStateOf(false)

    /**
     * The InputControlsView living on the cover presentation (null when the fallback grid or
     * no session is shown). Cover-layout edit mode runs directly on this view, so buttons are
     * dragged on the physical panel they live on.
     */
    var coverInputControlsView: InputControlsView? = null
}

@Composable
fun rememberFoldableControlsState(): FoldableControlsState {
    val context = LocalContext.current
    return remember {
        FoldableControlsState(
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE),
        )
    }
}

/** Orientation lock for the current fold state (foldables get independent locks per state). */
fun currentAllowedOrientations(state: FoldableControlsState, container: Container): EnumSet<Orientation> = when {
    container.isPortraitMode -> EnumSet.of(Orientation.PORTRAIT)
    !state.deviceIsFoldable -> PrefManager.allowedOrientation
    state.isDeviceUnfolded -> PrefManager.allowedOrientationUnfolded
    else -> PrefManager.allowedOrientationFolded
}

/** Overlay profile for the current fold state: folded uses profileIdFolded with fallback. */
fun resolveOverlayProfile(state: FoldableControlsState, container: Container): ControlsProfile? {
    val manager = PluviaApp.inputControlsManager ?: return null
    val useFoldedKey = state.deviceIsFoldable && !state.isDeviceUnfolded
    var id = container.getExtra(if (useFoldedKey) "profileIdFolded" else "profileId", "0").toIntOrNull() ?: 0
    if (id == 0 && useFoldedKey) {
        id = container.getExtra("profileId", "0").toIntOrNull() ?: 0
    }
    val profiles = manager.getProfiles(false)
    return (if (id != 0) manager.getProfile(id) else null)
        ?: manager.getProfile(0)
        ?: profiles.getOrNull(2)
        ?: profiles.firstOrNull()
}

fun applyOverlayProfileForFoldState(state: FoldableControlsState, container: Container, isEditMode: Boolean) {
    if (isEditMode) return
    val icView = PluviaApp.inputControlsView ?: return
    val target = resolveOverlayProfile(state, container) ?: return
    if (icView.profile?.id == target.id) return
    Timber.i("Fold state changed, switching overlay profile to '%s'", target.name)
    icView.setProfile(target)
    icView.post {
        target.loadElements(icView)
        icView.invalidate()
    }
}

/** Seed a new cover profile with the default shoulder-button grid (normalized positions). */
fun seedCoverProfile(profile: ControlsProfile, view: InputControlsView) {
    val w = view.maxWidth
    val h = view.maxHeight
    // Mirrored: the cover screen faces away, so L buttons sit on the rendered right
    val defaults = listOf(
        Triple(Binding.GAMEPAD_BUTTON_R1, 0.25f, 0.2f),
        Triple(Binding.GAMEPAD_BUTTON_R2, 0.25f, 0.5f),
        Triple(Binding.GAMEPAD_BUTTON_R3, 0.25f, 0.8f),
        Triple(Binding.GAMEPAD_BUTTON_L1, 0.75f, 0.2f),
        Triple(Binding.GAMEPAD_BUTTON_L2, 0.75f, 0.5f),
        Triple(Binding.GAMEPAD_BUTTON_L3, 0.75f, 0.8f),
    )
    defaults.forEach { (binding, fx, fy) ->
        val element = ControlElement(view)
        element.setBindingAt(0, binding)
        element.scale = 1.5f
        element.setX((fx * w).toInt())
        element.setY((fy * h).toInt())
        profile.addElement(element)
    }
}

/** Content for the cover presentation: the cover profile if one exists, else the default grid. */
fun buildCoverScreenContent(
    state: FoldableControlsState,
    container: Container,
    xServer: XServer?,
    ctx: Context,
): View {
    val manager = PluviaApp.inputControlsManager
    val coverId = container.getExtra("profileIdCover", "0").toIntOrNull() ?: 0
    val coverProfile = if (coverId != 0) manager?.getProfile(coverId) else null
    if (coverProfile != null && xServer != null) {
        // Merge cover input into the overlay profile's gamepad state so committing one
        // doesn't release buttons held on the other
        PluviaApp.inputControlsView?.profile?.let { coverProfile.setSharedGamepadState(it.gamepadState) }
        val icView = InputControlsView(ctx)
        icView.setCoverScreenView(true)
        icView.setXServer(xServer)
        icView.setProfile(coverProfile)
        icView.setOverlayOpacity(PrefManager.getFloat("controls_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY))
        icView.post {
            coverProfile.loadElements(icView)
            icView.invalidate()
        }
        state.coverInputControlsView = icView
        // The cover panel is portrait-natural but gameplay is typically landscape;
        // rotate the content when the aspects don't match.
        val dm = ctx.resources.displayMetrics
        val presentationPortrait = dm.heightPixels >= dm.widthPixels
        val editedPortrait = container.isPortraitMode
        return if (presentationPortrait != editedPortrait) {
            val wrapper = FrameLayout(ctx)
            icView.rotation = 90f
            wrapper.addView(icView, FrameLayout.LayoutParams(dm.heightPixels, dm.widthPixels, Gravity.CENTER))
            wrapper
        } else {
            icView
        }
    }
    state.coverInputControlsView = null
    return CoverScreenShoulderView(ctx) { binding, pressed ->
        PluviaApp.inputControlsView?.handleExternalInputEvent(binding, pressed)
    }
}

/**
 * Fold-state tracking and cover-session lifecycle, hoisted out of XServerScreen.
 * [onFoldStateChanged] fires on every posture change (and once for the initial posture).
 */
@Composable
fun FoldableControlsEffects(
    state: FoldableControlsState,
    activity: Activity?,
    lifecycleOwner: LifecycleOwner,
    coverScreenManager: CoverScreenManager?,
    coverEnabled: Boolean,
    buildCoverContent: (Context) -> View,
    onFoldStateChanged: (Boolean) -> Unit,
) {
    // Track fold posture; FoldingFeature is only reported while the app spans the hinge
    LaunchedEffect(activity, state.deviceIsFoldable) {
        if (activity != null && state.deviceIsFoldable) {
            var previous: Boolean? = null
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { layout ->
                    val unfolded = layout.displayFeatures.any { it is FoldingFeature }
                    if (previous != unfolded) {
                        previous = unfolded
                        state.isDeviceUnfolded = unfolded
                        Timber.i("Fold state: %s", if (unfolded) "unfolded" else "folded")
                        onFoldStateChanged(unfolded)
                    }
                }
        }
    }

    LaunchedEffect(coverScreenManager) {
        if (coverScreenManager != null && coverEnabled) {
            // Folding transitions cycle the activity lifecycle and suspend the game in between.
            // Scope the cover session strictly to the RESUMED state so it is torn down before
            // every fold transition and freshly re-presented once the activity settles.
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    coverScreenManager.start(this, buildCoverContent)
                    awaitCancellation()
                } finally {
                    coverScreenManager.stop()
                }
            }
        }
    }
}
