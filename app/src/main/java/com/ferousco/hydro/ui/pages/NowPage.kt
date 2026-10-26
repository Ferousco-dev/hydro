package com.ferousco.hydro.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.HydroUiState
import com.ferousco.hydro.ui.TripMode
import com.ferousco.hydro.ui.TripUi
import com.ferousco.hydro.ui.components.ActionRow
import com.ferousco.hydro.ui.components.AppleActivityIndicator
import com.ferousco.hydro.ui.components.HydroIconButton
import com.ferousco.hydro.ui.components.HydroPrimaryButton
import com.ferousco.hydro.ui.components.HydroTextButton
import com.ferousco.hydro.ui.components.IndicatorSize
import com.ferousco.hydro.ui.components.MetricTile
import com.ferousco.hydro.ui.components.MetricTileRow
import com.ferousco.hydro.ui.components.PhIcon
import com.ferousco.hydro.ui.components.PlaceDetailsHeader
import com.ferousco.hydro.ui.components.SegmentedControl
import com.ferousco.hydro.ui.components.SheetSectionTitle
import com.ferousco.hydro.ui.components.SkeletonPlaceDetails
import com.ferousco.hydro.ui.components.SkeletonPlaceRows
import com.ferousco.hydro.ui.components.SkeletonText
import com.ferousco.hydro.ui.components.StatusPill
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes
import androidx.compose.foundation.background
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The home page of the panel: glanceable status first, detail after, prose last. */
@Composable
fun NowPage(
    state: HydroUiState,
    activityLabel: String,
    onRefresh: () -> Unit,
    onRouteHere: () -> Unit,
    onTripMode: (TripMode) -> Unit,
    onClearTrip: () -> Unit
) {
    val hasData = state.airTemperatureCelsius != null || state.heatIndexCelsius != null
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = HydroDimens.SpaceXl)) {
        if (!hasData && state.errorMessage == null) {
            SkeletonPlaceDetails()
            Column(modifier = Modifier.padding(top = HydroDimens.SpaceXl)) { SkeletonPlaceRows() }
            return@Column
        }

        PlaceDetailsHeader(
            title = state.locationLabel,
            subtitle = listOfNotNull(
                activityLabel,
                state.observationTime?.let { formatTime(it) }?.let { "Updated $it" },
                if (!state.isLiveAndFresh && state.deliveryOriginLabel != null) "Saved data" else null
            ).joinToString(" · ").ifBlank { null },
            trailing = {
                if (state.isLoading) {
                    Box(Modifier.size(HydroDimens.TouchTarget), contentAlignment = Alignment.Center) {
                        AppleActivityIndicator(size = IndicatorSize.Medium, color = HydroColors.TextSecondary)
                    }
                } else {
                    HydroIconButton(PhIcon.Refresh, "Refresh conditions", onRefresh)
                }
            }
        )

        state.errorMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = HydroColors.Danger,
                modifier = Modifier.padding(top = HydroDimens.SpaceMd)
            )
            HydroTextButton("Try again", onRefresh, modifier = Modifier.offset(x = -HydroDimens.SpaceMd))
        }

        if (hasData) {
            // Glance row: temperature on the left, risk on the right.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HydroDimens.SpaceSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${state.airTemperatureCelsius.formatOrDash(0)}°",
                        style = MaterialTheme.typography.displaySmall,
                        color = HydroColors.TextPrimary
                    )
                    state.feelsLikeCelsius?.let {
                        Text(
                            "Feels like ${it.formatOrDash(0)}°",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HydroColors.TextSecondary
                        )
                    }
                }
                state.recommendation?.let { rec ->
                    val (fg, bg) = urgencyColors(rec.urgency)
                    StatusPill(
                        text = rec.urgency.lowercase().replaceFirstChar { it.uppercase() } + " risk",
                        foreground = fg,
                        background = bg
                    )
                }
            }

            state.recommendation?.let { rec ->
                Text(
                    rec.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = HydroColors.TextPrimary,
                    modifier = Modifier.padding(top = HydroDimens.SpaceMd)
                )
                rec.bestWindow?.let {
                    Text(
                        "Coolest window · $it",
                        style = MaterialTheme.typography.labelLarge,
                        color = HydroColors.Blue,
                        modifier = Modifier.padding(top = HydroDimens.SpaceXs)
                    )
                }
            }

            if (state.isCustomLocation && state.trip == null) {
                HydroPrimaryButton(
                    text = "Route here from my location",
                    onClick = onRouteHere,
                    modifier = Modifier.fillMaxWidth().padding(top = HydroDimens.SpaceLg)
                )
            }

            state.trip?.let { TripCard(it, onTripMode, onClearTrip) }

            Column(
                modifier = Modifier.padding(top = HydroDimens.SpaceLg),
                verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)
            ) {
                MetricTileRow(
                    first = {
                        MetricTile(PhIcon.Thermometer, "Heat index", state.heatIndexCelsius?.let { "${it.formatOrDash(0)}°" } ?: "—", shortCategory(state.heatCategory), it)
                    },
                    second = {
                        MetricTile(PhIcon.Droplet, "Humidity", state.relativeHumidityPercent.formatOrDash(0).let { v -> if (v == "—") v else "$v%" }, null, it)
                    }
                )
                MetricTileRow(
                    first = {
                        MetricTile(PhIcon.Wind, "Wind", state.windSpeedMetersPerSecond.formatOrDash(1).let { v -> if (v == "—") v else "$v m/s" }, null, it)
                    },
                    second = {
                        MetricTile(PhIcon.Outdoor, "UV index", state.uvIndex.formatOrDash(1), state.uvCategory?.substringBefore(" ("), it)
                    }
                )
            }

            state.recommendation?.let { rec ->
                if (rec.actions.isNotEmpty()) {
                    SheetSectionTitle("What to do")
                    rec.actions.take(3).forEach { (title, detail) -> ActionRow(title, detail) }
                }
                if (!rec.isCurrentData) {
                    Text(
                        "This may be out of date. Refresh for the latest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HydroColors.Warning,
                        modifier = Modifier.padding(top = HydroDimens.SpaceSm)
                    )
                }
            }
            state.recommendationError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = HydroColors.Warning, modifier = Modifier.padding(top = HydroDimens.SpaceMd))
            }
            if (!state.isLiveAndFresh && state.deliveryOriginLabel != null) {
                Text(
                    "You're seeing saved data. Reconnect to update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HydroColors.Warning,
                    modifier = Modifier.padding(top = HydroDimens.SpaceMd)
                )
            }
        }

    }
}

