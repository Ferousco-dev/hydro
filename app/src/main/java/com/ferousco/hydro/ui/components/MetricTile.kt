package com.ferousco.hydro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

/** One reading: icon, value, label, optional note. Two of them sit side by side in [MetricTileRow]. */
@Composable
fun MetricTile(
    icon: PhIcon,
    label: String,
    value: String,
    note: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(HydroColors.IceBlue.copy(alpha = 0.6f), HydroShapes.Control)
            .padding(HydroDimens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PhosphorIcon(icon, contentDescription = null, size = HydroDimens.IconSmall, tint = HydroColors.TextSecondary)
            Text(label, style = MaterialTheme.typography.bodySmall, color = HydroColors.TextSecondary)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = HydroColors.TextPrimary)
        Text(
            text = note ?: " ",
            style = MaterialTheme.typography.bodySmall,
            color = HydroColors.TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
fun MetricTileRow(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)
    ) {
        first(Modifier.weight(1f))
        second(Modifier.weight(1f))
    }
}

/**
 * The four readings in one quiet surface instead of four separate cards: two columns, a hairline
 * between rows, big values, small labels. [MetricCell] is one reading.
 */
@Composable
fun MetricGrid(
    cells: List<@Composable (Modifier) -> Unit>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(HydroColors.IceBlue.copy(alpha = 0.5f), HydroShapes.Control)
    ) {
        cells.chunked(2).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) androidx.compose.material3.HorizontalDivider(color = HydroColors.Hairline)
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { i, cell ->
                    if (i > 0) androidx.compose.foundation.layout.Box(
                        Modifier.width(1.dp).height(IntrinsicSize.Max).background(HydroColors.Hairline)
                    )
                    cell(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun MetricCell(
    icon: PhIcon,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    noteColor: androidx.compose.ui.graphics.Color = HydroColors.TextSecondary
) {
    Column(
        modifier = modifier.padding(horizontal = HydroDimens.SpaceLg, vertical = HydroDimens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PhosphorIcon(icon, contentDescription = null, size = HydroDimens.IconSmall, tint = HydroColors.TextSecondary)
            Text(label, style = MaterialTheme.typography.bodySmall, color = HydroColors.TextSecondary)
        }
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = HydroColors.TextPrimary
        )
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = noteColor, maxLines = 1)
    }
}
