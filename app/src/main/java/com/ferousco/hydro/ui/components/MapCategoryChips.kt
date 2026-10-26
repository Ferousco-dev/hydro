package com.ferousco.hydro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

data class MapCategory<T>(val value: T, val label: String, val icon: PhIcon)

/**
 * Horizontally scrolling single-select chips under the search bar. Selection is shown by fill and
 * weight (not colour alone) and exposed to accessibility services.
 */
@Composable
fun <T> MapCategoryChips(
    categories: List<MapCategory<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = contentPadding),
        horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)
    ) {
        categories.forEach { category ->
            val isSelected = category.value == selected
            Row(
                modifier = Modifier
                    .height(HydroDimens.ChipHeight)
                    .shadow(2.dp, HydroShapes.Chip, clip = false)
                    .background(
                        if (isSelected) HydroColors.Blue else HydroColors.FloatingSurface,
                        HydroShapes.Chip
                    )
                    .clickable(role = Role.Tab) { onSelect(category.value) }
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = HydroDimens.SpaceMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PhosphorIcon(
                    icon = category.icon,
                    contentDescription = null,
                    size = HydroDimens.IconSmall,
                    tint = if (isSelected) androidx.compose.ui.graphics.Color.White else HydroColors.TextPrimary
                )
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) androidx.compose.ui.graphics.Color.White else HydroColors.TextPrimary
                )
            }
        }
    }
}
