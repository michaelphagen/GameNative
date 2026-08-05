package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.GyroInput
import java.util.Locale

// Gamepad buttons offered as gyro triggers. Because physical controllers and on-screen buttons
// set the same gamepad state, one selection covers either input source.
private val GYRO_TRIGGER_CANDIDATES = listOf(
    Binding.GAMEPAD_BUTTON_A, Binding.GAMEPAD_BUTTON_B, Binding.GAMEPAD_BUTTON_X, Binding.GAMEPAD_BUTTON_Y,
    Binding.GAMEPAD_BUTTON_L1, Binding.GAMEPAD_BUTTON_R1, Binding.GAMEPAD_BUTTON_L2, Binding.GAMEPAD_BUTTON_R2,
    Binding.GAMEPAD_BUTTON_L3, Binding.GAMEPAD_BUTTON_R3, Binding.GAMEPAD_BUTTON_SELECT, Binding.GAMEPAD_BUTTON_START,
    Binding.GAMEPAD_DPAD_UP, Binding.GAMEPAD_DPAD_DOWN, Binding.GAMEPAD_DPAD_LEFT, Binding.GAMEPAD_DPAD_RIGHT,
)

/**
 * Standalone Gyro Settings screen (profile-level), independent of the touch controller. Configures
 * gyro aiming: master enable, always-on vs triggered, which emulated stick it drives, sensitivity,
 * per-axis source mapping (yaw/pitch/roll) with invert, and the activation triggers.
 *
 * Triggers are gamepad buttons (fired by a physical controller OR an on-screen button mapped to
 * that button), plus — when touch controls exist — per-touchpad touch triggers. A dedicated on/off
 * button can also be made by binding any button to the GYRO action.
 *
 * Rendered inline (no platform Dialog) so it also works on the foldable cover presentation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GyroSettingsDialog(
    profile: ControlsProfile,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val axisOptions = remember { listOf(GyroInput.Axis.YAW, GyroInput.Axis.PITCH, GyroInput.Axis.ROLL) }
    val axisLabels = listOf(
        stringResource(R.string.gyro_axis_yaw),
        stringResource(R.string.gyro_axis_pitch),
        stringResource(R.string.gyro_axis_roll),
    )
    val stickLabels = listOf(
        stringResource(R.string.touchpad_stick_right),
        stringResource(R.string.touchpad_stick_left),
    )
    val triggerModeLabels = listOf(
        stringResource(R.string.gyro_trigger_momentary),
        stringResource(R.string.gyro_trigger_toggle),
    )

    var gyroEnabled by remember { mutableStateOf(profile.isGyroEnabled) }
    var alwaysOn by remember { mutableStateOf(profile.isGyroAlwaysOn) }
    var outputStickIndex by remember { mutableIntStateOf(if (profile.isGyroOutputLeftStick) 1 else 0) }
    var sensitivity by remember { mutableFloatStateOf(profile.gyroSensitivity) }
    var axisXIndex by remember { mutableIntStateOf(axisOptions.indexOf(profile.gyroAxisX).coerceAtLeast(0)) }
    var axisYIndex by remember { mutableIntStateOf(axisOptions.indexOf(profile.gyroAxisY).coerceAtLeast(0)) }
    var invertX by remember { mutableStateOf(profile.isGyroInvertX) }
    var invertY by remember { mutableStateOf(profile.isGyroInvertY) }

    // Button trigger state, parallel to GYRO_TRIGGER_CANDIDATES
    val btnEnabled: SnapshotStateList<Boolean> = remember {
        mutableStateListOf<Boolean>().apply {
            GYRO_TRIGGER_CANDIDATES.forEach { b -> add(profile.gyroTriggers.any { it.button == b }) }
        }
    }
    val btnToggle: SnapshotStateList<Boolean> = remember {
        mutableStateListOf<Boolean>().apply {
            GYRO_TRIGGER_CANDIDATES.forEach { b -> add(profile.gyroTriggers.firstOrNull { it.button == b }?.toggle ?: false) }
        }
    }
    val candidateLabels = remember { GYRO_TRIGGER_CANDIDATES.map { it.toString() } }

    // Touch-control triggers: any on-screen touchpad or stick can activate gyro by being touched.
    val touchElements = remember {
        profile.getElements().filter { it.canBeGyroTrigger() }
    }
    val padEnabled: SnapshotStateList<Boolean> = remember {
        mutableStateListOf<Boolean>().apply { touchElements.forEach { add(it.isGyroTrigger) } }
    }
    val padToggle: SnapshotStateList<Boolean> = remember {
        mutableStateListOf<Boolean>().apply { touchElements.forEach { add(it.isGyroTriggerToggle) } }
    }
    val leftStickLabel = stringResource(R.string.touchpad_stick_left)
    val rightStickLabel = stringResource(R.string.touchpad_stick_right)
    var padCount = 0
    var stickCount = 0
    val touchLabels = touchElements.map { element ->
        val stickSide = if (element.getBindingAt(0) == Binding.GAMEPAD_LEFT_THUMB_UP) leftStickLabel else rightStickLabel
        if (element.type == ControlElement.Type.TOUCHPAD) {
            padCount += 1
            stringResource(R.string.gyro_trigger_touchpad_label, padCount, stickSide)
        } else {
            stickCount += 1
            stringResource(R.string.gyro_trigger_stick_label, stickCount, stickSide)
        }
    }

    fun persist() {
        profile.isGyroEnabled = gyroEnabled
        profile.isGyroAlwaysOn = alwaysOn
        profile.isGyroOutputLeftStick = outputStickIndex == 1
        profile.gyroSensitivity = sensitivity
        profile.gyroAxisX = axisOptions[axisXIndex]
        profile.gyroAxisY = axisOptions[axisYIndex]
        profile.isGyroInvertX = invertX
        profile.isGyroInvertY = invertY
        profile.gyroTriggers.clear()
        GYRO_TRIGGER_CANDIDATES.forEachIndexed { i, button ->
            if (btnEnabled[i]) profile.gyroTriggers.add(ControlsProfile.GyroTrigger(button, btnToggle[i]))
        }
        touchElements.forEachIndexed { i, element ->
            element.isGyroTrigger = padEnabled[i]
            element.isGyroTriggerToggle = padToggle[i]
        }
        profile.save()
        // Apply immediately to the live gyro runtime
        app.gamenative.PluviaApp.gyroController?.syncRunning()
    }

    InlineDialogScrim(onDismissRequest = onDismiss) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.gyro_settings),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                    },
                    actions = {
                        IconButton(onClick = { persist(); onSave() }) { Icon(Icons.Default.Save, null) }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsGroup(title = { Text(stringResource(R.string.gyro_settings)) }) {
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gyro_enable)) },
                        subtitle = { Text(stringResource(R.string.gyro_enable_subtitle)) },
                        state = gyroEnabled,
                        onCheckedChange = { gyroEnabled = it },
                    )
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gyro_always_on)) },
                        subtitle = { Text(stringResource(R.string.gyro_always_on_subtitle)) },
                        state = alwaysOn,
                        onCheckedChange = { alwaysOn = it },
                    )
                    SettingsListDropdown(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.gyro_output_stick)) },
                        subtitle = { Text(stringResource(R.string.gyro_output_stick_subtitle)) },
                        value = outputStickIndex,
                        items = stickLabels,
                        onItemSelected = { outputStickIndex = it },
                    )
                    SettingsMenuLink(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.gyro_sensitivity)) },
                        subtitle = { Text(stringResource(R.string.gyro_sensitivity_subtitle)) },
                        onClick = {},
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Slider(
                            value = sensitivity,
                            onValueChange = { sensitivity = it },
                            valueRange = 0.1f..10.0f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = String.format(Locale.US, "%.1fx", sensitivity),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                SettingsGroup(title = { Text(stringResource(R.string.gyro_axis_mapping)) }) {
                    SettingsListDropdown(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.gyro_axis_x)) },
                        subtitle = { Text(stringResource(R.string.gyro_axis_x_subtitle)) },
                        value = axisXIndex,
                        items = axisLabels,
                        onItemSelected = { axisXIndex = it },
                    )
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gyro_invert_x)) },
                        state = invertX,
                        onCheckedChange = { invertX = it },
                    )
                    SettingsListDropdown(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.gyro_axis_y)) },
                        subtitle = { Text(stringResource(R.string.gyro_axis_y_subtitle)) },
                        value = axisYIndex,
                        items = axisLabels,
                        onItemSelected = { axisYIndex = it },
                    )
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gyro_invert_y)) },
                        state = invertY,
                        onCheckedChange = { invertY = it },
                    )
                }

                // Unified trigger list: on-screen sticks/touchpads (by touch) and gamepad buttons
                // (from a physical controller or an on-screen button). Always shown; when Always
                // On is enabled the runtime ignores these, and the note below says so.
                SettingsGroup(title = { Text(stringResource(R.string.gyro_triggers)) }) {
                    Text(
                        text = stringResource(
                            if (alwaysOn) R.string.gyro_triggers_ignored else R.string.gyro_triggers_help
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    // Touch controls (sticks and touchpads)
                    touchElements.forEachIndexed { i, _ ->
                        SettingsSwitch(
                            colors = settingsTileColorsAlt(),
                            title = { Text(touchLabels[i]) },
                            subtitle = { Text(stringResource(R.string.gyro_trigger_enable_subtitle)) },
                            state = padEnabled[i],
                            onCheckedChange = { padEnabled[i] = it },
                        )
                        if (padEnabled[i]) {
                            SettingsListDropdown(
                                colors = settingsTileColors(),
                                title = { Text(stringResource(R.string.gyro_trigger_mode)) },
                                subtitle = { Text(stringResource(R.string.gyro_trigger_mode_subtitle)) },
                                value = if (padToggle[i]) 1 else 0,
                                items = triggerModeLabels,
                                onItemSelected = { padToggle[i] = it == 1 },
                            )
                        }
                    }

                    // Gamepad buttons
                    GYRO_TRIGGER_CANDIDATES.forEachIndexed { i, _ ->
                        SettingsSwitch(
                            colors = settingsTileColorsAlt(),
                            title = { Text(candidateLabels[i]) },
                            state = btnEnabled[i],
                            onCheckedChange = { btnEnabled[i] = it },
                        )
                        if (btnEnabled[i]) {
                            SettingsListDropdown(
                                colors = settingsTileColors(),
                                title = { Text(stringResource(R.string.gyro_trigger_mode)) },
                                subtitle = { Text(stringResource(R.string.gyro_trigger_mode_subtitle)) },
                                value = if (btnToggle[i]) 1 else 0,
                                items = triggerModeLabels,
                                onItemSelected = { btnToggle[i] = it == 1 },
                            )
                        }
                    }

                    SettingsMenuLink(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.gyro_trigger_button_hint_title)) },
                        subtitle = { Text(stringResource(R.string.gyro_trigger_button_hint_subtitle)) },
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }
    }
}
