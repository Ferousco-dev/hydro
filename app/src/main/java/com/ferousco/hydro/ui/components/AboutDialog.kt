package com.ferousco.hydro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.ferousco.hydro.BuildConfig
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

/** What Hydro is, who made the data, and the "not medical advice" note. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    HydroDialog(
        onDismiss = onDismiss,
        icon = PhIcon.Info,
        title = "About Hydro",
        message = "Hydro helps you understand the heat, sun and air around you, and plan your day around them.",
        primaryLabel = "Done",
        onPrimary = onDismiss
    ) {
        CreditsBox()
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = HydroColors.TextSecondary
        )
    }
}

@Composable
private fun ColumnScope.CreditsBox() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HydroColors.IceBlue.copy(alpha = 0.6f), HydroShapes.Control)
            .padding(HydroDimens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)
    ) {
        Text(
            "Not medical advice",
            style = MaterialTheme.typography.labelLarge,
            color = HydroColors.TextPrimary
        )
        Text(
            "Hydro gives general guidance only. If you feel unwell, rest, cool down and get help.",
            style = MaterialTheme.typography.bodyMedium,
            color = HydroColors.TextSecondary
        )
        Text(
            "Data from",
            style = MaterialTheme.typography.labelLarge,
            color = HydroColors.TextPrimary,
            modifier = Modifier.padding(top = HydroDimens.SpaceXs)
        )
        Text(
            "Weather by Open-Meteo.com. Maps and places by Google.",
            style = MaterialTheme.typography.bodyMedium,
            color = HydroColors.TextSecondary
        )
    }
}
