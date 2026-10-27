package com.ferousco.hydro.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import com.ferousco.hydro.ui.components.SkyBadge
import com.ferousco.hydro.ui.components.SkyGlyph
import com.ferousco.hydro.ui.skyKindOf
import com.ferousco.hydro.ui.theme.HydroShapes
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.ferousco.hydro.ui.components.HydroIconButton
import com.ferousco.hydro.ui.components.ColorKeyDialog
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.HydroUiState
import com.ferousco.hydro.ui.TrendHour
import com.ferousco.hydro.ui.components.HourlyBarChart
import com.ferousco.hydro.ui.components.MetricTile
import com.ferousco.hydro.ui.components.MetricTileRow
import com.ferousco.hydro.ui.components.PhIcon
import com.ferousco.hydro.ui.components.SkeletonBox
import com.ferousco.hydro.ui.components.SkeletonText
import com.ferousco.hydro.ui.i18n.LocalStrings
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens

/** Hourly heat for the next 24 hours, with the hottest and coolest hour. */
@Composable
fun TrendsPage(state: HydroUiState) {
    val s = LocalStrings.current
    val trend = state.trend
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = HydroDimens.SpaceXl)) {
        var showKey by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.next24Hours,
                style = MaterialTheme.typography.titleLarge,
                color = HydroColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            HydroIconButton(PhIcon.Info, "Colour key", { showKey = true })
        }
        if (showKey) ColorKeyDialog(onDismiss = { showKey = false })
        Text(
            if (state.locationLabel == "Near you") s.howHotNear else s.howHotIn.format(state.locationLabel),
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
        WeatherNowCard(state, trend.hours, Modifier.padding(top = HydroDimens.SpaceLg))

        Text(
            s.heatThroughDay,
            style = MaterialTheme.typography.titleMedium,
            color = HydroColors.TextPrimary,
            modifier = Modifier.padding(top = HydroDimens.SpaceXl)
        )
        HourlyBarChart(
            hours = trend.hours,
            summary = summary,
            modifier = Modifier.padding(top = HydroDimens.SpaceMd)
        )

        MetricTileRow(
            modifier = Modifier.padding(top = HydroDimens.SpaceLg),
            first = { m -> trend.hottest?.let { HourTile(s.hottest, it, m) } ?: Box(m) },
            second = { m -> trend.coolest?.let { HourTile(s.coolest, it, m) } ?: Box(m) }
        )
    }
}

@Composable
private fun HourTile(label: String, hour: TrendHour, modifier: Modifier) {
    MetricTile(
        icon = PhIcon.Thermometer,
        label = label,
        value = "${hour.label} · ${hour.heatIndexC.formatOrDash(0)}°",
        note = feelWord(hour.heatIndexC, hour.categoryName),
        modifier = modifier
    )
}

/** Live conditions like a weather app: big temperature, the sky, a few numbers and an hour-by-hour strip. */
@Composable
private fun WeatherNowCard(state: HydroUiState, hours: List<TrendHour>, modifier: Modifier) {
    val sky = state.sky
    Column(
        modifier = modifier.fillMaxWidth()
            .background(HydroColors.IceBlue.copy(alpha = 0.6f), HydroShapes.Sheet)
            .padding(HydroDimens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.airTemperatureCelsius?.let { "${it.roundToInt()}°" } ?: "–",
                    style = MaterialTheme.typography.displaySmall,
                    color = HydroColors.TextPrimary
                )
                Text(sky?.label ?: LocalStrings.current.now, style = MaterialTheme.typography.titleMedium, color = HydroColors.TextPrimary)
                val hi = hours.mapNotNull { it.tempC }.maxOrNull()
                val lo = hours.mapNotNull { it.tempC }.minOrNull()
                Text(
                    buildString {
                        state.feelsLikeCelsius?.let { append("${LocalStrings.current.feelsLike} ${it.roundToInt()}°") }
                        if (hi != null && lo != null) append("  ·  H ${hi.roundToInt()}°  L ${lo.roundToInt()}°")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = HydroColors.TextSecondary
                )
            }
            if (sky != null) SkyBadge(sky, animated = state.animationsOn, size = 76.dp)
        }
        if (sky != null && !sky.isDay) {
            Text(
                "${sky.moon.name} · ${(sky.moon.illumination * 100).roundToInt()}% lit",
                style = MaterialTheme.typography.bodyMedium,
                color = HydroColors.TextSecondary
            )
        }
        MetricTileRow(
            first = { m -> MetricTile(PhIcon.Droplet, LocalStrings.current.humidity, state.relativeHumidityPercent?.let { "${it.roundToInt()}%" } ?: "–", null, m) },
            second = { m -> MetricTile(PhIcon.Wind, LocalStrings.current.wind, state.windSpeedMetersPerSecond?.let { "${(it * 3.6).roundToInt()} km/h" } ?: "–", null, m) }
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceLg)
        ) {
            hours.forEach { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (h.isNow) LocalStrings.current.now else h.label, style = MaterialTheme.typography.bodySmall, color = HydroColors.TextSecondary)
                    SkyGlyph(skyKindOf(h.weatherCode), h.isDay ?: true)
                    Text(h.tempC?.let { "${it.roundToInt()}°" } ?: "–", style = MaterialTheme.typography.labelLarge, color = HydroColors.TextPrimary)
                }
            }
        }
    }
}
