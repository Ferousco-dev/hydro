package com.ferousco.hydro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.PlaceSuggestionUi
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

/** Search results shown under the search bar. Tapping one starts a trip to it. */
@Composable
fun PlaceSuggestionList(
    suggestions: List<PlaceSuggestionUi>,
    onPick: (PlaceSuggestionUi) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(3.dp, HydroShapes.Control, clip = false)
            .background(HydroColors.FloatingSurface, HydroShapes.Control)
    ) {
        suggestions.forEachIndexed { index, place ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPick(place) }
                    .padding(horizontal = HydroDimens.SpaceLg, vertical = HydroDimens.SpaceMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
            ) {
                PhosphorIcon(PhIcon.Pin, contentDescription = null, size = HydroDimens.IconCompact, tint = HydroColors.TextSecondary)
                Column {
                    Text(
                        place.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = HydroColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    place.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = HydroColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (index < suggestions.lastIndex) HorizontalDivider(color = HydroColors.Hairline, modifier = Modifier.padding(start = 52.dp))
        }
    }
}
