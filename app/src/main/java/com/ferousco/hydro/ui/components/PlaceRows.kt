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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

/** Place name, a one-line subtitle, and an optional trailing action (e.g. refresh). */
@Composable
fun PlaceDetailsHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = HydroColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HydroColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = HydroDimens.SpaceSm)) { trailing() }
        }
    }
}

/** Section heading inside the sheet. */
@Composable
fun SheetSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = HydroColors.TextPrimary,
        modifier = modifier.padding(top = HydroDimens.SpaceLg, bottom = HydroDimens.SpaceXs)
    )
}

/** Label on the left, value on the right, hairline below. */
@Composable
fun MetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: PhIcon? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = HydroDimens.SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
        ) {
            if (icon != null) {
                PhosphorIcon(icon, contentDescription = null, size = HydroDimens.IconCompact, tint = HydroColors.TextSecondary)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = HydroColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = HydroColors.TextSecondary
            )
        }
        HorizontalDivider(color = HydroColors.Hairline)
    }
}

/** A recommended action: bold-ish title with a supporting line. */
@Composable
fun ActionRow(
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = HydroDimens.SpaceMd)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = HydroColors.TextPrimary)
            Text(detail, modifier = Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = HydroColors.TextSecondary)
        }
        HorizontalDivider(color = HydroColors.Hairline)
    }
}

/** Compact status pill: the label carries the meaning; colour only reinforces it. */
@Composable
fun StatusPill(
    text: String,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(background, HydroShapes.Chip)
            .padding(horizontal = HydroDimens.SpaceMd, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(6.dp).background(foreground, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}
