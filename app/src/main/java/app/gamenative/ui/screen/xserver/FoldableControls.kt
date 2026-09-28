package app.gamenative.ui.screen.xserver

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.externaldisplay.PresentationComposeHost
import app.gamenative.inputcontrols.ControlProfileService
import app.gamenative.inputcontrols.ControlProfileSlot
import app.gamenative.ui.component.dialog.ElementEditorDialog
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.BrightnessManager
import app.gamenative.utils.CoverScreenManager
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.BindingCombo
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.widget.InputControlsView
import java.util.EnumSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

private const val GAME_AREA_MAX_TOTAL_INSET = 85

fun deviceHasHinge(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

fun isFoldableSupportActive(context: Context): Boolean =
    PrefManager.foldableSupportEnabled && deviceHasHinge(context)

// Foldable state for the running game. Kept out of XServerScreen, which is at the dex register limit.
object FoldableSession {
    var deviceIsFoldable by mutableStateOf(false)
        private set

    // Null until the first window layout arrives
    private var isDeviceUnfolded by mutableStateOf<Boolean?>(null)
    var gameHost: FrameLayout? = null
        private set
    var unifiedActiveIsCover by mutableStateOf(false)
        private set
    private var scope: CoroutineScope? = null
    private var container: Container? = null
    private var coverView: InputControlsView? = null
    private var coverContainer: FrameLayout? = null
    private var coverEditorHost: PresentationComposeHost? = null
    private var unifiedEditor: UnifiedControlsEditor? = null

    // Game Area trim of the profile on screen (left, right, top, bottom), cached for layout passes
    var gameArea = IntArray(4)
        private set

    val isFolded: Boolean
        get() = deviceIsFoldable && isDeviceUnfolded == false

    val isUnfolded: Boolean
        get() = deviceIsFoldable && isDeviceUnfolded == true

    // Folded uses its own profile; everything else, including an unknown state, uses the unfolded one
    val activeSlot: ControlProfileSlot
        get() = if (isFolded) ControlProfileSlot.FOLDED else ControlProfileSlot.UNFOLDED

    fun start(gameHost: FrameLayout, container: Container) {
        end()
        this.gameHost = gameHost
        this.container = container
        val activity = BrightnessManager.findActivity(gameHost.context)
        deviceIsFoldable = activity != null && isFoldableSupportActive(activity)
        isDeviceUnfolded = null
        gameArea = IntArray(4)
        if (activity == null || !deviceIsFoldable) return
        resolveOverlayProfile(container)?.let(::loadGameArea)
        val scope = MainScope().also { scope = it }
        // FoldingFeature is only reported while the app spans the hinge, i.e. unfolded
        scope.launch {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
                .map { layout -> layout.displayFeatures.any { it is FoldingFeature } }
                .distinctUntilChanged()
                .collect { unfolded ->
                    Timber.i("Fold state: %s", if (unfolded) "unfolded" else "folded")
                    isDeviceUnfolded = unfolded
                    if (!unfolded) abortUnifiedEditing()
                    applyFoldLayout()
                    applyGameAreaLayout(container)
                }
        }
        // Folding cycles the activity lifecycle, so only keep the cover session while resumed
        val lifecycleOwner = activity as? LifecycleOwner ?: return
        val coverScreen = CoverScreenManager(activity)
        scope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    coverScreen.start(this) { context -> buildCoverContent(container, context) }
                    awaitCancellation()
                } finally {
                    abortUnifiedEditing()
                    coverScreen.stop()
                    coverView = null
                    coverContainer = null
                }
            }
        }
    }

    fun end() {
        abortUnifiedEditing()
        scope?.cancel()
        scope = null
        gameHost = null
        container = null
    }

    // Switches to the profile for the current fold state, unless controls are being edited
    fun applyFoldLayout() {
        val icView = PluviaApp.inputControlsView ?: return
        val container = container ?: return
        if (icView.isEditMode) return
        val profile = resolveOverlayProfile(container) ?: return
        loadGameArea(profile)
        applyGameAreaLayout(container)
        val current = icView.profile
        if (current != null && current.id == profile.id) return
        icView.setProfilePreservingOverlayVisibility(profile)
        PluviaApp.radialMenuCoordinator?.setProfile(profile)
        PluviaApp.touchpadView?.setSensitivity(profile.cursorSpeed)
        if (icView.profile == null) return
        icView.post {
            profile.loadElements(icView)
            icView.invalidate()
        }
    }

    private fun loadGameArea(profile: ControlsProfile) {
        val context = gameHost?.context ?: return
        val json = runCatching { ControlProfileService.readProfileJson(context, profile) }.getOrNull()
        val trim = json?.optJSONObject(ControlProfileService.KEY_GAME_AREA)
        gameArea = IntArray(4) { i ->
            (trim?.optInt(ControlProfileService.GAME_AREA_EDGES[i]) ?: 0).coerceIn(0, ControlProfileService.GAME_AREA_MAX_INSET)
        }
    }

    // Saves the trim into the profile on screen, giving the game its own copy of it first
    fun saveGameArea(trim: IntArray) {
        val container = container ?: return
        val context = gameHost?.context ?: return
        val manager = PluviaApp.inputControlsManager ?: return
        gameArea = trim.copyOf()
        applyGameAreaLayout(container)
        runCatching {
            val profile = ControlProfileService.ensureWorkingProfile(context, container, manager, activeSlot) ?: return
            val json = ControlProfileService.readProfileJson(context, profile)
            json.put(
                ControlProfileService.KEY_GAME_AREA,
                JSONObject().apply { ControlProfileService.GAME_AREA_EDGES.forEachIndexed { i, edge -> put(edge, trim[i]) } },
            )
            FileUtils.writeString(ControlsProfile.getProfileFile(context, profile.id), json.toString())
            applyFoldLayout()
        }.onFailure { Timber.e(it, "Failed to save the game area") }
    }

    // A gyro control in any of the game's profiles counts, not just the one on screen
    fun anyProfileHasGyroControl(): Boolean {
        val container = container ?: return false
        val context = gameHost?.context ?: return false
        val manager = PluviaApp.inputControlsManager ?: return false
        if (!deviceIsFoldable) return false
        return ControlProfileSlot.entries.any { slot ->
            val profile = ControlProfileService.selectedProfile(container, manager, slot) ?: return@any false
            val json = runCatching { ControlProfileService.readProfileJson(context, profile) }.getOrNull() ?: return@any false
            hasBinding(json.optJSONArray("elements"), Binding.GYRO_MODIFIER)
        }
    }

    private fun hasBinding(elements: JSONArray?, binding: Binding): Boolean {
        if (elements == null) return false
        for (i in 0 until elements.length()) {
            val bindings = elements.optJSONObject(i)?.optJSONArray("bindings") ?: continue
            for (j in 0 until bindings.length()) {
                if (BindingCombo.fromJsonValue(bindings.opt(j)).contains(binding)) return true
            }
        }
        return false
    }

    // Starts editing the inner and cover profiles together; false when there is no cover screen
    fun startUnifiedEditing(onFinished: () -> Unit): Boolean {
        val innerView = PluviaApp.inputControlsView ?: return false
        val coverView = coverView?.takeIf { it.isAttachedToWindow } ?: return false
        val coverParent = coverContainer ?: return false
        if (!isUnfolded || unifiedEditor != null) return false
        // Editing may have reloaded the inner profile, so share its gamepad state again
        innerView.profile?.gamepadState?.let { coverView.profile?.setGamepadState(it) }
        unifiedEditor = UnifiedControlsEditor(
            innerView = innerView,
            coverParent = coverParent,
            coverView = coverView,
            onEditCover = ::showElementEditorOnCover,
            onActiveChanged = { unifiedActiveIsCover = it },
            onFinished = {
                detachCoverEditor()
                unifiedEditor = null
                onFinished()
            },
        ).also { it.start() }
        return true
    }

    // The cover went away mid-edit: keep editing the inner screen on its own
    private fun abortUnifiedEditing() {
        val editor = unifiedEditor ?: return
        unifiedEditor = null
        detachCoverEditor()
        editor.abort()
    }

    // Save or cancel from the inner toolbar; false when no unified session is running
    fun finishUnifiedEditing(save: Boolean): Boolean {
        val editor = unifiedEditor ?: return false
        if (save) editor.done() else editor.cancel()
        return true
    }

    private fun buildCoverContent(container: Container, context: Context): View {
        val xServer = PluviaApp.xServerView?.getxServer()
        val profile = coverProfile(container)
        if (xServer == null || profile == null) {
            coverView = null
            coverContainer = null
            return View(context)
        }
        val icView = InputControlsView(context)
        icView.setXServer(xServer)
        icView.setGyroModifierTarget(PluviaApp.inputControlsView)
        icView.setOverlayOpacity(PrefManager.getFloat("controls_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY))
        showCoverProfile(icView, profile)

        val wrapper = FrameLayout(context)
        wrapper.addView(icView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wrapper.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            rotateCoverControls(wrapper, icView, container.isPortraitMode)
        }
        coverView = icView
        coverContainer = wrapper
        return wrapper
    }

    // Reloads the cover after one of the game's profiles was replaced
    fun reloadCover() {
        val view = coverView?.takeIf { it.isAttachedToWindow } ?: return
        container?.let(::coverProfile)?.let { showCoverProfile(view, it) }
    }

    private fun coverProfile(container: Container): ControlsProfile? {
        val context = gameHost?.context ?: return null
        val manager = PluviaApp.inputControlsManager ?: return null
        ControlProfileService.selectedProfile(container, manager, ControlProfileSlot.COVER)?.let { return it }
        return runCatching {
            ControlProfileService.createWorkingProfile(context, container, manager, ControlProfileSlot.COVER, defaultCoverJson(context))
        }.onFailure { Timber.e(it, "Failed to create the cover screen profile") }.getOrNull()
    }

    private fun showCoverProfile(view: InputControlsView, profile: ControlsProfile) {
        // Share the main screen's gamepad state so cover and inner input merge
        PluviaApp.inputControlsView?.profile?.gamepadState?.let(profile::setGamepadState)
        view.setProfile(profile)
        // Element positions are relative to the view size, so wait for layout
        view.doOnLayout {
            profile.loadElements(view)
            view.invalidate()
        }
    }

    // Default shoulder buttons. The cover faces away from the player, so R is drawn on the left.
    private fun defaultCoverJson(context: Context): JSONObject {
        val defaults = listOf(
            Triple(Binding.GAMEPAD_BUTTON_R1, 0.25, 0.2),
            Triple(Binding.GAMEPAD_BUTTON_R2, 0.25, 0.5),
            Triple(Binding.GAMEPAD_BUTTON_R3, 0.25, 0.8),
            Triple(Binding.GAMEPAD_BUTTON_L1, 0.75, 0.2),
            Triple(Binding.GAMEPAD_BUTTON_L2, 0.75, 0.5),
            Triple(Binding.GAMEPAD_BUTTON_L3, 0.75, 0.8),
        )
        val elements = JSONArray()
        for ((binding, x, y) in defaults) {
            elements.put(
                JSONObject()
                    .put("type", "BUTTON")
                    .put("shape", "CIRCLE")
                    .put("bindings", JSONArray().put(binding.name).put(Binding.NONE.name))
                    .put("scale", 1.5)
                    .put("x", x)
                    .put("y", y)
                    .put("toggleSwitch", false)
                    .put("text", "")
                    .put("iconId", 0),
            )
        }
        return JSONObject()
            .put("name", context.getString(R.string.cover_screen_profile_name))
            .put("cursorSpeed", ControlsProfile.DEFAULT_CURSOR_SPEED.toDouble())
            .put("elements", elements)
    }

    // Keep the cover controls in the game's orientation, whichever way the cover panel is
    private fun rotateCoverControls(wrapper: FrameLayout, icView: View, gamePortrait: Boolean) {
        val w = wrapper.width
        val h = wrapper.height
        if (w == 0 || h == 0) return
        val rotate = (h >= w) != gamePortrait
        val rotation = if (rotate) 90f else 0f
        val width = if (rotate) h else ViewGroup.LayoutParams.MATCH_PARENT
        val height = if (rotate) w else ViewGroup.LayoutParams.MATCH_PARENT
        val lp = icView.layoutParams as? FrameLayout.LayoutParams
        if (icView.rotation == rotation && lp != null && lp.width == width && lp.height == height) return
        icView.rotation = rotation
        icView.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
    }

    private fun showElementEditorOnCover() {
        val parent = coverContainer ?: return
        val view = coverView ?: return
        val element = view.selectedElement ?: return
        detachCoverEditor()
        coverEditorHost = PresentationComposeHost.attach(parent) {
            PluviaTheme {
                ElementEditorDialog(
                    element = element,
                    view = view,
                    onDismiss = ::detachCoverEditor,
                    onSave = ::detachCoverEditor,
                )
            }
        }
    }

    private fun detachCoverEditor() {
        coverEditorHost?.detach()
        coverEditorHost = null
    }
}

fun currentAllowedOrientations(container: Container): EnumSet<Orientation> = when {
    container.isPortraitMode -> EnumSet.of(Orientation.PORTRAIT)
    !FoldableSession.deviceIsFoldable -> PrefManager.allowedOrientation
    FoldableSession.isUnfolded -> PrefManager.allowedOrientationUnfolded
    FoldableSession.isFolded -> PrefManager.allowedOrientationFolded
    else -> PrefManager.allowedOrientation
}

fun resolveOverlayProfile(container: Container): ControlsProfile? {
    val manager = PluviaApp.inputControlsManager ?: return null
    return ControlProfileService.selectedProfile(container, manager, FoldableSession.activeSlot)
        ?: manager.getProfiles(false).let { it.getOrNull(2) ?: it.firstOrNull() }
}

fun applyGameAreaLayout(container: Container) {
    val host = FoldableSession.gameHost ?: return
    val parent = host.parent as? FrameLayout ?: return
    if (container.isPortraitMode || parent.width == 0 || parent.height == 0) return

    val enabled = FoldableSession.deviceIsFoldable
    val trim = FoldableSession.gameArea
    val left = if (enabled) trim[0] else 0
    var right = if (enabled) trim[1] else 0
    val top = if (enabled) trim[2] else 0
    var bottom = if (enabled) trim[3] else 0
    if (left + right > GAME_AREA_MAX_TOTAL_INSET) right = GAME_AREA_MAX_TOTAL_INSET - left
    if (top + bottom > GAME_AREA_MAX_TOTAL_INSET) bottom = GAME_AREA_MAX_TOTAL_INSET - top

    val lp = host.layoutParams as? FrameLayout.LayoutParams ?: return
    val newLeft = parent.width * left / 100
    val newRight = parent.width * right / 100
    val newTop = parent.height * top / 100
    val newBottom = parent.height * bottom / 100
    if (lp.leftMargin == newLeft && lp.rightMargin == newRight && lp.topMargin == newTop && lp.bottomMargin == newBottom) return

    for (view in listOfNotNull(host, PluviaApp.touchpadView?.takeIf { it.parent === parent })) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: continue
        params.setMargins(newLeft, newTop, newRight, newBottom)
        view.layoutParams = params
    }
    Timber.i("Game area insets: L=%d R=%d T=%d B=%d", left, right, top, bottom)
}

