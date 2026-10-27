package com.ferousco.hydro.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.LocalReducedMotion

private val GlowColors = listOf(
    Color(0xFF3D8BFF), // blue
    Color(0xFF4FD1FF), // cyan
    Color(0xFFA78BFA), // violet
    Color(0xFFFF7EB6), // pink
    Color(0xFF3D8BFF)  // back to blue so the loop is seamless
)

private fun glowColorAt(position: Float): Color {
    val p = ((position % 1f) + 1f) % 1f * (GlowColors.size - 1)
    val index = p.toInt().coerceIn(0, GlowColors.size - 2)
    return lerp(GlowColors[index], GlowColors[index + 1], p - index)
}

/**
 * A soft, slowly turning ring of colour around a rounded shape, shown while the assistant is
 * working (the "Siri glow"). It fades in and out, and holds still when system animations are off.
 * Drawn mostly outside the shape so it never covers content.
 */
fun Modifier.aiGlow(active: Boolean, cornerRadius: Dp): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val visibility by animateFloatAsState(if (active) 1f else 0f, tween(450), label = "ai-glow-visibility")
    val phase: State<Float> = if (active && !reduced) {
        rememberInfiniteTransition(label = "ai-glow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
            label = "ai-glow-phase"
        )
    } else {
        mutableFloatStateOf(0f)
    }
    drawWithContent {
        val on = visibility > 0.01f
        val brush = if (on) {
            Brush.sweepGradient(
                colorStops = Array(GLOW_STOPS + 1) { i -> (i / GLOW_STOPS.toFloat()) to glowColorAt(i / GLOW_STOPS.toFloat() + phase.value) },
                center = Offset(size.width / 2f, size.height / 2f)
            )
        } else {
            null
        }
        val radius = CornerRadius(cornerRadius.toPx())
        if (brush != null) {
            // Wide, faint rings give the glow; they sit behind the panel, so only the outside shows.
            drawRoundRect(brush, cornerRadius = radius, style = Stroke(22.dp.toPx()), alpha = 0.10f * visibility)
            drawRoundRect(brush, cornerRadius = radius, style = Stroke(12.dp.toPx()), alpha = 0.20f * visibility)
        }
        drawContent()
        if (brush != null) {
            // A crisp line on the edge itself.
            drawRoundRect(brush, cornerRadius = radius, style = Stroke(2.5.dp.toPx()), alpha = 0.95f * visibility)
        }
    }
}

private const val GLOW_STOPS = 12
