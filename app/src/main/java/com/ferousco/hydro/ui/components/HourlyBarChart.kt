package com.ferousco.hydro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.TrendHour
import com.ferousco.hydro.ui.theme.HydroColors

/**
 * One bar per hour, coloured by NWS heat category, with a dashed line at the 26.7 C caution
 * threshold. The bar for "now" is full strength; the others are slightly lighter. A text summary
 * is attached for screen readers because the bars alone are not accessible.
 */
@Composable
fun HourlyBarChart(
    hours: List<TrendHour>,
    summary: String,
    modifier: Modifier = Modifier
) {
    if (hours.isEmpty()) return
    val lo = minOf(hours.minOf { it.heatIndexC }, CAUTION_C) - 2.0
    val hi = maxOf(hours.maxOf { it.heatIndexC }, CAUTION_C) + 2.0

    Column(modifier = modifier.fillMaxWidth().semantics { contentDescription = summary }) {
        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            val gap = 3.dp.toPx()
            val barWidth = (size.width - gap * (hours.size - 1)) / hours.size
            fun y(value: Double) = (size.height * (1f - ((value - lo) / (hi - lo)).toFloat()))
            hours.forEachIndexed { i, h ->
                val top = y(h.heatIndexC)
                drawRoundRect(
                    color = HydroColors.heat(h.severity).copy(alpha = if (h.isNow) 1f else 0.78f),
                    topLeft = Offset(i * (barWidth + gap), top),
                    size = Size(barWidth, size.height - top),
                    cornerRadius = CornerRadius(3.dp.toPx())
                )
            }
            val cautionY = y(CAUTION_C)
            drawLine(
                color = Color(0xFF0F1B2D).copy(alpha = 0.45f),
                start = Offset(0f, cautionY),
                end = Offset(size.width, cautionY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            val marks = hours.indices.filter { it % 6 == 0 }
            marks.forEachIndexed { index, i ->
                Text(
                    text = hours[i].label,
                    style = MaterialTheme.typography.bodySmall,
                    color = HydroColors.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                if (index == marks.lastIndex) Unit
            }
        }
        Text(
            text = "Dashed line: 27°C, where NWS heat caution starts",
            style = MaterialTheme.typography.bodySmall,
            color = HydroColors.TextSecondary
        )
    }
}

private const val CAUTION_C = 26.7
