package app.gamenative.ui.component.dialog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A dialog-like overlay that renders INLINE in the current composition instead of opening a
 * platform Dialog/Popup window. This lets the same content render both on the inner screen and
 * on the foldable cover presentation, which cannot host platform sub-windows (they fail with a
 * window-type mismatch). Content is centered over a dimming scrim.
 */
@Composable
fun InlineDialogScrim(
    onDismissRequest: () -> Unit,
    dismissOnClickOutside: Boolean = false,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismissRequest)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (dismissOnClickOutside) onDismissRequest() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Wrapper Box swallows clicks so taps on the content don't fall through to the scrim
        Box(
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
