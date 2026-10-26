package com.ferousco.hydro.core.provider.openmeteo

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.NoOpDiagnosticLogger
import com.ferousco.hydro.core.model.EnvironmentalDataSource
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.FreshnessPolicy
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HistoricalWeatherSeries
import com.ferousco.hydro.core.model.WeatherForecast
import com.ferousco.hydro.core.provider.HttpGetRequest
import com.ferousco.hydro.core.provider.HttpTransport
import com.ferousco.hydro.core.provider.JavaNetHttpTransport
import com.ferousco.hydro.core.provider.RawHttpResponse
import com.ferousco.hydro.core.provider.WeatherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Open-Meteo adapter implementing [WeatherProvider].
 *
 * Requests only the variables required by Hydro's heat-assessment and activity-planning domain,
 * requests `timeformat=unixtime` with IANA `timezone` resolution for DST-accurate timestamps,
 * normalizes units, handles transient HTTP failures with bounded retry, and preserves strict
 * separation between observations, forecasts, and historical reanalysis.
 */
class OpenMeteoWeatherProvider(
    private val config: OpenMeteoConfig = OpenMeteoConfig(),
    private val transport: HttpTransport = JavaNetHttpTransport(config.connectTimeout),
    private val freshnessPolicy: FreshnessPolicy = FreshnessPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) : WeatherProvider {

    override val providerId: String = EnvironmentalDataSource.OPEN_METEO_PROVIDER_ID

    private val mapper = OpenMeteoResponseMapper(freshnessPolicy)

    override suspend fun fetchCurrentObservation(
        location: GeoLocation
    ): EngineResult<EnvironmentalObservation> {
        val params = buildCommonParams(location) + listOf(
            "current" to CURRENT_AND_FORECAST_VARIABLES.joinToString(",")
        )
        val uri = buildUri(config.forecastBaseUri, params)
        logger.debug(
            component = COMPONENT,
            event = "Fetching current observation",
            attributes = mapOf("region" to location.redactedSummary())
        )

        val httpResult = executeWithRetry(uri, operationName = "fetchCurrentObservation")
        return when (httpResult) {
            is EngineResult.Failure -> httpResult
            is EngineResult.Success -> {
                val retrievedAt = clock.instant()
                mapper.mapCurrentObservation(
                    rawJson = httpResult.value.body,
                    requestedLocation = location,
                    retrievedAt = retrievedAt
                )
            }
        }
    }

    override suspend fun fetchHourlyForecast(
        location: GeoLocation,
        forecastHours: Int
    ): EngineResult<WeatherForecast> {
        if (forecastHours !in 1..OpenMeteoConfig.MAX_FORECAST_HOURS) {
            return EngineResult.Failure(
                EngineError.InvalidInput(
                    field = "forecastHours",
                    rejectedValueSummary = forecastHours.toString(),
                    message = "forecastHours must be within [1, ${OpenMeteoConfig.MAX_FORECAST_HOURS}], got $forecastHours."
                )
            )
        }

        val params = buildCommonParams(location) + listOf(
            "hourly" to CURRENT_AND_FORECAST_VARIABLES.joinToString(","),
            "forecast_hours" to forecastHours.toString()
        )
        val uri = buildUri(config.forecastBaseUri, params)
        logger.debug(
            component = COMPONENT,
            event = "Fetching hourly forecast",
            attributes = mapOf(
                "region" to location.redactedSummary(),
                "forecastHours" to forecastHours.toString()
            )
        )

        val httpResult = executeWithRetry(uri, operationName = "fetchHourlyForecast")
        return when (httpResult) {
            is EngineResult.Failure -> httpResult
            is EngineResult.Success -> {
                val retrievedAt = clock.instant()
                mapper.mapHourlyForecast(
                    rawJson = httpResult.value.body,
                    requestedLocation = location,
                    retrievedAt = retrievedAt,
                    maxHours = forecastHours
                )
            }
        }
    }

    override suspend fun fetchHistoricalWeather(
        location: GeoLocation,
        startDate: LocalDate,
        endDate: LocalDate
    ): EngineResult<HistoricalWeatherSeries> {
        if (endDate.isBefore(startDate)) {
            return EngineResult.Failure(
                EngineError.InvalidInput(
                    field = "dateRange",
                    rejectedValueSummary = "$startDate..$endDate",
                    message = "endDate ($endDate) must not precede startDate ($startDate)."
                )
            )
        }
        if (startDate.year < OpenMeteoConfig.MIN_HISTORICAL_DATE_YEAR) {
            return EngineResult.Failure(
                EngineError.InvalidInput(
                    field = "startDate",
                    rejectedValueSummary = startDate.toString(),
                    message = "Open-Meteo historical archive starts at year ${OpenMeteoConfig.MIN_HISTORICAL_DATE_YEAR}."
                )
            )
        }
        val todayUtc = LocalDate.ofInstant(clock.instant(), location.timeZone ?: java.time.ZoneOffset.UTC)
        if (endDate.isAfter(todayUtc)) {
            return EngineResult.Failure(
                EngineError.InvalidInput(
                    field = "endDate",
                    rejectedValueSummary = endDate.toString(),
                    message = "Historical weather endDate ($endDate) cannot be in the future relative to $todayUtc."
                )
            )
        }
        val spanDays = ChronoUnit.DAYS.between(startDate, endDate) + 1
        if (spanDays > OpenMeteoConfig.MAX_HISTORICAL_SPAN_DAYS) {
            return EngineResult.Failure(
                EngineError.InvalidInput(
                    field = "dateRange",
                    rejectedValueSummary = "${spanDays}d",
                    message = "Historical request span ($spanDays days) exceeds maximum (${OpenMeteoConfig.MAX_HISTORICAL_SPAN_DAYS} days)."
                )
            )
        }

        val params = buildCommonParams(location) + listOf(
            "start_date" to startDate.toString(),
            "end_date" to endDate.toString(),
            "hourly" to HISTORICAL_ARCHIVE_VARIABLES.joinToString(",")
        )
        val uri = buildUri(config.historicalBaseUri, params)
        logger.debug(
            component = COMPONENT,
            event = "Fetching historical weather",
            attributes = mapOf(
                "region" to location.redactedSummary(),
                "startDate" to startDate.toString(),
                "endDate" to endDate.toString()
            )
        )

        val httpResult = executeWithRetry(uri, operationName = "fetchHistoricalWeather")
        return when (httpResult) {
            is EngineResult.Failure -> httpResult
            is EngineResult.Success -> {
                val retrievedAt = clock.instant()
                mapper.mapHistoricalWeather(
                    rawJson = httpResult.value.body,
                    requestedLocation = location,
                    startDate = startDate,
                    endDate = endDate,
                    retrievedAt = retrievedAt
                )
            }
        }
    }

    private suspend fun executeWithRetry(
        uri: URI,
        operationName: String
    ): EngineResult<RawHttpResponse> {
        val request = HttpGetRequest(
            uri = uri,
            headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to config.userAgent
            ),
            connectTimeout = config.connectTimeout,
            readTimeout = config.readTimeout
        )

        var lastError: EngineError? = null
        for (attempt in 1..config.maxRetryAttempts) {
            val responseResult = try {
                transport.get(request, providerId)
            } catch (ce: CancellationException) {
                logger.info(COMPONENT, "Cancelled $operationName")
                throw ce
            }

            when (responseResult) {
                is EngineResult.Success -> {
                    val resp = responseResult.value
                    if (resp.statusCode in 200..299) {
                        return EngineResult.Success(resp)
                    }
                    val providerError = mapHttpErrorStatus(resp)
                    lastError = providerError
                    if (!providerError.isTransient || attempt == config.maxRetryAttempts) {
                        logger.warn(
                            COMPONENT,
                            "Provider HTTP failure on $operationName",
                            mapOf(
                                "statusCode" to resp.statusCode.toString(),
                                "transient" to providerError.isTransient.toString(),
                                "attempt" to attempt.toString()
                            )
                        )
                        return EngineResult.Failure(providerError)
                    }
                }
                is EngineResult.Failure -> {
                    val err = responseResult.error
                    lastError = err
                    val retryable = err is EngineError.ProviderError && err.isTransient
                    if (!retryable || attempt == config.maxRetryAttempts) {
                        logger.warn(
                            COMPONENT,
                            "Transport failure on $operationName",
                            mapOf(
                                "errorType" to (err::class.simpleName ?: "EngineError"),
                                "attempt" to attempt.toString()
                            )
                        )
                        return EngineResult.Failure(err)
                    }
                }
            }

            val backoffMillis = computeRetryDelayMillis(attempt, lastError)
            delay(backoffMillis)
        }

        return EngineResult.Failure(
            lastError ?: EngineError.ProviderError(
                providerId = providerId,
                statusCode = null,
                providerReason = "exhausted_retries",
                isTransient = true,
                message = "Retries exhausted for $operationName."
            )
        )
    }

    private fun computeRetryDelayMillis(attempt: Int, error: EngineError?): Long {
        val retryAfterMs = (error as? EngineError.ProviderError)?.retryAfterSeconds?.let {
            (it.coerceIn(1L, 5L)) * 1000L
        }
        val expMs = config.initialRetryBackoff.toMillis() * (1L shl (attempt - 1))
        return retryAfterMs ?: expMs.coerceAtMost(3_000L)
    }

    private fun mapHttpErrorStatus(response: RawHttpResponse): EngineError.ProviderError {
        val status = response.statusCode
        val reason = mapper.parseErrorReasonOrNull(response.body)
        val retryAfter = response.firstHeader("Retry-After")?.trim()?.toLongOrNull()
        val isTransient = status == 408 || status == 429 || status in 500..599
        return EngineError.ProviderError(
            providerId = providerId,
            statusCode = status,
            providerReason = reason,
            isTransient = isTransient,
            retryAfterSeconds = retryAfter,
            message = reason ?: "Open-Meteo returned HTTP status $status."
        )
    }

    private fun buildCommonParams(location: GeoLocation): List<Pair<String, String>> = buildList {
        add("latitude" to String.format(Locale.US, "%.5f", location.latitude))
        add("longitude" to String.format(Locale.US, "%.5f", location.longitude))
        if (location.elevationMeters != null) {
            add("elevation" to String.format(Locale.US, "%.1f", location.elevationMeters))
        }
        add("temperature_unit" to "celsius")
        add("wind_speed_unit" to "ms")
        add("timeformat" to "unixtime")
        add("timezone" to (location.timeZone?.id ?: "auto"))
    }

    companion object {
        private const val COMPONENT = "OpenMeteoWeatherProvider"

        internal val CURRENT_AND_FORECAST_VARIABLES = listOf(
            "temperature_2m",
            "relative_humidity_2m",
            "apparent_temperature",
            "wind_speed_10m",
            "uv_index",
            "shortwave_radiation",
            "is_day",
            "weather_code"
        )

        // Open-Meteo /v1/archive supports these required hourly variables; hourly uv_index is not supported on /v1/archive.
        internal val HISTORICAL_ARCHIVE_VARIABLES = listOf(
            "temperature_2m",
            "relative_humidity_2m",
            "apparent_temperature",
            "wind_speed_10m",
            "shortwave_radiation",
            "is_day",
            "weather_code"
        )

        internal fun buildUri(baseUri: URI, params: List<Pair<String, String>>): URI {
            val query = params.joinToString("&") { (k, v) ->
                val ek = URLEncoder.encode(k, StandardCharsets.UTF_8)
                val ev = URLEncoder.encode(v, StandardCharsets.UTF_8)
                "$ek=$ev"
            }
            val base = baseUri.toString().trimEnd('?')
            val sep = if (base.contains('?')) '&' else '?'
            return URI.create("$base$sep$query")
        }
    }
}
