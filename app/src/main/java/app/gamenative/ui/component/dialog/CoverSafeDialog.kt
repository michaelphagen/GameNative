package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.utils.BrightnessManager

// The foldable cover screen presentation can't open dialog windows, so dialogs shown there are
// drawn inline over a scrim instead
@Composable
fun isHostedOnPresentation(): Boolean {
    val context = LocalContext.current
    return remember(context) { BrightnessManager.findActivity(context) == null }
}

@Composable
fun CoverSafeDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!isHostedOnPresentation()) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
            content = content,
        )
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun CoverSafeAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    if (!isHostedOnPresentation()) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
        )
        return
    }
    CoverSafeDialog(onDismissRequest) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(24.dp).widthIn(max = 340.dp)) {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                Spacer(Modifier.height(16.dp))
                text()
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    }
}
