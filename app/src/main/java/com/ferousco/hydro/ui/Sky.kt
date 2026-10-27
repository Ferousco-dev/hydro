package com.ferousco.hydro.ui

import kotlin.math.PI
import kotlin.math.cos

enum class SkyKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Storm }

/** The moon's phase: [fraction] runs 0 (new) to 1 (back to new); [illumination] is how much is lit. */
data class MoonPhase(val name: String, val fraction: Double, val illumination: Double)

/** What the sky looks like right now: drives the sun, moon and rain on the map. */
data class SkyUi(
    val kind: SkyKind,
    val isDay: Boolean,
    /** Heat category rank, 0 (no risk) to 4 (extreme danger). Sets how hot the sun looks. */
    val heatSeverity: Int,
    val moon: MoonPhase
) {
    val isRaining get() = kind == SkyKind.Rain || kind == SkyKind.Storm

    val label: String
        get() = when (kind) {
            SkyKind.Clear -> if (isDay) "Sunny" else "Clear night"
            SkyKind.PartlyCloudy -> "Partly cloudy"
            SkyKind.Cloudy -> "Cloudy"
            SkyKind.Fog -> "Foggy"
            SkyKind.Rain -> "Raining"
            SkyKind.Snow -> "Snowing"
            SkyKind.Storm -> "Thunderstorm"
        }
}

/** Maps a WMO weather code (what the forecast gives us) to a sky. Unknown means clear. */
fun skyKindOf(code: Int?): SkyKind = when (code) {
    null, 0, 1 -> SkyKind.Clear
    2 -> SkyKind.PartlyCloudy
    3 -> SkyKind.Cloudy
    45, 48 -> SkyKind.Fog
    in 51..67, in 80..82 -> SkyKind.Rain
    in 71..77, 85, 86 -> SkyKind.Snow
    in 95..99 -> SkyKind.Storm
    else -> SkyKind.Cloudy
}

private const val SYNODIC_DAYS = 29.530588853
// A known new moon: 2000-01-06 18:14 UTC.
private const val NEW_MOON_EPOCH_MS = 947182440000L

/** Moon phase worked out from the date alone, so it needs no network. */
fun moonPhaseAt(epochMs: Long): MoonPhase {
    val days = (epochMs - NEW_MOON_EPOCH_MS) / 86_400_000.0
    val fraction = ((days % SYNODIC_DAYS) + SYNODIC_DAYS) % SYNODIC_DAYS / SYNODIC_DAYS
    val illumination = (1 - cos(2 * PI * fraction)) / 2
    val name = when {
        fraction < 0.03 || fraction >= 0.97 -> "New moon"
        fraction < 0.22 -> "Waxing crescent"
        fraction < 0.28 -> "First quarter"
        fraction < 0.47 -> "Waxing gibbous"
        fraction < 0.53 -> "Full moon"
        fraction < 0.72 -> "Waning gibbous"
        fraction < 0.78 -> "Last quarter"
        else -> "Waning crescent"
    }
    return MoonPhase(name, fraction, illumination)
}
