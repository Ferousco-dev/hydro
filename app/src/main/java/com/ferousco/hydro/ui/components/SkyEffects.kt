package com.ferousco.hydro.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.MoonPhase
import com.ferousco.hydro.ui.SkyKind
import com.ferousco.hydro.ui.SkyUi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * The sky effects are plain Canvas drawing so they work on every Android version. They are kept soft
 * on purpose: glows are low-alpha and the sun never goes pure white, so a hot day reads as hot
 * without being glaring.
 */

// Sun colour by heat rank: calm yellow, through amber and orange, to a deep red-orange.
private val SunCore = listOf(
    Color(0xFFFFD36B), Color(0xFFFFBE45), Color(0xFFFF9A3C), Color(0xFFF26A38), Color(0xFFD9432F)
)
private val MoonLit = Color(0xFFF5E6A3)
private val MoonRim = Color(0xFF5B6B88)
private val MoonDark = Color(0xFF3B4A66)

/** The sun or moon that sits in the top-right of the map. Its colour tells you how hot it is. */
@Composable
fun SkyBadge(sky: SkyUi, animated: Boolean, modifier: Modifier = Modifier, size: Dp = 84.dp) {
    val transition = rememberInfiniteTransition(label = "sky")
    val turn by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "turn"
    )
    val pulse by transition.animateFloat(
        0.96f, 1.06f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "pulse"
    )
    val description = if (sky.isDay) sky.label else "${sky.label}, ${sky.moon.name}"
    Canvas(modifier = modifier.size(size).semantics { contentDescription = description }) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        if (sky.isDay) {
            drawSun(c, r * 0.42f, sky.heatSeverity, if (animated) turn else 0f, if (animated && sky.heatSeverity >= 3) pulse else 1f)
        } else {
            drawMoon(c, r * 0.4f, sky.moon)
        }
        if (sky.kind != SkyKind.Clear) drawCloud(Offset(c.x + r * 0.1f, c.y + r * 0.28f), r * 0.9f, sky.kind, sky.isDay)
    }
}

private fun DrawScope.drawSun(c: Offset, r: Float, severity: Int, turn: Float, pulse: Float) {
    val core = SunCore[severity.coerceIn(0, 4)]
    // Soft glow, capped low so it never dazzles.
    val glowAlpha = 0.18f + 0.05f * severity
    drawCircle(
        Brush.radialGradient(listOf(core.copy(alpha = glowAlpha), Color.Transparent), c, r * 2.3f * pulse),
        radius = r * 2.3f * pulse, center = c
    )
    val rays = 12
    val rayWidth = r * 0.14f
    for (i in 0 until rays) {
        val a = (turn + i / rays.toFloat()) * 2 * PI.toFloat()
        val inner = r * 1.3f
        val outer = r * (if (i % 2 == 0) 1.85f else 1.6f) * (1 + (pulse - 1) * 0.5f)
        drawLine(
            core.copy(alpha = 0.85f),
            Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
            Offset(c.x + cos(a) * outer, c.y + sin(a) * outer),
            strokeWidth = rayWidth, cap = StrokeCap.Round
        )
    }
    drawCircle(core, radius = r, center = c)
    // A gentle inner highlight gives it body without a bright white hotspot.
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent), c - Offset(r * 0.25f, r * 0.25f), r),
        radius = r, center = c
    )
}

private fun DrawScope.drawMoon(c: Offset, r: Float, moon: MoonPhase, soft: Boolean = false) {
    drawCircle(
        Brush.radialGradient(listOf(MoonLit.copy(alpha = 0.35f), Color.Transparent), c, r * 2.2f),
        radius = r * 2.2f, center = c
    )
    drawCircle(MoonDark, radius = r, center = c)
    if (!soft) drawCircle(MoonRim, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.06f))
    val waxing = moon.fraction < 0.5
    val k = cos(2 * PI * moon.fraction).toFloat() // 1 new … -1 full
    val disc = Path().apply { addOval(Rect(c, r)) }
    val half = Path().apply { addRect(Rect(c.x, c.y - r, c.x + r, c.y + r)) } // right half
    val ellipse = Path().apply { addOval(Rect(c.x - abs(k) * r, c.y - r, c.x + abs(k) * r, c.y + r)) }
    val rightHalfDisc = Path.combine(PathOperation.Intersect, disc, half)
    val lit = if (k > 0) Path.combine(PathOperation.Difference, rightHalfDisc, ellipse)
    else Path.combine(PathOperation.Union, rightHalfDisc, Path.combine(PathOperation.Intersect, disc, ellipse))
    if (moon.illumination > 0.02) {
        // Waning moons are lit on the left, so mirror the shape.
        scale(if (waxing) 1f else -1f, 1f, pivot = c) { drawPath(lit, MoonLit) }
    }
}

