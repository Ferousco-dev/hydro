package com.ferousco.hydro.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ferousco.hydro.ui.AirUi
import com.ferousco.hydro.ui.components.StatusPill
import com.ferousco.hydro.ui.i18n.LocalStrings
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

/**
 * A heads-up when the air is dusty. It only appears when the readings say so, so it is not noise on
 * clear days; in the Harmattan season it uses that name, which is how people here know it.
 */
@Composable
fun DustBanner(air: AirUi, modifier: Modifier = Modifier) {
    if (air.dustLevel == "NONE") return
    val s = LocalStrings.current
    val heavy = air.dustLevel == "HEAVY"
    val (fg, bg) = if (heavy) HydroColors.Danger to HydroColors.DangerContainer else HydroColors.Warning to HydroColors.WarningContainer
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, HydroShapes.Control)
            .padding(HydroDimens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceXs)
    ) {
        Text(
            if (air.inHarmattanSeason) s.dustTitleSeason else s.dustTitleOther,
            style = MaterialTheme.typography.titleMedium,
            color = fg
        )
        Text(
            if (heavy) s.dustAdviceHeavy else s.dustAdviceLight,
            style = MaterialTheme.typography.bodyMedium,
            color = HydroColors.TextPrimary
        )
    }
}

/** Air quality, always labelled as a modelled estimate because there is no ground sensor behind it. */
@Composable
fun AirCard(air: AirUi, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(HydroColors.IceBlue.copy(alpha = 0.6f), HydroShapes.Control)
            .padding(HydroDimens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.airQuality,
                style = MaterialTheme.typography.titleMedium,
                color = HydroColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            StatusPill(s.modelledEstimate, HydroColors.Blue, HydroColors.FloatingSurface)
        }
        val category = air.categoryName?.let { s.airCategories[it] ?: it }
        Text(
            listOfNotNull(air.usAqi?.let { "AQI $it" }, category).joinToString(" · ").ifBlank { "—" },
            style = MaterialTheme.typography.titleLarge,
            color = HydroColors.TextPrimary
        )
        val detail = listOfNotNull(
            air.pm25?.let { "PM2.5 ${it.formatOrDash(0)}" },
            air.pm10?.let { "PM10 ${it.formatOrDash(0)}" },
            air.dust?.let { "Dust ${it.formatOrDash(0)}" }
        ).joinToString(" · ")
        if (detail.isNotBlank()) {
            Text("$detail µg/m³", style = MaterialTheme.typography.bodyMedium, color = HydroColors.TextSecondary)
        }
        Text(
            "${s.airFootnote} (${air.source})",
            style = MaterialTheme.typography.bodySmall,
            color = HydroColors.TextSecondary
        )
    }
}
