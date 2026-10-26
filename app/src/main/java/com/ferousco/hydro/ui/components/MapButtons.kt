package com.ferousco.hydro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

/**
 * Lightweight floating control for the map: a compact glass surface with one icon. The visible
 * square is [HydroDimens.ControlSize]; the interactive area is the full 48dp touch target.
 */
@Composable
fun MapFloatingButton(
    icon: PhIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    elevated: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container = when {
        selected -> HydroColors.IceBlue
        pressed -> HydroColors.IceBlue
        else -> HydroColors.FloatingSurface
    }
    Box(
        modifier = modifier
            .size(HydroDimens.TouchTarget)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(HydroDimens.ControlSize)
                .then(if (elevated) Modifier.shadow(3.dp, HydroShapes.Control, clip = false) else Modifier)
                .background(container, HydroShapes.Control),
            contentAlignment = Alignment.Center
        ) {
            PhosphorIcon(
                icon = icon,
                contentDescription = null,
                selected = selected,
                tint = if (selected) HydroColors.Blue else HydroColors.TextPrimary
            )
        }
    }
}

/** Plain icon button with no container, for use inside sheets and the search bar. */
@Composable
fun HydroIconButton(
    icon: PhIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = HydroColors.TextPrimary
) {
    Box(
        modifier = modifier
            .size(HydroDimens.TouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        PhosphorIcon(
            icon = icon,
            contentDescription = null,
            size = HydroDimens.IconCompact,
            tint = if (enabled) tint else HydroColors.TextSecondary.copy(alpha = 0.5f)
        )
    }
}

/** Several related floating buttons in one glass container, separated by hairlines. */
@Composable
fun MapControlGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .shadow(3.dp, HydroShapes.Control, clip = false)
            .background(HydroColors.FloatingSurface, HydroShapes.Control),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        content()
    }
}

/** A button inside a [MapControlGroup]: no own surface, the group provides it. */
@Composable
fun MapGroupButton(
    icon: PhIcon,
    contentDescription: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    showDivider: Boolean = true
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(HydroDimens.TouchTarget)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    this.contentDescription = contentDescription
                    this.selected = selected
                },
            contentAlignment = Alignment.Center
        ) {
            PhosphorIcon(
                icon = icon,
                contentDescription = null,
                selected = selected,
                tint = if (selected) HydroColors.Blue else HydroColors.TextPrimary
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.width(32.dp),
                color = HydroColors.Hairline
            )
        }
    }
}

/** Re-centres the map on the user; filled arrow while the map is following the device. */
@Composable
fun CurrentLocationButton(
    following: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MapFloatingButton(
        icon = PhIcon.Locate,
        contentDescription = if (following) "Following your location" else "Go to my location",
        onClick = onClick,
        modifier = modifier,
        selected = following
    )
}

/**
 * Full-width primary action. While [loading], the label is replaced by the activity indicator
 * without changing the button's size.
 */
@Composable
fun HydroPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && !loading
    val container = when {
        !enabled -> HydroColors.IceBlue
        pressed -> HydroColors.BluePressed
        else -> HydroColors.Blue
    }
    Box(
        modifier = modifier
            .height(HydroDimens.TouchTarget)
            .background(container, HydroShapes.Control)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = active,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { if (loading) stateDescription = "Loading" },
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            AppleActivityIndicator(size = IndicatorSize.Medium, color = Color.White)
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) Color.White else HydroColors.TextSecondary,
                modifier = Modifier.padding(horizontal = HydroDimens.SpaceLg)
            )
        }
    }
}

/** Understated secondary action: text on a transparent background. */
@Composable
fun HydroTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(HydroDimens.TouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = HydroDimens.SpaceMd),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = HydroColors.Blue)
    }
}
