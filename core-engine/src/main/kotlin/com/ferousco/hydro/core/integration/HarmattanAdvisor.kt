package com.ferousco.hydro.core.integration

import com.ferousco.hydro.core.model.GeoLocation

/** How much desert dust is in the air, in three steps people can act on. */
enum class DustLevel { NONE, DUSTY, HEAVY }

/**
 * A dust call for one place and moment.
 *
 * [inHarmattanSeason] is true from November to March across West Africa, when the dry Harmattan wind
 * carries Saharan dust south. A warning does not depend on the season: dust is judged from the
 * readings, and the season only decides whether it is called "Harmattan".
 */
data class DustAdvice(val level: DustLevel, val inHarmattanSeason: Boolean)

/**
 * Turns an air-quality reading into a dust warning.
 *
 * The thresholds are deliberately simple and use the model's own dust and PM10 figures (micrograms
 * per cubic metre): dust of 50 or PM10 of 75 is noticeable and worth a warning for sensitive people,
 * and dust of 100 or PM10 of 150 is heavy for everyone. They are practical cut-offs for a heads-up,
 * not a clinical standard, and the readings are modelled estimates, so the warning says "dust is
 * likely" rather than "dust is measured".
 */
object HarmattanAdvisor {
    private const val DUSTY_DUST = 50.0
    private const val DUSTY_PM10 = 75.0
    private const val HEAVY_DUST = 100.0
    private const val HEAVY_PM10 = 150.0
    private const val VERY_UNHEALTHY_AQI = 200.0

    fun assess(reading: AirQualityReading, location: GeoLocation, month: Int): DustAdvice {
        val dust = reading.pollutantsMicrogramsPerM3["dust"] ?: 0.0
        val pm10 = reading.pollutantsMicrogramsPerM3["pm10"] ?: 0.0
        val level = when {
            dust >= HEAVY_DUST || pm10 >= HEAVY_PM10 -> DustLevel.HEAVY
            // A very unhealthy air index with real dust behind it is heavy for everyone, whatever the cut-offs say.
            (reading.usAqi ?: 0.0) >= VERY_UNHEALTHY_AQI && (dust >= DUSTY_DUST || pm10 >= DUSTY_PM10) -> DustLevel.HEAVY
            dust >= DUSTY_DUST || pm10 >= DUSTY_PM10 -> DustLevel.DUSTY
            else -> DustLevel.NONE
        }
        return DustAdvice(level, inSeason(location, month))
    }

    /** November to March, over West Africa's dust belt (roughly Guinea to Chad, coast to Sahel). */
    fun inSeason(location: GeoLocation, month: Int): Boolean =
        month in setOf(11, 12, 1, 2, 3) &&
            location.latitude in 4.0..18.0 &&
            location.longitude in -18.0..16.0
}
