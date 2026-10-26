package com.ferousco.hydro.core

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.model.DataAcquisitionMethod
import com.ferousco.hydro.core.model.DataQualityAndFreshness
import com.ferousco.hydro.core.model.DeliveryOrigin
import com.ferousco.hydro.core.model.EnvironmentalDataSource
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HistoricalWeatherSeries
import com.ferousco.hydro.core.model.HourlyForecastPoint
import com.ferousco.hydro.core.model.IrradianceUnit
import com.ferousco.hydro.core.model.MeasuredQuantity
import com.ferousco.hydro.core.model.ObservationKind
import com.ferousco.hydro.core.model.PercentageUnit
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.UnavailableReason
import com.ferousco.hydro.core.model.UvIndexUnit
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.WeatherForecast
import com.ferousco.hydro.core.provider.HttpGetRequest
import com.ferousco.hydro.core.provider.HttpPostRequest
import com.ferousco.hydro.core.provider.HttpTransport
import com.ferousco.hydro.core.provider.RawHttpResponse
import com.ferousco.hydro.core.provider.WeatherProvider
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MutableClock(
    private var currentInstant: Instant = Instant.parse("2026-10-26T12:00:00Z"),
    private val zone: ZoneId = ZoneId.of("UTC")
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)
    override fun instant(): Instant = currentInstant

    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }

    fun set(instant: Instant) {
        currentInstant = instant
    }
}

class FakeHttpTransport : HttpTransport {
    val recordedRequests = mutableListOf<HttpGetRequest>()
    val recordedPostRequests = mutableListOf<HttpPostRequest>()
    private val scripted = ArrayDeque<suspend (HttpGetRequest) -> EngineResult<RawHttpResponse>>()
    private val scriptedPosts = ArrayDeque<suspend (HttpPostRequest) -> EngineResult<RawHttpResponse>>()

    fun enqueueSuccess(statusCode: Int = 200, body: String, headers: Map<String, List<String>> = emptyMap()) {
        scripted.addLast {
            EngineResult.Success(RawHttpResponse(statusCode = statusCode, body = body, headers = headers))
        }
    }

    fun enqueueFailure(error: EngineError) {
        scripted.addLast { EngineResult.Failure(error) }
    }

    fun enqueueDelayedSuccess(delayMs: Long, body: String) {
        scripted.addLast {
            delay(delayMs)
            EngineResult.Success(RawHttpResponse(statusCode = 200, body = body))
        }
    }

    fun enqueuePostSuccess(statusCode: Int = 200, body: String) {
        scriptedPosts.addLast {
            EngineResult.Success(RawHttpResponse(statusCode = statusCode, body = body))
        }
    }

    fun enqueuePostFailure(error: EngineError) {
        scriptedPosts.addLast { EngineResult.Failure(error) }
    }

    override suspend fun get(request: HttpGetRequest, providerId: String): EngineResult<RawHttpResponse> {
        recordedRequests.add(request)
        val handler = scripted.removeFirstOrNull()
            ?: return EngineResult.Failure(EngineError.NetworkUnavailable("No scripted HTTP response remaining."))
        return handler(request)
    }

    override suspend fun post(request: HttpPostRequest, providerId: String): EngineResult<RawHttpResponse> {
        recordedPostRequests.add(request)
        val handler = scriptedPosts.removeFirstOrNull()
            ?: return EngineResult.Failure(EngineError.NetworkUnavailable("No scripted HTTP POST response remaining."))
        return handler(request)
    }
}

class FakeWeatherProvider(
    override val providerId: String = "fake-weather-provider"
) : WeatherProvider {
    var currentResult: EngineResult<EnvironmentalObservation> =
        EngineResult.Failure(EngineError.NetworkUnavailable("Unconfigured current observation"))
    var forecastResult: EngineResult<WeatherForecast> =
        EngineResult.Failure(EngineError.NetworkUnavailable("Unconfigured forecast"))
    var historicalResult: EngineResult<HistoricalWeatherSeries> =
        EngineResult.Failure(EngineError.NetworkUnavailable("Unconfigured historical weather"))
    var simulatedDelayMs: Long = 0L

    override suspend fun fetchCurrentObservation(location: GeoLocation): EngineResult<EnvironmentalObservation> {
        if (simulatedDelayMs > 0) delay(simulatedDelayMs)
        return currentResult
    }

    override suspend fun fetchHourlyForecast(
        location: GeoLocation,
        forecastHours: Int
    ): EngineResult<WeatherForecast> {
        if (simulatedDelayMs > 0) delay(simulatedDelayMs)
        return forecastResult
    }

    override suspend fun fetchHistoricalWeather(
        location: GeoLocation,
        startDate: LocalDate,
        endDate: LocalDate
    ): EngineResult<HistoricalWeatherSeries> {
        if (simulatedDelayMs > 0) delay(simulatedDelayMs)
        return historicalResult
    }
}

