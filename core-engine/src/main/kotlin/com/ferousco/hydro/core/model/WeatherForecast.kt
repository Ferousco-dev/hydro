package com.ferousco.hydro.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A single predicted hourly forecast point.
 * Preserves [validAt] (UTC instant), DST-aware [localDateTime], [issuedOrRetrievedAt], and [ValueNature.FORECAST].
 */
data class HourlyForecastPoint(
    val validAt: Instant,
    val localDateTime: ZonedDateTime,
    val issuedOrRetrievedAt: Instant,
    val location: GeoLocation,
    val variables: EnvironmentalSnapshotVariables,
    val source: EnvironmentalDataSource
) {
    init {
        val allNatures = listOfNotNull(
            variables.airTemperature.quantityOrNull()?.nature,
            variables.relativeHumidity.quantityOrNull()?.nature,
            variables.windSpeed.quantityOrNull()?.nature,
            variables.feelsLikeTemperature.quantityOrNull()?.nature,
            variables.uvIndex.quantityOrNull()?.nature,
            variables.solarRadiation.quantityOrNull()?.nature
        )
        require(allNatures.all { it == ValueNature.FORECAST }) {
            "HourlyForecastPoint measurements must all have ValueNature.FORECAST."
        }
    }
}

/**
 * Multi-hour weather forecast for a location.
 * Strictly distinct from [EnvironmentalObservation] even after forecast valid times pass into the past.
 */
data class WeatherForecast(
    val requestedLocation: GeoLocation,
    val effectiveLocation: GeoLocation,
    val timeZone: ZoneId,
    val issuedOrRetrievedAt: Instant,
    val hourly: List<HourlyForecastPoint>,
    val source: EnvironmentalDataSource,
    val quality: DataQualityAndFreshness
) {
    init {
        for (i in 1 until hourly.size) {
            require(!hourly[i].validAt.isBefore(hourly[i - 1].validAt)) {
                "WeatherForecast hourly points must be sorted in non-decreasing chronological order."
            }
        }
    }

    fun pointAt(instant: Instant): HourlyForecastPoint? =
        hourly.firstOrNull { it.validAt == instant }

    fun pointsInRange(startInclusive: Instant, endExclusive: Instant): List<HourlyForecastPoint> =
        hourly.filter { !it.validAt.isBefore(startInclusive) && it.validAt.isBefore(endExclusive) }
}
