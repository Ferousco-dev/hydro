package com.ferousco.hydro.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.AssistantLabels
import com.ferousco.hydro.ui.ChatMessage
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes
import com.ferousco.hydro.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.min

/**
 * One chat message. Your questions sit in an ice-blue bubble on the right. Hydro's replies are
 * plain text on the left, typed out once when they arrive, with a short "Used weather and air
 * quality" caption underneath. A failed reply offers "Try again".
 */
@Composable
fun ChatMessageRow(
    message: ChatMessage,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onRevealed: (Long) -> Unit,
    onGrow: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.fromUser) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = HydroColors.TextPrimary,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(HydroColors.IceBlue, HydroShapes.Bubble)
                    .padding(horizontal = HydroDimens.SpaceMd, vertical = 10.dp)
            )
        }
        return
    }

    var finished by remember(message.id) { mutableStateOf(!message.animate) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceXs)) {
        RevealText(
            text = message.text,
            animate = message.animate,
            color = if (message.isError) HydroColors.TextSecondary else HydroColors.TextPrimary,
            onFinished = {
                finished = true
                onRevealed(message.id)
            },
            onGrow = onGrow
        )
        if (finished && message.sources.isNotEmpty()) {
            Text(
                text = "Used " + AssistantLabels.joinSources(message.sources),
                style = MaterialTheme.typography.bodySmall,
                color = HydroColors.TextSecondary
            )
        }
        if (message.isError && canRetry) {
            HydroTextButton("Try again", onRetry, modifier = Modifier.offset(x = -HydroDimens.SpaceMd))
        }
    }
}

/**
 * Types [text] out over about two to three seconds. This is a reveal effect, not live streaming:
 * the full answer has already arrived. It is skipped when system animations are off.
 */
@Composable
private fun RevealText(
    text: String,
    animate: Boolean,
    color: androidx.compose.ui.graphics.Color,
    onFinished: () -> Unit,
    onGrow: () -> Unit
) {
    val reducedMotion = LocalReducedMotion.current
    var shown by remember(text, animate) { mutableIntStateOf(if (animate) 0 else text.length) }
    LaunchedEffect(text, animate) {
        if (!animate) return@LaunchedEffect
        if (reducedMotion) {
            shown = text.length
            onFinished()
            return@LaunchedEffect
        }
        val step = ceil(text.length / REVEAL_FRAMES).toInt().coerceAtLeast(1)
        var sinceGrow = 0
        while (shown < text.length) {
            delay(FRAME_MILLIS)
            shown = min(text.length, shown + step)
            sinceGrow += step
            if (sinceGrow >= GROW_EVERY_CHARS) {
                sinceGrow = 0
                onGrow()
            }
        }
        onGrow()
        onFinished()
    }
    Text(
        text = text.take(shown),
        style = MaterialTheme.typography.bodyLarge,
        color = color
    )
}

private const val REVEAL_FRAMES = 160.0
private const val FRAME_MILLIS = 16L
private const val GROW_EVERY_CHARS = 40

/**
 * The "thinking" line: three typing dots next to what is happening right now. The text
 * changes as the assistant moves between steps ("Checking the weather", "Checking air quality").
 */
@Composable
fun ThinkingRow(status: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = status
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
    ) {
        TypingDots()
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
            },
            label = "thinking-status"
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = HydroColors.TextSecondary
            )
        }
    }
}
