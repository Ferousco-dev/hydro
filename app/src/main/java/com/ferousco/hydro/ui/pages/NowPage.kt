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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ferousco.hydro.ui.HydroUiState
import com.ferousco.hydro.ui.components.ActionRow
import com.ferousco.hydro.ui.components.AppleActivityIndicator
import com.ferousco.hydro.ui.components.HydroIconButton
import com.ferousco.hydro.ui.components.HydroTextButton
import com.ferousco.hydro.ui.components.IndicatorSize
import com.ferousco.hydro.ui.components.MetricCell
import com.ferousco.hydro.ui.components.MetricGrid
import com.ferousco.hydro.ui.components.PhIcon
import com.ferousco.hydro.ui.components.PlaceDetailsHeader
import com.ferousco.hydro.ui.components.SheetSectionTitle
import com.ferousco.hydro.ui.components.SkeletonPlaceDetails
import com.ferousco.hydro.ui.components.SkeletonPlaceRows
import com.ferousco.hydro.ui.components.StatusPill
import com.ferousco.hydro.ui.i18n.LocalStrings
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import androidx.compose.foundation.background
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The home page of the panel: glanceable status first, detail after, prose last. */
@Composable
fun NowPage(
    state: HydroUiState,
    activityLabel: String,
    onRefresh: () -> Unit
) {
    val s = LocalStrings.current
    val hasData = state.airTemperatureCelsius != null || state.heatIndexCelsius != null
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = HydroDimens.SpaceXl)) {
        if (!hasData && state.errorMessage == null) {
            SkeletonPlaceDetails()
            Column(modifier = Modifier.padding(top = HydroDimens.SpaceXl)) { SkeletonPlaceRows() }
            return@Column
        }

        PlaceDetailsHeader(
            title = if (state.locationLabel == "Near you") s.nearYou else state.locationLabel,
            subtitle = listOfNotNull(
                activityLabel,
                state.observationTime?.let { formatTime(it) }?.let { "${s.updated} $it" }
            ).joinToString(" · ").ifBlank { null },
            trailing = {
                if (state.isLoading) {
                    Box(Modifier.size(HydroDimens.TouchTarget), contentAlignment = Alignment.Center) {
                        AppleActivityIndicator(size = IndicatorSize.Medium, color = HydroColors.TextSecondary)
                    }
                } else {
                    HydroIconButton(PhIcon.Refresh, s.refresh, onRefresh)
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
            HydroTextButton(s.tryAgain, onRefresh, modifier = Modifier.offset(x = -HydroDimens.SpaceMd))
        }

        // No signal: say plainly that these numbers are the last ones we saw.
        if (state.showingSaved || (hasData && !state.isLiveAndFresh && state.deliveryOriginLabel != null)) {
            (state.savedAt ?: state.observationTime)?.let { saved ->
                Text(
                    s.offlineBanner.format(formatTime(saved)),
                    style = MaterialTheme.typography.bodySmall,
                    color = HydroColors.Warning,
                    modifier = Modifier.padding(top = HydroDimens.SpaceMd)
                )
            }
        }

        if (hasData) {
            // Hero: the temperature is the anchor; risk and "feels like" sit beside it, subordinate.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HydroDimens.SpaceMd),
                verticalAlignment = Alignment.Bottom
            ) {
                val tempText = state.airTemperatureCelsius.formatOrDash(0)
                Text(
                    text = buildAnnotatedString {
                        append(tempText)
                        if (tempText != "—") withStyle(SpanStyle(fontSize = 44.sp, fontWeight = FontWeight.Normal)) { append("°") }
                    },
                    style = MaterialTheme.typography.displayLarge,
                    color = HydroColors.temperature(state.airTemperatureCelsius),
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = HydroDimens.SpaceSm)) {
                    state.recommendation?.let { rec ->
                        val (fg, bg) = feelColors(state.feelsLikeCelsius, state.heatCategory, rec.urgency)
                        StatusPill(
                            text = feelWord(state.feelsLikeCelsius, state.heatCategory),
                            foreground = fg,
                            background = bg
                        )
                    }
                    state.feelsLikeCelsius?.let {
                        Text(
                            "${s.feelsLike} ${it.formatOrDash(0)}°",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HydroColors.TextSecondary,
                            modifier = Modifier.padding(top = HydroDimens.SpaceSm)
                        )
                    }
                }
            }

            state.recommendation?.let { rec ->
                Text(
                    friendlyHeadline(rec.headline, state.feelsLikeCelsius, state.heatCategory),
                    style = MaterialTheme.typography.titleLarge,
                    color = HydroColors.TextPrimary,
                    modifier = Modifier.padding(top = HydroDimens.SpaceSm)
                )
                rec.bestWindow?.let {
                    Row(
                        modifier = Modifier.padding(top = HydroDimens.SpaceXs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(s.coolestWindow, style = MaterialTheme.typography.bodyMedium, color = HydroColors.TextSecondary)
                        Text(it, style = MaterialTheme.typography.labelLarge, color = HydroColors.Blue)
                    }
                }
            }

            state.air?.let { DustBanner(it, Modifier.padding(top = HydroDimens.SpaceLg)) }

            MetricGrid(
                modifier = Modifier.padding(top = HydroDimens.SpaceLg),
                cells = listOf(
                    { m ->
                        val rank = state.recommendation?.urgency?.let { urgencyColors(it).first } ?: HydroColors.TextSecondary
                        MetricCell(PhIcon.Thermometer, s.heatIndex, state.heatIndexCelsius?.let { "${it.formatOrDash(0)}°" } ?: "—", m, feelWord(state.heatIndexCelsius, state.heatCategory), rank)
                    },
                    { m -> MetricCell(PhIcon.Droplet, s.humidity, state.relativeHumidityPercent.formatOrDash(0).let { v -> if (v == "—") v else "$v%" }, m) },
                    { m -> MetricCell(PhIcon.Wind, s.wind, state.windSpeedMetersPerSecond.formatOrDash(1).let { v -> if (v == "—") v else "$v m/s" }, m) },
                    { m -> MetricCell(PhIcon.Outdoor, s.uvIndex, state.uvIndex.formatOrDash(1), m, state.uvCategory?.substringBefore(" (")) }
                )
            )

            state.air?.let { AirCard(it, Modifier.padding(top = HydroDimens.SpaceLg)) }

            state.recommendation?.let { rec ->
                if (rec.actions.isNotEmpty()) {
                    SheetSectionTitle(s.whatToDo)
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
        }

    }
}

private fun formatTime(instant: java.time.Instant): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(instant)