object TestFixtures {
    val IBADAN = GeoLocation(
        latitude = 7.3775,
        longitude = 3.9470,
        elevationMeters = 230.0,
        timeZone = ZoneId.of("Africa/Lagos"),
        label = "Ibadan"
    )

    val LAGOS = GeoLocation(
        latitude = 6.5244,
        longitude = 3.3792,
        elevationMeters = 41.0,
        timeZone = ZoneId.of("Africa/Lagos"),
        label = "Lagos"
    )

    val BERLIN = GeoLocation(
        latitude = 52.5200,
        longitude = 13.4050,
        elevationMeters = 38.0,
        timeZone = ZoneId.of("Europe/Berlin"),
        label = "Berlin"
    )

    val TEST_OBSERVATION_SOURCE = EnvironmentalDataSource(
        providerId = "open-meteo",
        providerName = "Open-Meteo",
        endpointOrDataset = "Weather Forecast API (/v1/forecast current)",
        acquisitionMethod = DataAcquisitionMethod.GRIDDED_MODEL_NOWCAST,
        modelInfo = "best_match",
        license = EnvironmentalDataSource.OPEN_METEO_CC_BY_LICENSE,
        attribution = EnvironmentalDataSource.OPEN_METEO_ATTRIBUTION,
        resolvedGridLocation = IBADAN
    )

    val TEST_FORECAST_SOURCE = EnvironmentalDataSource(
        providerId = "open-meteo",
        providerName = "Open-Meteo",
        endpointOrDataset = "Weather Forecast API (/v1/forecast hourly)",
        acquisitionMethod = DataAcquisitionMethod.NUMERICAL_WEATHER_FORECAST,
        modelInfo = "best_match",
        license = EnvironmentalDataSource.OPEN_METEO_CC_BY_LICENSE,
        attribution = EnvironmentalDataSource.OPEN_METEO_ATTRIBUTION,
        resolvedGridLocation = IBADAN
    )

    val TEST_HISTORICAL_SOURCE = EnvironmentalDataSource(
        providerId = "open-meteo",
        providerName = "Open-Meteo",
        endpointOrDataset = "Historical Weather API (/v1/archive)",
        acquisitionMethod = DataAcquisitionMethod.HISTORICAL_REANALYSIS,
        modelInfo = "ERA5 / ERA5-Land / ECMWF IFS reanalysis",
        license = EnvironmentalDataSource.OPEN_METEO_CC_BY_LICENSE,
        attribution = EnvironmentalDataSource.OPEN_METEO_ATTRIBUTION,
        resolvedGridLocation = IBADAN
    )

    fun buildVariables(
        validAt: Instant,
        location: GeoLocation = IBADAN,
        source: EnvironmentalDataSource = TEST_OBSERVATION_SOURCE,
        nature: ValueNature = ValueNature.OBSERVED,
        airTempC: Double? = 32.0,
        rhPercent: Double? = 65.0,
        windMs: Double? = 2.5,
        feelsLikeC: Double? = 36.0,
        uvIndex: Double? = 7.0,
        solarWm2: Double? = 680.0
    ): EnvironmentalSnapshotVariables {
        fun <U : com.ferousco.hydro.core.model.PhysicalUnit> avail(v: Double?, unit: U): FieldAvailability<U> =
            if (v == null) {
                FieldAvailability.Unavailable(UnavailableReason.NULL_IN_PROVIDER_RESPONSE)
            } else {
                FieldAvailability.Available(MeasuredQuantity(v, unit, validAt, location, source, nature))
            }

        return EnvironmentalSnapshotVariables(
            airTemperature = avail(airTempC, TemperatureUnit.CELSIUS),
            relativeHumidity = avail(rhPercent, PercentageUnit.PERCENT),
            windSpeed = avail(windMs, SpeedUnit.METERS_PER_SECOND),
            feelsLikeTemperature = avail(feelsLikeC, TemperatureUnit.CELSIUS),
            uvIndex = avail(uvIndex, UvIndexUnit.WHO_INDEX),
            solarRadiation = avail(solarWm2, IrradianceUnit.WATTS_PER_SQUARE_METER),
            isDaylight = true,
            wmoWeatherCode = 1
        )
    }

