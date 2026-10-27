package com.ferousco.hydro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.TrendHour
import com.ferousco.hydro.ui.theme.HydroColors
import kotlin.math.roundToInt

/**
 * One bar per hour, coloured by heat category, with a scale down the left side and faint dotted
 * guide lines. Touch or drag across it to read any hour: the bar lights up and the readout above
 * shows its time, feel and level. A text summary is attached for screen readers.
 */
@Composable
fun HourlyBarChart(
    hours: List<TrendHour>,
    summary: String,
    modifier: Modifier = Modifier
) {
    if (hours.isEmpty()) return
    val dataMax = hours.maxOf { it.heatIndexC }
    val dataMin = hours.minOf { it.heatIndexC }
    val showCaution = CAUTION_C - dataMax <= CAUTION_VISIBLE_WITHIN_C
    val lo = dataMin - 2.0
    val hi = (if (showCaution) maxOf(dataMax, CAUTION_C) else dataMax) + 2.0
    val ticks = listOf(hi, (hi + lo) / 2, lo)
    var selected by remember(hours) { mutableStateOf<Int?>(null) }
    val shown = selected?.let { hours.getOrNull(it) } ?: hours.firstOrNull { it.isNow } ?: hours.first()

    Column(modifier = modifier.fillMaxWidth().semantics { contentDescription = summary }) {
        // Readout for the touched (or current) hour.
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                "${shown.label}  ·  feels ${shown.heatIndexC.roundToInt()}°",
                style = MaterialTheme.typography.titleMedium,
                color = HydroColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (selected == null) "Now" else com.ferousco.hydro.ui.pages.feelWord(shown.heatIndexC, shown.categoryName),
                style = MaterialTheme.typography.bodyMedium,
                color = HydroColors.heat(shown.severity)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().height(150.dp)) {
            // Left scale.
            Column(
                modifier = Modifier.width(34.dp).height(150.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                ticks.forEach {
                    Text(
                        "${it.roundToInt()}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = HydroColors.TextSecondary
                    )
                }
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(150.dp)
                    .pointerInput(hours) {
                        fun indexAt(x: Float) = (x / size.width * hours.size).toInt().coerceIn(0, hours.size - 1)
                        detectTapGestures(onTap = { selected = if (selected == indexAt(it.x)) null else indexAt(it.x) })
                    }
                    .pointerInput(hours) {
                        fun indexAt(x: Float) = (x / size.width * hours.size).toInt().coerceIn(0, hours.size - 1)
                        detectDragGestures(
                            onDragStart = { selected = indexAt(it.x) },
                            onDrag = { change, _ -> change.consume(); selected = indexAt(change.position.x) }
                        )
                    }
            ) {
                val gap = 3.dp.toPx()
                val barWidth = (size.width - gap * (hours.size - 1)) / hours.size
                fun y(value: Double) = (size.height * (1f - ((value - lo) / (hi - lo)).toFloat()))
                // Dotted guides at the scale values.
                ticks.forEach {
                    drawLine(
                        Color(0xFF0F1B2D).copy(alpha = 0.12f), Offset(0f, y(it)), Offset(size.width, y(it)),
                        strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 9f))
                    )
                }
                hours.forEachIndexed { i, h ->
                    val top = y(h.heatIndexC)
                    val dim = selected != null && selected != i
                    drawRoundRect(
                        color = HydroColors.heat(h.severity).copy(alpha = if (dim) 0.35f else if (h.isNow || selected == i) 1f else 0.78f),
                        topLeft = Offset(i * (barWidth + gap), top),
                        size = Size(barWidth, size.height - top),
                        cornerRadius = CornerRadius(3.dp.toPx())
                    )
                }
                if (showCaution) drawLine(
                    color = Color(0xFF0F1B2D).copy(alpha = 0.45f),
                    start = Offset(0f, y(CAUTION_C)), end = Offset(size.width, y(CAUTION_C)),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 34.dp)) {
            hours.indices.filter { it % 6 == 0 }.forEach { i ->
                Text(
                    text = hours[i].label,
                    style = MaterialTheme.typography.bodySmall,
                    color = HydroColors.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (showCaution) {
            Text(
                text = "Above the dashed line, heat starts to need care",
                style = MaterialTheme.typography.bodySmall,
                color = HydroColors.TextSecondary,
                modifier = Modifier.padding(start = 34.dp)
            )
        }
    }
}

private const val CAUTION_C = 26.7
private const val CAUTION_VISIBLE_WITHIN_C = 6.0