private fun DrawScope.drawCloud(c: Offset, w: Float, kind: SkyKind, isDay: Boolean) {
    val tone = when (kind) {
        SkyKind.Rain, SkyKind.Storm -> Color(0xFF8FA3BD)
        SkyKind.Fog -> Color(0xFFC9D3DF)
        else -> if (isDay) Color(0xFFF4F8FC) else Color(0xFFB9C6D8)
    }
    val r = w * 0.2f
    drawCircle(tone, r, Offset(c.x - r * 1.1f, c.y))
    drawCircle(tone, r * 1.35f, Offset(c.x, c.y - r * 0.5f))
    drawCircle(tone, r * 0.95f, Offset(c.x + r * 1.5f, c.y + r * 0.1f))
    drawRoundRect(tone, Offset(c.x - r * 2.1f, c.y), Size(r * 4.6f, r * 0.95f), androidx.compose.ui.geometry.CornerRadius(r * 0.47f))
}

/** Falling rain or snow drawn over the map. It ignores touches so the map stays usable. */
@Composable
fun PrecipitationOverlay(snow: Boolean, modifier: Modifier = Modifier) {
    val drops = remember(snow) {
        val rnd = Random(if (snow) 7 else 3)
        List(if (snow) 70 else 150) { Triple(rnd.nextFloat(), rnd.nextFloat(), 0.6f + rnd.nextFloat() * 0.8f) }
    }
    val t by rememberInfiniteTransition(label = "fall").animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (snow) 6000 else 1100, easing = LinearEasing)), label = "t"
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        drops.forEach { (x0, y0, speed) ->
            val y = ((y0 + t * speed) % 1f) * size.height
            if (snow) {
                val x = x0 * size.width + sin((t + y0) * 2 * PI.toFloat()) * 12.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.75f), 2.2.dp.toPx() * speed, Offset(x, y))
            } else {
                val x = x0 * size.width + y * 0.12f
                val len = 22.dp.toPx() * speed
                drawLine(
                    Color(0xFF5B9BE0).copy(alpha = 0.55f), Offset(x, y), Offset(x - len * 0.12f, y + len),
                    strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round
                )
            }
        }
    }
}

/** Small static weather glyph for the hourly strip. */
@Composable
fun SkyGlyph(kind: SkyKind, isDay: Boolean, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Canvas(modifier = modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        val hasSun = kind == SkyKind.Clear || kind == SkyKind.PartlyCloudy
        if (hasSun) {
            if (isDay) drawCircle(SunCore[1], r * 0.5f, if (kind == SkyKind.Clear) c else c - Offset(r * 0.25f, r * 0.25f))
            else {
                val mc = if (kind == SkyKind.Clear) c else c - Offset(r * 0.25f, r * 0.25f)
                drawCircle(MoonLit, r * 0.48f, mc)
                drawCircle(MoonRim, r * 0.48f, mc, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
            }
        }
        if (kind != SkyKind.Clear) drawCloud(Offset(c.x, c.y + r * 0.2f), r * 1.9f, kind, isDay)
        if (kind == SkyKind.Rain || kind == SkyKind.Storm) {
            for (i in -1..1) drawLine(
                Color(0xFF3B82D6), Offset(c.x + i * r * 0.4f, c.y + r * 0.55f), Offset(c.x + i * r * 0.3f, c.y + r * 0.95f),
                strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round
            )
        }
        if (kind == SkyKind.Snow) {
            for (i in -1..1) drawCircle(Color(0xFF9BB8D9), 1.6.dp.toPx(), Offset(c.x + i * r * 0.4f, c.y + r * 0.8f))
        }
    }
}

/**
 * The big, see-through sky at the very top of the home screen. A huge sun (or moon) sits half off the
 * top-right corner so only its glow and rays wash over the screen, like light reflecting on glass.
 * It is drawn at low strength and never pure white, so it stays easy on the eyes. It ignores touches.
 */
@Composable
fun SkyAtmosphere(sky: SkyUi, animated: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "atmosphere")
    val turn by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Restart), label = "turn"
    )
    val pulse by transition.animateFloat(
        0.97f, 1.05f, infiniteRepeatable(tween(4200), RepeatMode.Reverse), label = "pulse"
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .size(width = androidx.compose.ui.unit.Dp.Unspecified, height = 460.dp)
            .blur(16.dp)
            .graphicsLayer { alpha = if (sky.isDay) 0.65f else 0.8f }
    ) {
        val r = 120.dp.toPx()
        val c = Offset(size.width * 0.82f, 8.dp.toPx())
        if (sky.isDay && sky.kind != SkyKind.Rain && sky.kind != SkyKind.Storm) {
            drawSun(c, r, sky.heatSeverity, if (animated) turn else 0f, if (animated && sky.heatSeverity >= 3) pulse else 1f)
        } else if (!sky.isDay) {
            drawMoon(c, r, sky.moon, soft = true)
        }
        if (sky.kind != SkyKind.Clear) {
            // Weather dims the light: a cool wash across the top, with a big soft cloud over the sun.
            drawRect(
                Brush.verticalGradient(
                    listOf(Color(0xFF7C93AE).copy(alpha = if (sky.isRaining) 0.5f else 0.28f), Color.Transparent),
                    startY = 0f, endY = size.height * 0.8f
                )
            )
            drawCloud(Offset(size.width * 0.7f, 70.dp.toPx()), size.width * 0.9f, sky.kind, sky.isDay)
        }
    }
}
