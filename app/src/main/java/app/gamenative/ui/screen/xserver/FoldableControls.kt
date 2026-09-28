package app.gamenative.ui.screen.xserver

import android.content.Context
import android.content.pm.PackageManager
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
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.inputcontrols.ControlProfileService
import app.gamenative.inputcontrols.ControlProfileSlot
import app.gamenative.ui.enums.Orientation
import app.gamenative.utils.BrightnessManager
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.ControlsProfile
import java.util.EnumSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private var scope: CoroutineScope? = null
    private var container: Container? = null

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
                    applyFoldLayout()
                    applyGameAreaLayout(container)
                }
        }
    }

    fun end() {
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