@Composable
private fun TripCard(trip: TripUi, onMode: (TripMode) -> Unit, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = HydroDimens.SpaceLg)
            .background(HydroColors.IceBlue.copy(alpha = 0.6f), HydroShapes.Control)
            .padding(HydroDimens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trip from where you are", style = MaterialTheme.typography.labelLarge, color = HydroColors.TextPrimary, modifier = Modifier.weight(1f))
            HydroTextButton("Clear", onClear)
        }
        SegmentedControl(
            options = TripMode.entries.map { it to it.label },
            selected = trip.mode,
            onSelect = onMode
        )
        when {
            trip.isLoading -> Column(verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)) {
                SkeletonText(widthFraction = 0.5f, lineHeight = 22.dp)
                SkeletonText(widthFraction = 0.8f)
            }
            trip.error != null -> Text(trip.error, style = MaterialTheme.typography.bodyMedium, color = HydroColors.Danger)
            else -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
                ) {
                    Text(
                        listOfNotNull(
                            trip.distanceKm?.let { "%.1f km".format(Locale.getDefault(), it) },
                            trip.durationMinutes?.let { formatDuration(it) }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                        color = HydroColors.TextPrimary
                    )
                    trip.worstCategory?.let {
                        StatusPill(
                            "Worst: ${shortCategory(it)}",
                            foreground = HydroColors.TextPrimary,
                            background = HydroColors.FloatingSurface
                        )
                    }
                }
                trip.advisories.take(3).forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = HydroColors.TextSecondary)
                }
                if (trip.method?.contains("GREAT_CIRCLE") == true) {
                    Text(
                        "Estimated from straight-line distance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HydroColors.Warning
                    )
                }
            }
        }
    }
}

private fun formatTime(instant: java.time.Instant): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(instant)
