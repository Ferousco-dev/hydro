package com.ferousco.hydro.ui.pages

import com.ferousco.hydro.ui.theme.HydroColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ferousco.hydro.ui.i18n.LocalStrings
import java.util.Locale

internal fun Double?.formatOrDash(decimals: Int): String =
    this?.let { String.format(Locale.getDefault(), "%.${decimals}f", it) } ?: "—"

/** NWS names are long; "Below NWS Caution Threshold" reads better as "No heat risk". */
internal fun shortCategory(name: String?): String = when {
    name == null -> "—"
    name.startsWith("Below NWS Caution") -> "No heat risk"
    else -> name.removePrefix("NWS ")
}

/** The lowest feel the official heat category allows: a "Danger" day is never just "Warm". */
private fun categoryFloor(categoryName: String?): Int = when {
    categoryName == null || categoryName.startsWith("Below") -> 0
    categoryName.contains("Extreme Danger", ignoreCase = true) -> 7
    categoryName.contains("Danger", ignoreCase = true) -> 6
    categoryName.contains("Extreme Caution", ignoreCase = true) -> 5
    categoryName.contains("Caution", ignoreCase = true) -> 4
    else -> 0
}

private fun feelIndex(feelsC: Double?, categoryName: String?): Int {
    val byTemperature = when {
        feelsC == null -> 0
        feelsC < 0 -> 0
        feelsC < 10 -> 1
        feelsC < 18 -> 2
        feelsC < 24 -> 3
        feelsC < 30 -> 4
        feelsC < 36 -> 5
        feelsC < 41 -> 6
        else -> 7
    }
    return maxOf(byTemperature, categoryFloor(categoryName))
}

/**
 * How it feels, in everyday words: Freezing, Cold, Cool, Mild, Warm, Hot, Very hot, Dangerously hot.
 * Replaces "No heat risk" and "Extreme caution", which say nothing on a cold day.
 */
@Composable
internal fun feelWord(feelsC: Double?, categoryName: String?): String {
    if (feelsC == null && categoryName == null) return "—"
    return LocalStrings.current.feelWords[feelIndex(feelsC, categoryName)]
}

/** The engine says "No heat risk right now" even on a warm or cold day; say how it actually is. */
@Composable
internal fun friendlyHeadline(engineHeadline: String, feelsC: Double?, categoryName: String?): String {
    val strings = LocalStrings.current
    return if (engineHeadline == "No heat risk right now") {
        strings.rightNow.format(feelWord(feelsC, categoryName).lowercase())
    } else {
        strings.headlines[engineHeadline] ?: engineHeadline
    }
}

/** Text and background colours for [feelWord]: blue when it is cool, the heat colours when it is hot. */
internal fun feelColors(feelsC: Double?, categoryName: String?, urgency: String?): Pair<Color, Color> {
    val index = feelIndex(feelsC, categoryName)
    return when {
        index <= 3 -> HydroColors.temperature(feelsC) to HydroColors.IceBlue
        index <= 5 -> HydroColors.Warning to HydroColors.WarningContainer
        else -> HydroColors.Danger to HydroColors.DangerContainer
    }
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
