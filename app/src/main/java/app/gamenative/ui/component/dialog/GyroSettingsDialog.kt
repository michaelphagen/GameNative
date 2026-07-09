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
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.GyroInput
import com.winlator.widget.InputControlsView
import java.util.Locale

/**
 * Standalone Gyro Settings screen (profile-level). Configures gyro aiming independently of any
 * one touch control: master enable, which emulated stick it drives, sensitivity, per-axis source
 * mapping (yaw/pitch/roll) with invert, and the list of triggers that turn gyro on — each
 * touchpad can act as a momentary or toggle trigger. A dedicated on-screen button that toggles
 * gyro is created separately by binding a BUTTON element to the GYRO action.
 *
 * Rendered inline (no platform Dialog) so it also works on the foldable cover presentation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GyroSettingsDialog(
    view: InputControlsView,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val profile = view.profile

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

    var gyroEnabled by remember { mutableStateOf(profile?.isGyroEnabled ?: false) }
    var outputStickIndex by remember { mutableIntStateOf(if (profile?.isGyroOutputLeftStick == true) 1 else 0) }
    var sensitivity by remember { mutableFloatStateOf(profile?.gyroSensitivity ?: 3.0f) }
    var axisXIndex by remember { mutableIntStateOf(axisOptions.indexOf(profile?.gyroAxisX ?: GyroInput.Axis.YAW).coerceAtLeast(0)) }
    var axisYIndex by remember { mutableIntStateOf(axisOptions.indexOf(profile?.gyroAxisY ?: GyroInput.Axis.PITCH).coerceAtLeast(0)) }
    var invertX by remember { mutableStateOf(profile?.isGyroInvertX ?: false) }
    var invertY by remember { mutableStateOf(profile?.isGyroInvertY ?: false) }

    // Trigger list: one entry per touchpad element in the profile.
    val touchpads = remember {
        profile?.getElements()?.filter { it.type == ControlElement.Type.TOUCHPAD } ?: emptyList()
    }
    val triggerEnabled: SnapshotStateList<Boolean> = remember {
        mutableStateListOf<Boolean>().apply { touchpads.forEach { add(it.isTouchpadGyroTrigger) } }
    }
    val triggerToggle: SnapshotStateList<Boolean> = remember {
        mutableStateListOf<Boolean>().apply { touchpads.forEach { add(it.isTouchpadGyroTriggerToggle) } }
    }
    val leftStickLabel = stringResource(R.string.touchpad_stick_left)
    val rightStickLabel = stringResource(R.string.touchpad_stick_right)
    val touchpadLabels = touchpads.mapIndexed { index, element ->
        val stick = if (element.getBindingAt(0) == com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_UP) {
            leftStickLabel
        } else {
            rightStickLabel
        }
        stringResource(R.string.gyro_trigger_touchpad_label, index + 1, stick)
    }

    fun persist() {
        profile ?: return
        profile.isGyroEnabled = gyroEnabled
        profile.isGyroOutputLeftStick = outputStickIndex == 1
        profile.gyroSensitivity = sensitivity
        profile.gyroAxisX = axisOptions[axisXIndex]
        profile.gyroAxisY = axisOptions[axisYIndex]
        profile.isGyroInvertX = invertX
        profile.isGyroInvertY = invertY
        touchpads.forEachIndexed { i, element ->
            element.isTouchpadGyroTrigger = triggerEnabled[i]
            element.isTouchpadGyroTriggerToggle = triggerToggle[i]
        }
        profile.save()
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
                        IconButton(onClick = {
                            persist()
                            onSave()
                        }) { Icon(Icons.Default.Save, null) }
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
                val gyroAvailable = remember { view.gyroInput.isAvailable }
                if (!gyroAvailable) {
                    Text(
                        text = stringResource(R.string.gyro_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                SettingsGroup(title = { Text(stringResource(R.string.gyro_settings)) }) {
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gyro_enable)) },
                        subtitle = { Text(stringResource(R.string.gyro_enable_subtitle)) },
                        state = gyroEnabled,
                        onCheckedChange = { gyroEnabled = it },
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

                SettingsGroup(title = { Text(stringResource(R.string.gyro_triggers)) }) {
                    if (touchpads.isEmpty()) {
                        SettingsMenuLink(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.gyro_triggers_none)) },
                            subtitle = { Text(stringResource(R.string.gyro_triggers_none_subtitle)) },
                            enabled = false,
                            onClick = {},
                        )
                    } else {
                        touchpads.forEachIndexed { i, element ->
                            SettingsSwitch(
                                colors = settingsTileColorsAlt(),
                                title = { Text(touchpadLabels[i]) },
                                subtitle = { Text(stringResource(R.string.gyro_trigger_enable_subtitle)) },
                                state = triggerEnabled[i],
                                onCheckedChange = { triggerEnabled[i] = it },
                            )
                            if (triggerEnabled[i]) {
                                SettingsListDropdown(
                                    colors = settingsTileColors(),
                                    title = { Text(stringResource(R.string.gyro_trigger_mode)) },
                                    subtitle = { Text(stringResource(R.string.gyro_trigger_mode_subtitle)) },
                                    value = if (triggerToggle[i]) 1 else 0,
                                    items = triggerModeLabels,
                                    onItemSelected = { triggerToggle[i] = it == 1 },
                                )
                            }
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