@Composable
fun GameAreaDialog(onDismiss: () -> Unit) {
    val initial = remember { FoldableSession.gameArea }
    var left by remember { mutableIntStateOf(initial[0]) }
    var right by remember { mutableIntStateOf(initial[1]) }
    var top by remember { mutableIntStateOf(initial[2]) }
    var bottom by remember { mutableIntStateOf(initial[3]) }

    fun applyAndPersist() {
        FoldableSession.saveGameArea(intArrayOf(left, right, top, bottom))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.game_area_title)) },
        text = {
            Column {
                Text(text = stringResource(R.string.game_area_subtitle))
                InsetSlider(R.string.game_area_left, left, { left = it }, ::applyAndPersist)
                InsetSlider(R.string.game_area_right, right, { right = it }, ::applyAndPersist)
                InsetSlider(R.string.game_area_top, top, { top = it }, ::applyAndPersist)
                InsetSlider(R.string.game_area_bottom, bottom, { bottom = it }, ::applyAndPersist)
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
        Text(text = stringResource(labelRes), modifier = Modifier.width(56.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            onValueChangeFinished = onCommit,
            valueRange = 0f..ControlProfileService.GAME_AREA_MAX_INSET.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(text = "$value%", modifier = Modifier.width(44.dp).padding(start = 8.dp))
    }
}
