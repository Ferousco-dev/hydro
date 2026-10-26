package com.ferousco.hydro.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.LocalReducedMotion
import kotlin.math.cos
import kotlin.math.sin

/** Standard indicator sizes. Segment length and stroke scale with the diameter. */
enum class IndicatorSize(val diameter: Dp, val segmentLength: Dp, val stroke: Dp) {
    Small(16.dp, 3.dp, 1.5.dp),
    Medium(24.dp, 5.dp, 2.dp),
    Large(32.dp, 7.dp, 2.5.dp)
}

/**
 * Circular activity indicator drawn as short rounded radial segments whose opacity fades
 * sequentially around the circle. No track, no ring.
 *
 * The infinite transition only exists while [isAnimating] is true and the composable is in the
 * composition, so it costs nothing when hidden. With system animations disabled it draws a static
 * frame instead of animating.
 */
@Composable
fun AppleActivityIndicator(
    modifier: Modifier = Modifier,
    size: IndicatorSize = IndicatorSize.Medium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    isAnimating: Boolean = true,
    contentDescription: String = "Loading"
) {
    val animate = isAnimating && !LocalReducedMotion.current
    val phase: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "activity-indicator").animateFloat(
            initialValue = 0f,
            targetValue = SEGMENTS.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = CYCLE_MILLIS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "activity-indicator-phase"
        )
    } else {
        mutableFloatStateOf(0f)
    }

    Canvas(
        modifier = modifier
            .size(size.diameter)
            .semantics { this.contentDescription = contentDescription }
    ) {
        val strokePx = size.stroke.toPx()
        val lengthPx = size.segmentLength.toPx()
        val outer = this.size.minDimension / 2f - strokePx / 2f
        val inner = outer - lengthPx
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val head = phase.value

        for (i in 0 until SEGMENTS) {
            // Distance behind the leading segment, in segments, wrapped to [0, SEGMENTS).
            val behind = ((head - i) % SEGMENTS + SEGMENTS) % SEGMENTS
            val alpha = MIN_ALPHA + (1f - MIN_ALPHA) * (1f - behind / SEGMENTS)
            val angle = (i.toDouble() / SEGMENTS) * 2.0 * Math.PI - Math.PI / 2.0
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            drawLine(
                color = color.copy(alpha = color.alpha * alpha),
                start = Offset(center.x + dx * inner, center.y + dy * inner),
                end = Offset(center.x + dx * outer, center.y + dy * outer),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
        }
    }
}

private const val SEGMENTS = 12
private const val CYCLE_MILLIS = 900
private const val MIN_ALPHA = 0.18f
