package com.ferousco.hydro.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ferousco.hydro.ui.components.AboutDialog
import com.ferousco.hydro.ui.components.HydroIconButton
import com.ferousco.hydro.core.integration.NotificationSeverity
import com.ferousco.hydro.ui.HydroUiState
import com.ferousco.hydro.ui.components.HydroPrimaryButton
import com.ferousco.hydro.ui.components.HydroTextButton
import com.ferousco.hydro.ui.components.MasterSwitchCard
import com.ferousco.hydro.ui.components.ToggleRow
import com.ferousco.hydro.ui.WearableLinkUi
import com.ferousco.hydro.ui.WearablesUi
import com.ferousco.hydro.ui.components.PhIcon
import com.ferousco.hydro.ui.components.SegmentedControl
import com.ferousco.hydro.ui.components.SettingsCard
import com.ferousco.hydro.ui.components.SettingsCardTitle
import com.ferousco.hydro.ui.components.TimeStepper
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Levels = listOf(
    NotificationSeverity.WATCH to "Early",
    NotificationSeverity.WARNING to "Important",
    NotificationSeverity.CRITICAL to "Urgent"
)

private fun levelExplanation(severity: NotificationSeverity): String = when (severity) {
    NotificationSeverity.WATCH -> "Get an early heads-up as heat or UV starts to build."
    NotificationSeverity.WARNING -> "Only when heat or UV needs care."
    NotificationSeverity.CRITICAL -> "Only when conditions are dangerous."
    NotificationSeverity.INFO -> "Everything, including general updates."
}

private fun hourLabel(hour: Int): String =
    LocalTime.of(hour, 0).format(DateTimeFormatter.ofPattern("h a", Locale.getDefault()))

/**
 * Alert settings as three clear groups: an on/off card, how urgent something must be, and quiet
 * hours. The groups beneath the switch only appear while alerts are on.
 */
@Composable
fun AlertsPage(
    state: HydroUiState,
    onEnabled: (Boolean) -> Unit,
    onSeverity: (NotificationSeverity) -> Unit,
    onQuietHour: (start: Boolean, delta: Int) -> Unit,
    onConnectHealth: () -> Unit,
    onDisconnectHealth: () -> Unit,
    onAssistantMayUseHealth: (Boolean) -> Unit,
    onAnimations: (Boolean) -> Unit
) {
    val prefs = state.alertPrefs
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = HydroDimens.SpaceXl),
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
    ) {
        var showAbout by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Alerts",
                style = MaterialTheme.typography.titleLarge,
                color = HydroColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            HydroIconButton(PhIcon.Info, "About Hydro", { showAbout = true })
        }
        if (showAbout) AboutDialog(onDismiss = { showAbout = false })

        MasterSwitchCard(
            icon = PhIcon.TabAlerts,
            title = "Heat and sun alerts",
            statusText = if (prefs.enabled) "On. We'll tell you when it matters." else "Off. You won't get notifications.",
            checked = prefs.enabled,
            onCheckedChange = onEnabled
        )

        if (prefs.enabled) {
            SettingsCard {
                SettingsCardTitle("How urgent", levelExplanation(prefs.minimumSeverity))
                SegmentedControl(
                    options = Levels,
                    selected = prefs.minimumSeverity,
                    onSelect = onSeverity
                )
            }

            SettingsCard {
                SettingsCardTitle(
                    "Quiet hours",
                    "No alerts from ${hourLabel(prefs.quietStartHour)} to ${hourLabel(prefs.quietEndHour)}, except urgent ones."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)) {
                    TimeStepper(
                        label = "From",
                        timeText = hourLabel(prefs.quietStartHour),
                        onEarlier = { onQuietHour(true, -1) },
                        onLater = { onQuietHour(true, 1) },
                        modifier = Modifier.weight(1f)
                    )
                    TimeStepper(
                        label = "Until",
                        timeText = hourLabel(prefs.quietEndHour),
                        onEarlier = { onQuietHour(false, -1) },
                        onLater = { onQuietHour(false, 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        SettingsCard {
            ToggleRow(
                title = "Weather animation",
                detail = null,
                checked = state.animationsOn,
                onCheckedChange = onAnimations
            )
        }

        HealthDataCard(state.wearables, onConnectHealth, onDisconnectHealth, onAssistantMayUseHealth)

        prefs.error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = HydroColors.Danger)
        }
    }
}

/** Connect, see and disconnect watch data. Everything here is read-only and stays on the phone by default. */
@Composable
private fun HealthDataCard(
    wearables: WearablesUi,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAssistantMayUse: (Boolean) -> Unit
) {
    SettingsCard {
        when (wearables.link) {
            WearableLinkUi.Checking -> SettingsCardTitle("Health data", "Checking…")
            WearableLinkUi.NotSupported -> SettingsCardTitle(
                "Health data",
                "Health Connect isn't available on this phone, so watch data can't be used."
            )
            WearableLinkUi.NeedsInstall -> {
                SettingsCardTitle("Health data", "Get Health Connect from Google Play to bring in data from your watch.")
                HydroPrimaryButton("Get Health Connect", onConnect, modifier = Modifier.fillMaxWidth())
            }
            WearableLinkUi.NeedsPermission -> {
                SettingsCardTitle(
                    "Health data",
                    "Let Hydro notice when your body is working hard in the heat. It only reads your heart rate and activity, on your phone, and never changes your data."
                )
                HydroPrimaryButton("Connect", onConnect, modifier = Modifier.fillMaxWidth())
            }
            WearableLinkUi.Connected -> {
                SettingsCardTitle("Health data", "Connected. Hydro reads this on your phone.")
                Text(
                    wearables.heartRateBpm?.let { bpm ->
                        "Heart rate $bpm" + (wearables.heartRateAgeMinutes?.let { " · ${if (it <= 1) "just now" else "$it min ago"}" } ?: "")
                    } ?: "No recent heart rate yet. Your watch syncs every so often.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = HydroColors.TextPrimary
                )
                wearables.stepsToday?.let {
                    Text("%,d steps today".format(it), style = MaterialTheme.typography.bodyLarge, color = HydroColors.TextPrimary)
                }
                ToggleRow(
                    title = "Let the assistant use it",
                    detail = "Off by default. When on, the assistant can read these numbers, and they are sent to Google's Gemini to write its answer.",
                    checked = wearables.assistantMayUse,
                    onCheckedChange = onAssistantMayUse
                )
                HydroTextButton("Disconnect", onDisconnect, modifier = Modifier.offset(x = -HydroDimens.SpaceMd))
            }
        }
    }
}
