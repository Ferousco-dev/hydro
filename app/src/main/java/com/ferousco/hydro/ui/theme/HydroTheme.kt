package com.ferousco.hydro.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Hydro's design tokens: snow-white surfaces, blue-ice tints and one clean blue accent.
 * Screens and components read these tokens; they never hardcode colors, sizes or text styles.
 */
object HydroColors {
    val SnowWhite = Color(0xFFF7FBFF)
    /** Opaque on purpose: a translucent surface lets its own shadow show through as a square. */
    val FloatingSurface = Color(0xFFFFFFFF)
    val IceBlue = Color(0xFFE6F3FF)
    val IceBlueStrong = Color(0xFFBFE3FF)
    val Blue = Color(0xFF1E6FD9)
    val BluePressed = Color(0xFF1858AD)

    val TextPrimary = Color(0xFF0F1B2D)
    val TextSecondary = Color(0xFF55657A)
    val Hairline = Color(0xFFDDE8F3)
    val Skeleton = Color(0xFFE3EDF7)

    val Warning = Color(0xFFA85400)
    val WarningContainer = Color(0xFFFFF1DC)
    val Danger = Color(0xFFC62828)
    val DangerContainer = Color(0xFFFDE7E7)
    val Positive = Color(0xFF17704F)

    val Scrim = Color(0x1A0F1B2D)

    /** Heat-risk ramp, indexed by NWS category rank 0..4. Always paired with a text label. */
    val HeatRamp = listOf(
        Color(0xFF5BA3EC), // below caution
        Color(0xFFE5B800), // caution
        Color(0xFFEE8A1F), // extreme caution
        Color(0xFFD93B30), // danger
        Color(0xFF8E24AA)  // extreme danger
    )
    fun heat(rank: Int): Color = HeatRamp[rank.coerceIn(0, HeatRamp.lastIndex)]
}

object HydroDimens {
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 24.dp

    /** Visible size of a floating map control; the touch target is [TouchTarget]. */
    val ControlSize = 44.dp
    val TouchTarget = 48.dp
    val IconStandard = 24.dp
    val IconCompact = 20.dp
    val IconSmall = 16.dp

    val SearchBarHeight = 48.dp
    val ChipHeight = 36.dp
    val SheetPeekHeight = 232.dp
    val SheetHandleAreaHeight = 28.dp
    val SheetHandleWidth = 36.dp
    val SheetHandleHeight = 4.dp
    val ScreenEdge = 16.dp
}

object HydroShapes {
    val Control = RoundedCornerShape(12.dp)
    val Search = RoundedCornerShape(16.dp)
    val Chip = RoundedCornerShape(percent = 50)
    val Sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val Skeleton = RoundedCornerShape(6.dp)
    val Bubble = RoundedCornerShape(14.dp)
}

private val HydroColorScheme = lightColorScheme(
    primary = HydroColors.Blue,
    onPrimary = Color.White,
    primaryContainer = HydroColors.IceBlue,
    onPrimaryContainer = HydroColors.Blue,
    background = HydroColors.SnowWhite,
    onBackground = HydroColors.TextPrimary,
    surface = HydroColors.SnowWhite,
    onSurface = HydroColors.TextPrimary,
    onSurfaceVariant = HydroColors.TextSecondary,
    surfaceVariant = HydroColors.IceBlue,
    outline = HydroColors.Hairline,
    outlineVariant = HydroColors.Hairline,
    error = HydroColors.Danger,
    errorContainer = HydroColors.DangerContainer
)

// One typeface (the Android system font); sizes and weights follow the typography skill.
private val Family = FontFamily.Default

private val HydroTypography = Typography(
    displaySmall = TextStyle(fontFamily = Family, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontFamily = Family, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = Family, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Family, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = Family, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = Family, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = Family, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Family, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

private val HydroMaterialShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

/** True when the user has turned system animations off; animated components fall back to static. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun HydroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HydroColorScheme,
        typography = HydroTypography,
        shapes = HydroMaterialShapes,
        content = content
    )
}