    fun buildObservation(
        observedAt: Instant = Instant.parse("2026-10-26T11:45:00Z"),
        retrievedAt: Instant = Instant.parse("2026-10-26T12:00:00Z"),
        evaluatedAt: Instant = retrievedAt,
        location: GeoLocation = IBADAN,
        airTempC: Double? = 33.0,
        rhPercent: Double? = 68.0,
        windMs: Double? = 2.2,
        uvIndex: Double? = 8.5,
        solarWm2: Double? = 740.0,
        deliveryOrigin: DeliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
        kind: ObservationKind = ObservationKind.MODEL_ASSIMILATED_CURRENT
    ): EnvironmentalObservation {
        val isReanalysis = kind == ObservationKind.HISTORICAL_REANALYSIS_OBSERVATION
        val source = if (isReanalysis) TEST_HISTORICAL_SOURCE else TEST_OBSERVATION_SOURCE
        val vars = buildVariables(
            validAt = observedAt,
            location = location,
            source = source,
            nature = if (isReanalysis) ValueNature.HISTORICAL_REANALYSIS else ValueNature.OBSERVED,
            airTempC = airTempC,
            rhPercent = rhPercent,
            windMs = windMs,
            feelsLikeC = airTempC?.plus(3.0),
            uvIndex = uvIndex,
            solarWm2 = solarWm2
        )
        val quality = if (isReanalysis) {
            DataQualityAndFreshness.evaluateHistoricalArchive(
                retrievedAt = retrievedAt,
                evaluatedAt = evaluatedAt,
                referenceDataTime = observedAt,
                deliveryOrigin = deliveryOrigin,
                completenessRatio = vars.completenessRatio()
            )
        } else {
            DataQualityAndFreshness.evaluateCurrentObservation(
                retrievedAt = retrievedAt,
                evaluatedAt = evaluatedAt,
                observedAt = observedAt,
                deliveryOrigin = deliveryOrigin,
                completenessRatio = vars.completenessRatio()
            )
        }
        return EnvironmentalObservation(
            requestedLocation = location,
            effectiveLocation = location,
            observedAt = observedAt,
            retrievedAt = retrievedAt,
            timeZone = location.timeZone ?: ZoneId.of("UTC"),
            observationKind = kind,
            variables = vars,
            source = source,
            quality = quality,
            intervalSeconds = 900
        )
    }

    fun buildHourlyForecast(
        startHour: Instant,
        hourlySpecs: List<Triple<Double?, Double?, Double?>>,
        retrievedAt: Instant = startHour,
        evaluatedAt: Instant = retrievedAt,
        location: GeoLocation = IBADAN,
        timeZone: ZoneId = location.timeZone ?: ZoneId.of("UTC"),
        skipHourOffsets: Set<Int> = emptySet()
    ): WeatherForecast {
        val points = hourlySpecs.mapIndexedNotNull { idx, (tempC, rh, uv) ->
            if (idx in skipHourOffsets) return@mapIndexedNotNull null
            val validAt = startHour.plus(Duration.ofHours(idx.toLong()))
            val vars = buildVariables(
                validAt = validAt,
                location = location,
                source = TEST_FORECAST_SOURCE,
                nature = ValueNature.FORECAST,
                airTempC = tempC,
                rhPercent = rh,
                windMs = 2.5,
                feelsLikeC = tempC?.plus(2.0),
                uvIndex = uv,
                solarWm2 = if (uv != null && uv > 0.0) uv * 85.0 else 0.0
            )
            HourlyForecastPoint(
                validAt = validAt,
                localDateTime = validAt.atZone(timeZone),
                issuedOrRetrievedAt = retrievedAt,
                location = location,
                variables = vars,
                source = TEST_FORECAST_SOURCE
            )
        }
        val quality = DataQualityAndFreshness.evaluateForecast(
            retrievedAt = retrievedAt,
            evaluatedAt = evaluatedAt,
            anchorTime = points.first().validAt,
            deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
            completenessRatio = points.map { it.variables.completenessRatio() }.average()
        )
        return WeatherForecast(
            requestedLocation = location,
            effectiveLocation = location,
            timeZone = timeZone,
            issuedOrRetrievedAt = retrievedAt,
            hourly = points,
            source = TEST_FORECAST_SOURCE,
            quality = quality
        )
    }
}
