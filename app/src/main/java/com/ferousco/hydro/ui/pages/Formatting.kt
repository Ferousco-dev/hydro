package com.ferousco.hydro.ui.pages

import com.ferousco.hydro.ui.theme.HydroColors
import androidx.compose.ui.graphics.Color
import java.util.Locale

internal fun Double?.formatOrDash(decimals: Int): String =
    this?.let { String.format(Locale.getDefault(), "%.${decimals}f", it) } ?: "—"

/** NWS names are long; "Below NWS Caution Threshold" reads better as "No heat risk". */
internal fun shortCategory(name: String?): String = when {
    name == null -> "—"
    name.startsWith("Below NWS Caution") -> "No heat risk"
    else -> name.removePrefix("NWS ")
}

/** Foreground and container colours for a recommendation urgency; the label always carries the meaning. */
internal fun urgencyColors(urgency: String): Pair<Color, Color> = when (urgency) {
    "CRITICAL", "HIGH" -> HydroColors.Danger to HydroColors.DangerContainer
    "MODERATE" -> HydroColors.Warning to HydroColors.WarningContainer
    "LOW" -> HydroColors.Positive to HydroColors.IceBlue
    else -> HydroColors.TextSecondary to HydroColors.IceBlue
}

internal fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes < 24 * 60 -> if (minutes % 60 == 0) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"
    else -> "${minutes / (24 * 60)} d ${minutes % (24 * 60) / 60} h"
}

/** One decimal for short trips, whole kilometres (with separators) for long ones. */
internal fun formatDistanceKm(km: Double): String =
    if (km >= 100) String.format(Locale.getDefault(), "%,.0f km", km) else String.format(Locale.getDefault(), "%.1f km", km)
