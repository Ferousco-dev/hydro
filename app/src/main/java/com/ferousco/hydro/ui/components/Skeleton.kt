package com.ferousco.hydro.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes
import com.ferousco.hydro.ui.theme.LocalReducedMotion

/** A gentle opacity pulse shared by every skeleton shape; static when animations are off. */
@Composable
private fun rememberSkeletonAlpha(): State<Float> =
    if (LocalReducedMotion.current) {
        mutableFloatStateOf(1f)
    } else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 1f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "skeleton-alpha"
        )
    }

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = HydroShapes.Skeleton
) {
    val alpha = rememberSkeletonAlpha()
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .alpha(alpha.value)
            .background(HydroColors.Skeleton, shape)
    )
}

@Composable
fun SkeletonCircle(size: Dp, modifier: Modifier = Modifier) {
    SkeletonBox(modifier = modifier.size(size), shape = CircleShape)
}

/** A text line placeholder sized like the real line (body = 14sp text on a 20sp line). */
@Composable
fun SkeletonText(
    widthFraction: Float,
    modifier: Modifier = Modifier,
    lineHeight: Dp = 14.dp
) {
    SkeletonBox(modifier = modifier.fillMaxWidth(widthFraction).height(lineHeight))
}

/**
 * Placeholder for the place sheet's peek + first rows. Heights mirror the real content so nothing
 * jumps when data arrives. The whole group is one loading announcement for screen readers.
 */
@Composable
fun SkeletonPlaceDetails(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Loading conditions" },
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
    ) {
        // Header: place name + subtitle
        Column(verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)) {
            SkeletonText(widthFraction = 0.55f, lineHeight = 22.dp)
            SkeletonText(widthFraction = 0.35f)
        }
        // Big temperature + feels-like
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
        ) {
            SkeletonBox(modifier = Modifier.size(width = 96.dp, height = 38.dp))
            SkeletonText(widthFraction = 0.4f)
        }
        SkeletonText(widthFraction = 0.8f)
    }
}

@Composable
fun SkeletonPlaceRows(count: Int = 3, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceLg)
    ) {
        repeat(count) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
            ) {
                SkeletonCircle(size = 36.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonText(widthFraction = 0.5f, lineHeight = 14.dp)
                    SkeletonText(widthFraction = 0.75f, lineHeight = 12.dp)
                }
            }
        }
    }
}
