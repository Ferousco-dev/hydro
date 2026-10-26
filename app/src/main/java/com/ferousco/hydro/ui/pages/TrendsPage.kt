package com.ferousco.hydro.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.HydroUiState
import com.ferousco.hydro.ui.TrendHour
import com.ferousco.hydro.ui.components.HourlyBarChart
import com.ferousco.hydro.ui.components.MetricTile
import com.ferousco.hydro.ui.components.MetricTileRow
import com.ferousco.hydro.ui.components.PhIcon
import com.ferousco.hydro.ui.components.SheetSectionTitle
import com.ferousco.hydro.ui.components.SkeletonBox
import com.ferousco.hydro.ui.components.SkeletonText
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens

/** Hourly heat for the next 24 hours, hottest/coolest hour, and the comparison with yesterday. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrendsPage(state: HydroUiState) {
    val trend = state.trend
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = HydroDimens.SpaceXl)) {
        Text("Next 24 hours", style = MaterialTheme.typography.titleLarge, color = HydroColors.TextPrimary)
        Text(
            "How hot it will feel in ${state.locationLabel}",
            style = MaterialTheme.typography.bodyMedium,
            color = HydroColors.TextSecondary
        )

        if (trend == null) {
            Column(
                modifier = Modifier.padding(top = HydroDimens.SpaceLg),
                verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
            ) {
                if (state.isLoading) {
                    SkeletonBox(Modifier.fillMaxWidth().height(140.dp))
                    SkeletonText(0.6f)
                } else {
                    Text(
                        "The hourly forecast isn't available right now. Open Now and tap refresh.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HydroColors.TextSecondary
                    )
                }
            }
            return@Column
        }

        val summary = "Heat index for the next ${trend.hours.size} hours. " +
            (trend.hottest?.let { "Hottest at ${it.label}, ${it.heatIndexC.formatOrDash(0)} degrees. " } ?: "") +
            (trend.coolest?.let { "Coolest at ${it.label}, ${it.heatIndexC.formatOrDash(0)} degrees." } ?: "")
        HourlyBarChart(
            hours = trend.hours,
            summary = summary,
            modifier = Modifier.padding(top = HydroDimens.SpaceLg)
        )

        MetricTileRow(
            modifier = Modifier.padding(top = HydroDimens.SpaceLg),
            first = { m -> trend.hottest?.let { HourTile("Hottest", it, m) } ?: Box(m) },
            second = { m -> trend.coolest?.let { HourTile("Coolest", it, m) } ?: Box(m) }
        )

        SheetSectionTitle("Versus yesterday")
        Text(trend.yesterday, style = MaterialTheme.typography.bodyLarge, color = HydroColors.TextPrimary)

        SheetSectionTitle("Colour key")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)
        ) {
            listOf("No heat risk", "Caution", "Extreme caution", "Danger", "Extreme danger").forEachIndexed { rank, label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).background(HydroColors.heat(rank), CircleShape))
                    Text(label, style = MaterialTheme.typography.bodySmall, color = HydroColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun HourTile(label: String, hour: TrendHour, modifier: Modifier) {
    MetricTile(
        icon = PhIcon.Thermometer,
        label = label,
        value = "${hour.label} · ${hour.heatIndexC.formatOrDash(0)}°",
        note = shortCategory(hour.categoryName),
        modifier = modifier
    )
}
