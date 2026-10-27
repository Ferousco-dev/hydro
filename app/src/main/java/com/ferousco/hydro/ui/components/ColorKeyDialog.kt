package com.ferousco.hydro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens

private val Levels = listOf(
    "Cool or mild" to "Comfortable. No special care.",
    "Warm" to "Can feel tiring. Drink water, take breaks.",
    "Hot" to "The heat can wear you down. Find shade, drink often.",
    "Very hot" to "Risky for long. Avoid heavy effort.",
    "Dangerously hot" to "Very dangerous. Stay somewhere cool."
)

/** What the colours on the heat chart mean, in plain words. */
@Composable
fun ColorKeyDialog(onDismiss: () -> Unit) {
    HydroDialog(
        onDismiss = onDismiss,
        icon = PhIcon.TabTrends,
        title = "Colour key",
        message = "Each bar is how hot it will feel at that hour. Taller means hotter.",
        primaryLabel = "Done",
        onPrimary = onDismiss
    ) {
        // The five levels at a glance, cool to hot.
        Row(
            modifier = Modifier.fillMaxWidth().height(8.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Levels.indices.forEach { rank ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(HydroColors.heat(rank), RoundedCornerShape(4.dp))
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Levels.forEachIndexed { rank, (name, meaning) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = HydroDimens.SpaceMd),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
                ) {
                    Box(Modifier.padding(top = 5.dp).size(10.dp).background(HydroColors.heat(rank), CircleShape))
                    Column {
                        Text(name, style = MaterialTheme.typography.labelLarge, color = HydroColors.TextPrimary)
                        Text(meaning, style = MaterialTheme.typography.bodySmall, color = HydroColors.TextSecondary)
                    }
                }
                if (rank < Levels.lastIndex) HorizontalDivider(color = HydroColors.Hairline)
            }
        }
    }
}
