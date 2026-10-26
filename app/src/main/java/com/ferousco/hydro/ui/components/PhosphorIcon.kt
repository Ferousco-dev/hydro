package com.ferousco.hydro.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Icon
import com.ferousco.hydro.R
import com.ferousco.hydro.ui.theme.HydroDimens

/**
 * Phosphor Icons (MIT, https://phosphoricons.com) bundled as vector drawables, so they work offline
 * with no runtime dependency. Regular weight is the default; Fill is used for selected states.
 */
enum class PhIcon(@DrawableRes val regular: Int, @DrawableRes val fill: Int? = null) {
    Search(R.drawable.ph_magnifying_glass),
    Close(R.drawable.ph_x),
    Locate(R.drawable.ph_navigation_arrow, R.drawable.ph_navigation_arrow_fill),
    Layers(R.drawable.ph_stack, R.drawable.ph_stack_fill),
    Refresh(R.drawable.ph_arrow_clockwise),
    Send(R.drawable.ph_paper_plane_tilt),
    Sparkle(R.drawable.ph_sparkle, R.drawable.ph_sparkle_fill),
    Walk(R.drawable.ph_person_simple_walk),
    Run(R.drawable.ph_person_simple_run),
    Bike(R.drawable.ph_bicycle),
    Work(R.drawable.ph_briefcase),
    Commute(R.drawable.ph_car_simple),
    Outdoor(R.drawable.ph_sun),
    Warning(R.drawable.ph_warning),
    Droplet(R.drawable.ph_drop),
    Wind(R.drawable.ph_wind),
    Thermometer(R.drawable.ph_thermometer)
}

/**
 * Draws a [PhIcon]. Pass [contentDescription] for icons that carry meaning on their own; leave it
 * null for icons that sit next to a text label so screen readers do not read them twice.
 */
@Composable
fun PhosphorIcon(
    icon: PhIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Dp = HydroDimens.IconStandard,
    tint: Color = LocalContentColor.current
) {
    val resource = if (selected) icon.fill ?: icon.regular else icon.regular
    Icon(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        tint = tint
    )
}
