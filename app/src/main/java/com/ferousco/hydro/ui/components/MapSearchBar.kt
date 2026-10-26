package com.ferousco.hydro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

/**
 * Floating search surface above the map. Shows the activity indicator while a search is running and
 * a clear button once there is text.
 */
@Composable
fun MapSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    isSearching: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Search a place"
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HydroDimens.SearchBarHeight)
            .shadow(3.dp, HydroShapes.Search, clip = false)
            .background(HydroColors.FloatingSurface, HydroShapes.Search),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(HydroDimens.TouchTarget), contentAlignment = Alignment.Center) {
            PhosphorIcon(
                icon = PhIcon.Search,
                contentDescription = null,
                size = HydroDimens.IconCompact,
                tint = HydroColors.TextSecondary
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = HydroColors.TextSecondary
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = HydroColors.TextPrimary),
                cursorBrush = SolidColor(HydroColors.Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search for a place" }
            )
        }
        when {
            isSearching -> Box(
                modifier = Modifier.size(HydroDimens.TouchTarget),
                contentAlignment = Alignment.Center
            ) {
                AppleActivityIndicator(
                    size = IndicatorSize.Medium,
                    color = HydroColors.TextSecondary,
                    contentDescription = "Searching"
                )
            }
            query.isNotEmpty() -> HydroIconButton(
                icon = PhIcon.Close,
                contentDescription = "Clear search",
                onClick = onClear,
                tint = HydroColors.TextSecondary
            )
            else -> Box(modifier = Modifier.padding(end = HydroDimens.SpaceSm))
        }
    }
}
