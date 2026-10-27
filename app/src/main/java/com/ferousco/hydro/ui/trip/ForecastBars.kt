package com.ferousco.hydro.ui.trip

import com.ferousco.hydro.core.HydroEngine
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.inUnitOrNull
import com.ferousco.hydro.ui.TrendHour
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Hourly heat bars for a place, shared by the Trends screen and the trip chart. */
internal object ForecastBars {
    /**
     * @param emphasizeFirst how many of the first hours to draw at full strength (the "now" hour on
     * Trends, the hours you will actually be travelling on a trip chart).
     */
    suspend fun load(
        engine: HydroEngine,
        location: GeoLocation,
        count: Int = 24,
        emphasizeFirst: Int = 1
    ): List<TrendHour> {
        val envelope = (engine.weatherRepository.getHourlyForecast(location, count + 6) as? EngineResult.Success)?.value
            ?: return emptyList()
        val forecast = envelope.data
        val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(forecast.timeZone)
        val now = Instant.now()
        return forecast.hourly
            .filter { !it.validAt.isBefore(now.minus(Duration.ofHours(1))) }
            .take(count)
            .mapNotNull { point ->
                val supported = engine.assessmentEngine.assessForecastPoint(point, forecast.quality) as? HeatAssessment.Supported
                    ?: return@mapNotNull null
                TrendHour(
                    label = fmt.format(point.validAt),
                    heatIndexC = supported.heatIndexCelsius.value,
                    severity = supported.category.severityRank,
                    categoryName = supported.category.displayName,
                    isNow = false,
                    tempC = point.variables.airTemperature.inUnitOrNull(TemperatureUnit.CELSIUS),
                    weatherCode = point.variables.wmoWeatherCode,
                    isDay = point.variables.isDaylight
                )
            }
            .mapIndexed { i, hour -> if (i < emphasizeFirst) hour.copy(isNow = true) else hour }
    }
}
