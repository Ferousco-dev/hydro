package com.ferousco.hydro.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.ferousco.hydro.ui.components.HydroTextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ferousco.hydro.core.integration.NotificationSeverity
import com.ferousco.hydro.ui.HydroUiState
import com.ferousco.hydro.ui.components.InfoNote
import com.ferousco.hydro.ui.components.SegmentedControl
import com.ferousco.hydro.ui.components.SheetSectionTitle
import com.ferousco.hydro.ui.components.StepperRow
import com.ferousco.hydro.ui.components.SwitchRow
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens

/** Alert preferences. The background monitor and the assistant both read the same saved values. */
@Composable
fun AlertsPage(
    state: HydroUiState,
    onEnabled: (Boolean) -> Unit,
    onSeverity: (NotificationSeverity) -> Unit,
    onQuietHour: (start: Boolean, delta: Int) -> Unit
) {
    val prefs = state.alertPrefs
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = HydroDimens.SpaceXl)) {
        Text("Alerts", style = MaterialTheme.typography.titleLarge, color = HydroColors.TextPrimary)
        Text(
            "Hydro checks conditions in the background and only interrupts you when it matters.",
            style = MaterialTheme.typography.bodyMedium,
            color = HydroColors.TextSecondary
        )

        Column(modifier = Modifier.padding(top = HydroDimens.SpaceLg)) {
            SwitchRow(
                title = "Heat and UV alerts",
                detail = "Notifications for rising heat, strong UV and forecast changes",
                checked = prefs.enabled,
                onCheckedChange = onEnabled
            )
        }

        SheetSectionTitle("Minimum severity")
        SegmentedControl(
            options = listOf(
                NotificationSeverity.WATCH to "Watch",
                NotificationSeverity.WARNING to "Warning",
                NotificationSeverity.CRITICAL to "Critical"
            ),
            selected = prefs.minimumSeverity,
            onSelect = onSeverity
        )
        InfoNote("Alerts below this level are not sent. Critical alerts can still break through quiet hours.")

        SheetSectionTitle("Quiet hours")
        StepperRow("Starts", "%02d:00".format(prefs.quietStartHour), { onQuietHour(true, -1) }, { onQuietHour(true, 1) })
        StepperRow("Ends", "%02d:00".format(prefs.quietEndHour), { onQuietHour(false, -1) }, { onQuietHour(false, 1) })

        prefs.error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = HydroColors.Danger, modifier = Modifier.padding(top = HydroDimens.SpaceMd))
        }
        InfoNote("Hydro checks about every 30 minutes. Android may delay checks to save battery.")

        var showAbout by remember { mutableStateOf(false) }
        HydroTextButton(
            text = if (showAbout) "Hide about" else "About Hydro",
            onClick = { showAbout = !showAbout },
            modifier = Modifier.padding(top = HydroDimens.SpaceLg).offset(x = -HydroDimens.SpaceMd)
        )
        if (showAbout) {
            Text(
                "Hydro gives general guidance about heat, sun and air. It isn't medical advice.\n\n" +
                    "Weather data by Open-Meteo.com. Maps and place data by Google.",
                style = MaterialTheme.typography.bodySmall,
                color = HydroColors.TextSecondary
            )
        }
    }
}
