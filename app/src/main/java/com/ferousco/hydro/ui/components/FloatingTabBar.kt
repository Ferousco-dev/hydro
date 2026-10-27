package com.ferousco.hydro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

data class TabItem<T>(val value: T, val label: String, val icon: PhIcon)

/**
 * Floating navigation: a capsule holding every tab but the last, and the last tab in a circle of its
 * own beside it, so the map stays the main thing on screen. The selected tab uses the filled icon, a
 * tinted shape and (in the capsule) heavier text, so selection never depends on colour alone.
 */
@Composable
fun <T> FloatingTabBar(
    items: List<TabItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val capsuleItems = items.dropLast(1)
    val circleItem = items.last()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(HydroDimens.TabBarHeight)
                .shadow(6.dp, HydroShapes.Capsule, clip = false)
                .background(HydroColors.FloatingSurface, HydroShapes.Capsule)
                .padding(HydroDimens.SpaceXs),
            horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceXs)
        ) {
            capsuleItems.forEach { item ->
                val isSelected = item.value == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(HydroDimens.TabBarHeight - HydroDimens.SpaceSm)
                        .background(
                            if (isSelected) HydroColors.IceBlue else Color.Transparent,
                            HydroShapes.Capsule
                        )
                        .clickable(role = Role.Tab) { onSelect(item.value) }
                        .semantics { this.selected = isSelected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    PhosphorIcon(
                        icon = item.icon,
                        contentDescription = null,
                        selected = isSelected,
                        size = HydroDimens.IconStandard,
                        tint = if (isSelected) HydroColors.Blue else HydroColors.TextSecondary
                    )
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) HydroColors.Blue else HydroColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        // The last tab floats on its own as a circle: icon only, tinted when it is the open page.
        val circleSelected = circleItem.value == selected
        Box(
            modifier = Modifier
                .size(HydroDimens.TabBarHeight)
                .shadow(6.dp, CircleShape, clip = false)
                .background(if (circleSelected) HydroColors.IceBlue else HydroColors.FloatingSurface, CircleShape)
                .clickable(role = Role.Tab) { onSelect(circleItem.value) }
                .semantics {
                    this.selected = circleSelected
                    contentDescription = circleItem.label
                },
            contentAlignment = Alignment.Center
        ) {
            PhosphorIcon(
                icon = circleItem.icon,
                contentDescription = null,
                selected = circleSelected,
                size = HydroDimens.IconStandard,
                tint = if (circleSelected) HydroColors.Blue else HydroColors.TextSecondary
            )
        }
    }
}
