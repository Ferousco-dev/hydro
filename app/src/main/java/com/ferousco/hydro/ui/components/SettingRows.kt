package com.ferousco.hydro.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens

@Composable
fun SwitchRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Switch) { onCheckedChange(!checked) }
                .padding(vertical = HydroDimens.SpaceMd)
                .semantics { stateDescription = if (checked) "On" else "Off" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = HydroColors.TextPrimary)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = HydroColors.TextSecondary)
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = HydroColors.Blue,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = HydroColors.IceBlueStrong,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
        HorizontalDivider(color = HydroColors.Hairline)
    }
}

/** A labelled value with minus/plus controls, for hour pickers. */
@Composable
fun StepperRow(
    title: String,
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = HydroDimens.SpaceXs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = HydroColors.TextPrimary, modifier = Modifier.weight(1f))
            HydroIconButton(PhIcon.Minus, "Earlier", onDecrement, tint = HydroColors.Blue)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = HydroColors.TextPrimary)
            HydroIconButton(PhIcon.Plus, "Later", onIncrement, tint = HydroColors.Blue)
        }
        HorizontalDivider(color = HydroColors.Hairline)
    }
}

@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = HydroColors.TextSecondary,
        modifier = modifier.padding(top = HydroDimens.SpaceSm)
    )
}
