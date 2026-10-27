package com.ferousco.hydro.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

/**
 * Hydro's one popup. Every popup shares the same shape: an icon badge, a centred title and message,
 * the popup's own content, then a primary button with an optional quiet secondary one beneath it.
 * It scales and fades in, floats with a soft shadow, and closes when you tap outside it.
 */
@Composable
fun HydroDialog(
    onDismiss: () -> Unit,
    icon: PhIcon,
    title: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    primaryEnabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = onDismiss,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }

        // The whole window is ours, so we handle "tap outside to close" ourselves.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(HydroDimens.SpaceXl)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220))
            ) {
                Column(
                    modifier = modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .shadow(24.dp, HydroShapes.Sheet, clip = false)
                        .background(HydroColors.SnowWhite, HydroShapes.Sheet)
                        // Taps inside the card must not count as "outside".
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(horizontal = HydroDimens.SpaceXl, vertical = HydroDimens.SpaceXl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
                ) {
                    Box(
                        modifier = Modifier.size(56.dp).background(HydroColors.IceBlue, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        PhosphorIcon(icon, contentDescription = null, selected = true, size = 28.dp, tint = HydroColors.Blue)
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = HydroColors.TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    if (message != null) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HydroColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    content()
                    HydroPrimaryButton(
                        text = primaryLabel,
                        onClick = onPrimary,
                        enabled = primaryEnabled,
                        modifier = Modifier.fillMaxWidth().padding(top = HydroDimens.SpaceSm)
                    )
                    if (secondaryLabel != null) HydroTextButton(secondaryLabel, onSecondary)
                }
            }
        }
    }
}
