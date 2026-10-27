package com.ferousco.hydro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

/** A soft ice-blue group that holds one related setting and its explanation. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(HydroColors.IceBlue.copy(alpha = 0.6f), HydroShapes.Control)
            .padding(HydroDimens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
    ) { content() }
}

@Composable
fun SettingsCardTitle(title: String, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = HydroColors.TextPrimary)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = HydroColors.TextSecondary)
        }
    }
}

/** The master switch: an icon, what it controls, and what On or Off means right now. */
@Composable
fun MasterSwitchCard(
    icon: PhIcon,
    title: String,
    statusText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (checked) HydroColors.Blue else HydroColors.IceBlue.copy(alpha = 0.6f),
                HydroShapes.Control
            )
            .padding(HydroDimens.SpaceLg)
            .semantics { stateDescription = if (checked) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(if (checked) Color.White.copy(alpha = 0.2f) else HydroColors.FloatingSurface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            PhosphorIcon(
                icon = icon,
                contentDescription = null,
                selected = checked,
                tint = if (checked) Color.White else HydroColors.TextSecondary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (checked) Color.White else HydroColors.TextPrimary
            )
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (checked) Color.White.copy(alpha = 0.85f) else HydroColors.TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HydroColors.Blue,
                checkedTrackColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = HydroColors.IceBlueStrong,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/** A time with minus and plus buttons beneath, used twice for quiet hours. */
@Composable
fun TimeStepper(
    label: String,
    timeText: String,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(HydroColors.FloatingSurface, HydroShapes.Control)
            .padding(vertical = HydroDimens.SpaceMd),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = HydroColors.TextSecondary)
        Text(timeText, style = MaterialTheme.typography.titleMedium, color = HydroColors.TextPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)) {
            HydroIconButton(PhIcon.Minus, "$label earlier", onEarlier, tint = HydroColors.Blue)
            HydroIconButton(PhIcon.Plus, "$label later", onLater, tint = HydroColors.Blue)
        }
    }
}

/** A labelled on/off switch with a short explanation, used inside a [SettingsCard]. */
@Composable
fun ToggleRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (checked) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = HydroColors.TextPrimary)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = HydroColors.TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = HydroColors.Blue,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = HydroColors.IceBlueStrong,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}
