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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
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
        val icView = InputControlsView(ctx)
        icView.setXServer(xServer)
        // Merge cover input into the canonical shared gamepad state so pressing a cover button
        // doesn't clear sticks/buttons currently held on the inner overlay (and vice versa)
        PluviaApp.sharedVirtualGamepadState?.let { icView.setSharedGamepadState(it) }
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
/** Max inset per edge (percent); leaves at least ~30% of each dimension for the game. */
const val GAME_AREA_MAX_INSET = 70

private fun gameAreaInset(container: Container, edge: String): Int =
    (container.getExtra("gameAreaInset$edge", "0").toIntOrNull() ?: 0).coerceIn(0, GAME_AREA_MAX_INSET)

fun applyGameAreaLayout(state: FoldableControlsState, container: Container) {
    val host = PluviaApp.gameHostView ?: return
    host.post {
        val parent = host.parent as? View ?: return@post
        val enabled = state.deviceIsFoldable && state.isDeviceUnfolded && !container.isPortraitMode &&
            parent.width > 0 && parent.height > 0

        // Four independent edge insets (percent of the corresponding dimension). The game area
        // is the parent rectangle minus these insets, positioned via view margins so it can be
        // both moved and resized freely (e.g. 40% left + 20% top).
        var left = if (enabled) gameAreaInset(container, "Left") else 0
        var right = if (enabled) gameAreaInset(container, "Right") else 0
        var top = if (enabled) gameAreaInset(container, "Top") else 0
        var bottom = if (enabled) gameAreaInset(container, "Bottom") else 0
        // Keep opposite insets from collapsing the area past ~15% of a dimension
        if (left + right > 85) right = 85 - left
        if (top + bottom > 85) bottom = 85 - top

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply {
            leftMargin = parent.width * left / 100
            rightMargin = parent.width * right / 100
            topMargin = parent.height * top / 100
            bottomMargin = parent.height * bottom / 100
        }
        host.layoutParams = lp
        PluviaApp.touchpadView?.let { touchpad ->
            if (touchpad.parent === parent) {
                touchpad.layoutParams = FrameLayout.LayoutParams(lp.width, lp.height).apply {
                    leftMargin = lp.leftMargin
                    rightMargin = lp.rightMargin
                    topMargin = lp.topMargin
                    bottomMargin = lp.bottomMargin
                }
            }
        }
        Timber.i("Game area insets: L=%d R=%d T=%d B=%d enabled=%s", left, right, top, bottom, enabled)
    }
}

/** Quick-menu dialog to move/resize the game area on the unfolded inner display. */
@Composable
fun GameAreaDialog(
    state: FoldableControlsState,
    container: Container,
    onDismiss: () -> Unit,
) {
    var left by remember { mutableIntStateOf(gameAreaInset(container, "Left")) }
    var right by remember { mutableIntStateOf(gameAreaInset(container, "Right")) }
    var top by remember { mutableIntStateOf(gameAreaInset(container, "Top")) }
    var bottom by remember { mutableIntStateOf(gameAreaInset(container, "Bottom")) }

    fun applyAndPersist() {
        container.putExtra("gameAreaInsetLeft", left.toString())
        container.putExtra("gameAreaInsetRight", right.toString())
        container.putExtra("gameAreaInsetTop", top.toString())
        container.putExtra("gameAreaInsetBottom", bottom.toString())
        container.saveData()
        applyGameAreaLayout(state, container)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.game_area_title)) },
        text = {
            Column {
                Text(text = stringResource(R.string.game_area_subtitle))
                InsetSlider(R.string.game_area_left, left, { left = it }, { applyAndPersist() })
                InsetSlider(R.string.game_area_right, right, { right = it }, { applyAndPersist() })
                InsetSlider(R.string.game_area_top, top, { top = it }, { applyAndPersist() })
                InsetSlider(R.string.game_area_bottom, bottom, { bottom = it }, { applyAndPersist() })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun InsetSlider(labelRes: Int, value: Int, onChange: (Int) -> Unit, onCommit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.width(56.dp),
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            onValueChangeFinished = onCommit,
            valueRange = 0f..GAME_AREA_MAX_INSET.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(text = "$value%", modifier = Modifier.width(44.dp).padding(start = 8.dp))
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
