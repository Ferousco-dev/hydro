package com.ferousco.hydro.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.sin

/**
 * Three dots that rise and fade in turn, the classic "someone is typing" indicator. Used for the
 * assistant. With system animations off it shows three still dots.
 */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    color: Color = HydroColors.TextSecondary
) {
    val reduced = LocalReducedMotion.current
    val phase: State<Float> = if (reduced) {
        mutableFloatStateOf(0f)
    } else {
        rememberInfiniteTransition(label = "typing-dots").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing), RepeatMode.Restart),
            label = "typing-dots-phase"
        )
    }
    Canvas(modifier = modifier.size(DpSize(30.dp, 14.dp))) {
        val radius = 3.dp.toPx()
        val gap = (size.width - radius * 6) / 2f
        for (i in 0 until 3) {
            // Each dot is a third of a cycle behind the previous one.
            val local = ((phase.value - i / 3f) % 1f + 1f) % 1f
            val wave = if (reduced) 0.5f else (sin(local * 2 * PI).toFloat().coerceAtLeast(0f))
            drawCircle(
                color = color.copy(alpha = color.alpha * (0.3f + 0.7f * wave)),
                radius = radius,
                center = Offset(radius + i * (radius * 2 + gap), size.height / 2f - wave * 3.dp.toPx())
            )
        }
    }
}
