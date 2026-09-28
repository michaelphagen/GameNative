package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.screen.xserver.isFoldableSupportActive
import app.gamenative.ui.theme.PluviaTheme
import java.util.EnumSet

@Composable
fun OrientationDialog(
    openDialog: Boolean,
    onDismiss: () -> Unit,
) {
    if (!openDialog) {
        return
    }

    val context = LocalContext.current
    val isFoldable = remember { isFoldableSupportActive(context) }

    var currentSettings by remember {
        mutableStateOf(PrefManager.allowedOrientation.toList())
    }
    var foldedSettings by remember {
        mutableStateOf(PrefManager.allowedOrientationFolded.toList())
    }
    var unfoldedSettings by remember {
        mutableStateOf(PrefManager.allowedOrientationUnfolded.toList())
    }
    val isValid = if (isFoldable) {
        foldedSettings.isNotEmpty() && unfoldedSettings.isNotEmpty()
    } else {
        currentSettings.isNotEmpty()
    }

    // Save on close.
    val onClose: () -> Unit = {
        if (isFoldable) {
            PrefManager.allowedOrientationFolded = EnumSet.copyOf(foldedSettings)
            PrefManager.allowedOrientationUnfolded = EnumSet.copyOf(unfoldedSettings)
        } else {
            PrefManager.allowedOrientation = EnumSet.copyOf(currentSettings)
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = {
            // Block dismissal unless there is one valid setting checked.
            if (isValid) {
                onClose()
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Default.ScreenRotation,
                contentDescription = null,
            )
        },
        title = {
            Text(text = stringResource(R.string.allowed_orientations))
        },
        text = {
            Column {
                if (isFoldable) {
                    Text(text = stringResource(R.string.allowed_orientations_folded))
                    OrientationChecklist(foldedSettings) { foldedSettings = it }
                    Text(text = stringResource(R.string.allowed_orientations_unfolded))
                    OrientationChecklist(unfoldedSettings) { unfoldedSettings = it }
                } else {
                    OrientationChecklist(currentSettings) { currentSettings = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, enabled = isValid) {
                Text(text = stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun OrientationChecklist(
    settings: List<Orientation>,
    onChanged: (List<Orientation>) -> Unit,
) {
    arrayOf(Orientation.LANDSCAPE, Orientation.REVERSE_LANDSCAPE).forEach { orientation ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.contains(orientation),
                onCheckedChange = { enable ->
                    onChanged(if (enable) settings + orientation else settings - orientation)
                },
            )
            Text(text = orientation.name)
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_ProfileDialog() {
    val content = LocalContext.current
    PrefManager.init(content)
    PluviaTheme {
        OrientationDialog(
            openDialog = true,
            onDismiss = {},
        )
    }
}
