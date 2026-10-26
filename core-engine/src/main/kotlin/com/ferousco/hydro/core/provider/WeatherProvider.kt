package com.ferousco.hydro.core.provider

import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HistoricalWeatherSeries
import com.ferousco.hydro.core.model.WeatherForecast
import java.time.LocalDate

/**
 * Provider-agnostic weather adapter boundary.
 * Implementations normalize external responses into Hydro domain models without leaking DTOs.
 */
interface WeatherProvider {
    val providerId: String

    suspend fun fetchCurrentObservation(
        location: GeoLocation
    ): EngineResult<EnvironmentalObservation>

    suspend fun fetchHourlyForecast(
        location: GeoLocation,
        forecastHours: Int = 48
    ): EngineResult<WeatherForecast>

    suspend fun fetchHistoricalWeather(
        location: GeoLocation,
        startDate: LocalDate,
        endDate: LocalDate
    ): EngineResult<HistoricalWeatherSeries>
}
