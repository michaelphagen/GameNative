package app.gamenative.ui.screen.xserver

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
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
class FoldableControlsState internal constructor(
    /** True only when the device has a hinge AND the user enabled foldable support. */
    val deviceIsFoldable: Boolean,
) {
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
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE) &&
                PrefManager.foldableSupportEnabled,
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

/**
 * Content for the cover presentation. Auto-creates a container-specific cover profile with the
 * default shoulder-button layout on first use; the user customizes it from there via
 * "Edit Cover Screen Buttons". The inner-screen controls are left untouched.
 */
fun buildCoverScreenContent(
    state: FoldableControlsState,
    container: Container,
    xServer: XServer?,
    ctx: Context,
): View {
    val manager = PluviaApp.inputControlsManager
    var coverProfile = container.getExtra("profileIdCover", "0").toIntOrNull()
        ?.takeIf { it != 0 }
        ?.let { manager?.getProfile(it) }
    if (coverProfile == null && manager != null) {
        try {
            coverProfile = manager.createProfile("${container.name} - Cover Buttons")
            container.putExtra("profileIdCover", coverProfile.id.toString())
            container.saveData()
            Timber.i("Created cover profile '%s' for container %s", coverProfile.name, container.name)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create cover profile for container %s", container.name)
            coverProfile = null
        }
    }
    if (coverProfile != null && xServer != null) {
        val profile = coverProfile
        // Merge cover input into the overlay profile's gamepad state so committing one
        // doesn't release buttons held on the other
        PluviaApp.inputControlsView?.profile?.let { profile.setSharedGamepadState(it.gamepadState) }
        val icView = InputControlsView(ctx)
        icView.setXServer(xServer)
        icView.setProfile(profile)
        icView.setOverlayOpacity(PrefManager.getFloat("controls_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY))
        icView.post {
            // Seed the default layout once the view has dimensions (positions are normalized
            // against the view size at save time)
            profile.loadElements(icView)
            if (profile.elements.isEmpty()) {
                seedCoverProfile(profile, icView)
                profile.save()
                profile.loadElements(icView)
            }
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
 * Sizes and anchors the game area within its parent, per the container's gameAreaAnchor
 * ("full"/"left"/"right") and gameAreaWidth (percent) extras. Only active while unfolded with
 * foldable support enabled; otherwise restores fullscreen. The touchpad view is kept congruent
 * with the game host so touch-to-game coordinate mapping stays correct (TouchpadView rebuilds
 * its transform in onSizeChanged).
 */
fun applyGameAreaLayout(state: FoldableControlsState, container: Container) {
    val host = PluviaApp.gameHostView ?: return
    host.post {
        val parent = host.parent as? View ?: return@post
        val anchor = container.getExtra("gameAreaAnchor", "full")
        val sizePct = (container.getExtra("gameAreaWidth", "100").toIntOrNull() ?: 100).coerceIn(30, 100)
        val active = state.deviceIsFoldable && state.isDeviceUnfolded && !container.isPortraitMode &&
            anchor != "full" && sizePct < 100 && parent.width > 0 && parent.height > 0
        var width = ViewGroup.LayoutParams.MATCH_PARENT
        var height = ViewGroup.LayoutParams.MATCH_PARENT
        var gravity = Gravity.NO_GRAVITY
        if (active) {
            when (anchor) {
                "left" -> {
                    width = parent.width * sizePct / 100
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
                "right" -> {
                    width = parent.width * sizePct / 100
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
                "top" -> {
                    height = parent.height * sizePct / 100
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
                "bottom" -> {
                    height = parent.height * sizePct / 100
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
            }
        }
        host.layoutParams = FrameLayout.LayoutParams(width, height, gravity)
        PluviaApp.touchpadView?.let { touchpad ->
            if (touchpad.parent === parent) {
                touchpad.layoutParams = FrameLayout.LayoutParams(width, height, gravity)
            }
        }
        Timber.i("Game area layout: anchor=%s size=%d%% active=%s", anchor, sizePct, active)
    }
}

/** Quick-menu dialog to move/resize the game area on the unfolded inner display. */
@Composable
fun GameAreaDialog(
    state: FoldableControlsState,
    container: Container,
    onDismiss: () -> Unit,
) {
    var anchor by remember { mutableStateOf(container.getExtra("gameAreaAnchor", "full")) }
    var sizePct by remember {
        mutableIntStateOf((container.getExtra("gameAreaWidth", "100").toIntOrNull() ?: 100).coerceIn(30, 100))
    }

    fun applyAndPersist() {
        container.putExtra("gameAreaAnchor", anchor)
        container.putExtra("gameAreaWidth", sizePct.toString())
        container.saveData()
        applyGameAreaLayout(state, container)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.game_area_title)) },
        text = {
            Column {
                listOf(
                    listOf(
                        "full" to R.string.game_area_full,
                        "left" to R.string.game_area_left,
                        "right" to R.string.game_area_right,
                    ),
                    listOf(
                        "top" to R.string.game_area_top,
                        "bottom" to R.string.game_area_bottom,
                    ),
                ).forEach { rowOptions ->
                    Row {
                        rowOptions.forEach { (option, labelRes) ->
                            FilterChip(
                                selected = anchor == option,
                                onClick = {
                                    anchor = option
                                    applyAndPersist()
                                },
                                label = { Text(text = stringResource(labelRes)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }
                if (anchor != "full") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Slider(
                            value = sizePct.toFloat(),
                            onValueChange = { sizePct = it.toInt() },
                            onValueChangeFinished = { applyAndPersist() },
                            valueRange = 30f..100f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "$sizePct%",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        },
    )
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

    LaunchedEffect(coverScreenManager, state.deviceIsFoldable) {
        if (coverScreenManager != null && state.deviceIsFoldable) {
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
