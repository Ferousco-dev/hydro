package com.ferousco.hydro.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Clarifies the physical / assimilation nature of an environmental observation.
 */
enum class ObservationKind {
    /** Direct in-situ weather station or local sensor reading. */
    IN_SITU_OBSERVATION,

    /** Assimilated gridded model current-weather estimate (e.g., Open-Meteo 15-min current block). */
    MODEL_ASSIMILATED_CURRENT,

    /** Historical meteorological reanalysis estimate (e.g., Open-Meteo ERA5 / ECMWF IFS archive). */
    HISTORICAL_REANALYSIS_OBSERVATION
}

/**
 * Strongly typed set of environmental variables for a single timestamp and location.
 * Missing variables are represented via [FieldAvailability.Unavailable] rather than zero defaults.
 */
data class EnvironmentalSnapshotVariables(
    val airTemperature: FieldAvailability<TemperatureUnit>,
    val relativeHumidity: FieldAvailability<PercentageUnit>,
    val windSpeed: FieldAvailability<SpeedUnit> = FieldAvailability.Unavailable(UnavailableReason.NOT_REQUESTED),
    val feelsLikeTemperature: FieldAvailability<TemperatureUnit> = FieldAvailability.Unavailable(UnavailableReason.NOT_REQUESTED),
    val uvIndex: FieldAvailability<UvIndexUnit> = FieldAvailability.Unavailable(UnavailableReason.NOT_REQUESTED),
    val solarRadiation: FieldAvailability<IrradianceUnit> = FieldAvailability.Unavailable(UnavailableReason.NOT_REQUESTED),
    val isDaylight: Boolean? = null,
    val wmoWeatherCode: Int? = null
) {
    fun completenessRatio(expectedCoreFields: Int = 6): Double {
        val availableCount = listOf(
            airTemperature,
            relativeHumidity,
            windSpeed,
            feelsLikeTemperature,
            uvIndex,
            solarRadiation
        ).count { it.isAvailable }
        return (availableCount.toDouble() / expectedCoreFields.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    }

    fun missingVariableNames(): List<String> = buildList {
        if (!airTemperature.isAvailable) add("air_temperature")
        if (!relativeHumidity.isAvailable) add("relative_humidity")
        if (!windSpeed.isAvailable) add("wind_speed")
        if (!feelsLikeTemperature.isAvailable) add("feels_like_temperature")
        if (!uvIndex.isAvailable) add("uv_index")
        if (!solarRadiation.isAvailable) add("solar_radiation")
    }
}

/**
 * Normalized environmental observation for a past or current timestamp.
 * Strictly distinct from [WeatherForecast]; forecasts are never wrapped as [EnvironmentalObservation].
 */
data class EnvironmentalObservation(
    val requestedLocation: GeoLocation,
    val effectiveLocation: GeoLocation,
    val observedAt: Instant,
    val retrievedAt: Instant,
    val timeZone: ZoneId,
    val observationKind: ObservationKind,
    val variables: EnvironmentalSnapshotVariables,
    val source: EnvironmentalDataSource,
    val quality: DataQualityAndFreshness,
    val intervalSeconds: Int? = null
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
        require(allNatures.none { it == ValueNature.FORECAST }) {
            "EnvironmentalObservation cannot contain FORECAST values; forecasts must use WeatherForecast."
        }
    }
}

/**
 * Time-ordered historical observation series (e.g., from Open-Meteo Historical Weather API).
 */
data class HistoricalWeatherSeries(
    val requestedLocation: GeoLocation,
    val effectiveLocation: GeoLocation,
    val timeZone: ZoneId,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val retrievedAt: Instant,
    val hourlyObservations: List<EnvironmentalObservation>,
    val source: EnvironmentalDataSource,
    val quality: DataQualityAndFreshness
) {
    init {
        require(!endDate.isBefore(startDate)) {
            "HistoricalWeatherSeries endDate ($endDate) cannot precede startDate ($startDate)."
        }
    }
}
