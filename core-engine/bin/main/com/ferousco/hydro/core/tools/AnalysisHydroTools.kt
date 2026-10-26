package com.ferousco.hydro.core.tools

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.error.flatMap
import com.ferousco.hydro.core.history.EnvironmentalHistoryRepository
import com.ferousco.hydro.core.integration.HeatMapLayer
import com.ferousco.hydro.core.integration.HeatMapMetric
import com.ferousco.hydro.core.integration.HeatMapProvider
import com.ferousco.hydro.core.integration.HeatMapRequest
import com.ferousco.hydro.core.integration.LocationFix
import com.ferousco.hydro.core.integration.LocationSource
import com.ferousco.hydro.core.integration.RoutePlan
import com.ferousco.hydro.core.integration.RouteRequest
import com.ferousco.hydro.core.integration.RoutingProvider
import com.ferousco.hydro.core.integration.TravelMode
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.DataQualityAndFreshness
import com.ferousco.hydro.core.model.DeliveryOrigin
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.HistoricalRecordDataType
import com.ferousco.hydro.core.model.HourlyForecastPoint
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.WeatherForecast
import com.ferousco.hydro.core.model.rawValueOrNull
import com.ferousco.hydro.core.provider.CachePolicy
import com.ferousco.hydro.core.provider.WeatherRepository
import com.ferousco.hydro.core.provider.WeatherRetrievalEnvelope
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// ============================================================================
// 7. get_current_location
// ============================================================================

data class GetCurrentLocationParams(
    val maxAge: Duration,
    val includeLabel: Boolean
)

class GetCurrentLocationTool(
    private val locationSource: LocationSource,
    private val clock: Clock = Clock.systemUTC()
) : HydroTool<GetCurrentLocationParams, LocationFix> {

    override val descriptor = ToolDescriptor(
        name = "get_current_location",
        description = "Return the device's current location fix with explicit accuracy and freshness. " +
            "Requires location permission; Hydro never guesses coordinates.",
        requiredCapabilities = setOf(ToolCapability.LOCATION_READ),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema(
                    "max_age_seconds",
                    ToolSchemaType.INTEGER,
                    "Maximum acceptable age of the platform fix in seconds [60, 86400].",
                    required = false,
                    minimum = 60.0,
                    maximum = 86_400.0
                ),
                ToolPropertySchema(
                    "include_label",
                    ToolSchemaType.BOOLEAN,
                    "Whether to include any human-readable place label supplied by the location source.",
                    required = false
                )
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("latitude", ToolSchemaType.NUMBER, "WGS84 latitude."),
                ToolPropertySchema("longitude", ToolSchemaType.NUMBER, "WGS84 longitude."),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Resolved IANA timezone, or 'unspecified'."),
                ToolPropertySchema("captured_at", ToolSchemaType.STRING, "ISO-8601 time the platform captured the fix."),
                ToolPropertySchema("age_seconds", ToolSchemaType.INTEGER, "Age of the fix at tool execution."),
                ToolPropertySchema("accuracy_meters", ToolSchemaType.NUMBER, "Reported horizontal accuracy, when available.", required = false),
                ToolPropertySchema("provider", ToolSchemaType.STRING, "Platform location provider name."),
                ToolPropertySchema("is_mock", ToolSchemaType.BOOLEAN, "Whether the platform flagged this as a mock location."),
                ToolPropertySchema("freshness_state", ToolSchemaType.STRING, "FRESH or AGING."),
                ToolPropertySchema("label", ToolSchemaType.STRING, "Optional place label.", required = false)
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(10),
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "STALE_DATA", "UNSUPPORTED_CALCULATION", "TOOL_TIMEOUT")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<GetCurrentLocationParams> {
        val seconds = rawArgs.getInt("max_age_seconds") ?: 900
        if (seconds !in 60..86_400) {
            return EngineResult.Failure(
                EngineError.InvalidInput("max_age_seconds", seconds.toString(), "max_age_seconds must be between 60 and 86400.")
            )
        }
        return EngineResult.Success(
            GetCurrentLocationParams(
                maxAge = Duration.ofSeconds(seconds.toLong()),
                includeLabel = rawArgs.getBoolean("include_label") ?: true
            )
        )
    }

    override suspend fun executeTyped(params: GetCurrentLocationParams): EngineResult<LocationFix> {
        val fix = when (val result = locationSource.currentLocation(params.maxAge)) {
            is EngineResult.Failure -> return result
            is EngineResult.Success -> result.value
        }
        val age = Duration.between(fix.capturedAt, clock.instant())
        if (age.isNegative) {
            return EngineResult.Failure(
                EngineError.InvalidResponse(
                    providerId = "location",
                    fieldOrStage = "captured_at",
                    message = "Location fix is dated in the future (${fix.capturedAt}); refusing to use it."
                )
            )
        }
        if (age > params.maxAge) {
            return EngineResult.Failure(
                EngineError.StaleData(
                    retrievedAt = fix.capturedAt,
                    evaluatedAt = clock.instant(),
                    maxAllowedAge = params.maxAge,
                    message = "The most recent location fix is ${age.seconds}s old, which exceeds the " +
                        "allowed ${params.maxAge.seconds}s. Ask the user to refresh location or pass " +
                        "explicit coordinates to the weather tools."
                )
            )
        }
        // Place labels can resolve to a street address, so callers must opt in explicitly.
        val effective = if (params.includeLabel) fix else fix.copy(location = fix.location.copy(label = null))
        return EngineResult.Success(effective)
    }

    override fun encodeResult(result: LocationFix): JsonValue.ObjectValue {
        val ageSeconds = Duration.between(result.capturedAt, clock.instant()).seconds.coerceAtLeast(0)
        return JsonValue.ObjectValue(
            linkedMapOf<String, JsonValue>().apply {
                put("data_nature", JsonValue.StringValue(ValueNature.OBSERVED.name))
                put("latitude", SafeJsonCodec.number(result.location.latitude))
                put("longitude", SafeJsonCodec.number(result.location.longitude))
                put("timezone", JsonValue.StringValue(result.location.timeZone?.id ?: "unspecified"))
                put("captured_at", JsonValue.StringValue(result.capturedAt.toString()))
                put("age_seconds", SafeJsonCodec.number(ageSeconds))
                result.accuracyMeters?.let { put("accuracy_meters", SafeJsonCodec.number(it)) }
                put("provider", JsonValue.StringValue(result.provider))
                put("is_mock", JsonValue.BooleanValue(result.isMock))
                put("freshness_state", JsonValue.StringValue(if (ageSeconds <= 60) "FRESH" else "AGING"))
                // Labels can be precise addresses, so they are opt-out rather than always included.
                result.location.label?.let { put("label", JsonValue.StringValue(it)) }
            }
        )
    }
}

// ============================================================================
// 8. analyze_anomaly
// ============================================================================

enum class AnomalyStatus {
    EVALUATED,
    INSUFFICIENT_BASELINE
}

enum class AnomalyDirection {
    ABOVE_BASELINE,
    BELOW_BASELINE,
    WITHIN_THRESHOLD
}

enum class AnomalySeverity {
    NORMAL,
    ELEVATED,
    SEVERE
}

data class VariableAnomaly(
    val variable: String,
    val unit: String,
    val observed: Double,
    val baselineMean: Double,
    val baselineStdDev: Double,
    val delta: Double,
    val threshold: Double,
    val direction: AnomalyDirection,
    val severity: AnomalySeverity
)

data class ForecastExpectation(
    val validAt: Instant,
    val airTemperatureCelsius: Double?,
    val relativeHumidityPercent: Double?,
    val airTemperatureDeltaCelsius: Double?,
    val humidityDeltaPercent: Double?
)

data class AnomalyAnalysisResult(
    val status: AnomalyStatus,
    val observedAt: Instant,
    val observationKind: String,
    val baselineSampleCount: Int,
    val baselineWindowDays: Int,
    val baselineMethod: String,
    val anomalies: List<VariableAnomaly>,
    val forecastExpectation: ForecastExpectation?,
    val explanation: String,
    val limitations: List<String>,
    val freshness: DataQualityAndFreshness
)

data class AnalyzeAnomalyParams(
    val location: GeoLocation,
    val lookbackDays: Int,
    val temperatureThresholdCelsius: Double,
    val humidityThresholdPercent: Double,
    val allowCacheFallback: Boolean
)

/**
 * Compares the current reading against a local same-hour baseline and the issued forecast.
 *
 * The tool never accepts readings as arguments: it fetches its own observation. That keeps the model
 * from "analysing" numbers it invented, which is the exact failure mode the product document calls out.
 */
class AnalyzeAnomalyTool(
    private val weatherRepository: WeatherRepository,
    private val historyRepository: EnvironmentalHistoryRepository,
    private val clock: Clock = Clock.systemUTC()
) : HydroTool<AnalyzeAnomalyParams, AnomalyAnalysisResult> {

    override val descriptor = ToolDescriptor(
        name = "analyze_anomaly",
        description = "Compare the current reading against a local same-hour historical baseline and " +
            "against the issued forecast, reporting deterministic per-variable deviations.",
        requiredCapabilities = setOf(ToolCapability.WEATHER_PROVIDER_READ, ToolCapability.LOCAL_HISTORY_READ),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("latitude", ToolSchemaType.NUMBER, "Latitude in degrees [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("longitude", ToolSchemaType.NUMBER, "Longitude in degrees [-180, 180].", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("lookback_days", ToolSchemaType.INTEGER, "Days of local history to use as the baseline [1, 30].", required = false, minimum = 1.0, maximum = 30.0),
                ToolPropertySchema("temperature_threshold_celsius", ToolSchemaType.NUMBER, "Deviation treated as an anomaly in °C [0.5, 15].", required = false, minimum = 0.5, maximum = 15.0),
                ToolPropertySchema("humidity_threshold_percent", ToolSchemaType.NUMBER, "Deviation treated as an anomaly in percentage points [1, 50].", required = false, minimum = 1.0, maximum = 50.0),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Optional IANA timezone ID.", required = false, maxLength = 64),
                ToolPropertySchema("allow_cache_fallback", ToolSchemaType.BOOLEAN, "Whether cached weather may be used when live network fails.", required = false)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("status", ToolSchemaType.STRING, "EVALUATED or INSUFFICIENT_BASELINE."),
                ToolPropertySchema("observed_at", ToolSchemaType.STRING, "ISO-8601 observation timestamp."),
                ToolPropertySchema("baseline_sample_count", ToolSchemaType.INTEGER, "Same-hour records used for the baseline."),
                ToolPropertySchema("anomalies", ToolSchemaType.ARRAY, "Per-variable deviation results.", arrayItemType = ToolSchemaType.OBJECT),
                ToolPropertySchema("forecast_expectation", ToolSchemaType.OBJECT, "Issued forecast at the observation time, when available.", required = false),
                ToolPropertySchema("explanation", ToolSchemaType.STRING, "Deterministic summary."),
                ToolPropertySchema("limitations", ToolSchemaType.ARRAY, "Explicit method limitations.", arrayItemType = ToolSchemaType.STRING)
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(20),
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "NETWORK_UNAVAILABLE", "PROVIDER_ERROR", "PERSISTENCE_FAILURE", "TOOL_TIMEOUT")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<AnalyzeAnomalyParams> {
        val location = when (val parsed = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> return parsed
            is EngineResult.Success -> parsed.value
        }
        return EngineResult.Success(
            AnalyzeAnomalyParams(
                location = location,
                lookbackDays = rawArgs.getInt("lookback_days") ?: 7,
                temperatureThresholdCelsius = rawArgs.getDouble("temperature_threshold_celsius") ?: 2.0,
                humidityThresholdPercent = rawArgs.getDouble("humidity_threshold_percent") ?: 10.0,
                allowCacheFallback = rawArgs.getBoolean("allow_cache_fallback") ?: true
            )
        )
    }

    override suspend fun executeTyped(params: AnalyzeAnomalyParams): EngineResult<AnomalyAnalysisResult> {
        val policy = if (params.allowCacheFallback) CachePolicy.PREFER_FRESH_FALLBACK_CACHE else CachePolicy.NETWORK_ONLY
        val observation = when (val result = weatherRepository.getCurrentObservation(params.location, policy)) {
            is EngineResult.Failure -> return result
            is EngineResult.Success -> result.value
        }
        val observed = observation.data
        val windowStart = observed.observedAt.minus(Duration.ofDays(params.lookbackDays.toLong()))

        val history = when (
            val result = historyRepository.queryRecentRecords(
                location = params.location,
                maxDistanceKm = BASELINE_RADIUS_KM,
                dataTypes = setOf(HistoricalRecordDataType.OBSERVATION),
                validFromInclusive = windowStart,
                validToInclusive = observed.observedAt,
                limit = MAX_BASELINE_RECORDS
            )
        ) {
            is EngineResult.Failure -> return result
            is EngineResult.Success -> result.value
        }

        val sameHour = history.filter { record ->
            sameLocalHourDistanceMinutes(record, observed) <= SAME_HOUR_TOLERANCE_MINUTES
        }

        val temperatures = sameHour.mapNotNull { it.variables.airTemperature.rawValueOrNull() }
        val humidities = sameHour.mapNotNull { it.variables.relativeHumidity.rawValueOrNull() }

        val temperatureAnomaly = buildAnomaly(
            variable = "air_temperature",
            unit = "°C",
            samples = temperatures,
            observedValue = observed.variables.airTemperature.rawValueOrNull(),
            threshold = params.temperatureThresholdCelsius
        )
        val humidityAnomaly = buildAnomaly(
            variable = "relative_humidity",
            unit = "%",
            samples = humidities,
            observedValue = observed.variables.relativeHumidity.rawValueOrNull(),
            threshold = params.humidityThresholdPercent
        )
        val anomalies = listOfNotNull(temperatureAnomaly, humidityAnomaly)

        val status = if (anomalies.isEmpty()) {
            AnomalyStatus.INSUFFICIENT_BASELINE
        } else {
            AnomalyStatus.EVALUATED
        }

        val forecastExpectation = forecastExpectationFor(params, observed, policy)

        val explanation = when (status) {
            AnomalyStatus.INSUFFICIENT_BASELINE ->
                "Not enough same-hour local history (found ${sameHour.size} usable record(s); " +
                    "$MIN_BASELINE_SAMPLES required) to establish a baseline. Hydro will not call a " +
                    "reading anomalous without one."
            AnomalyStatus.EVALUATED -> buildString {
                append("Compared ${observed.observedAt} against ${sameHour.size} same-hour local record(s) ")
                append("from the last ${params.lookbackDays} day(s). ")
                for (anomaly in anomalies) {
                    append("${anomaly.variable} ${anomaly.observed}${anomaly.unit} vs baseline ")
                    append("${round(anomaly.baselineMean)}${anomaly.unit} ")
                    append("(delta ${signed(anomaly.delta)}${anomaly.unit}, ${anomaly.severity.name.lowercase()}). ")
                }
            }.trim()
        }

        return EngineResult.Success(
            AnomalyAnalysisResult(
                status = status,
                observedAt = observed.observedAt,
                observationKind = observed.observationKind.name,
                baselineSampleCount = sameHour.size,
                baselineWindowDays = params.lookbackDays,
                baselineMethod = "SAME_LOCAL_HOUR_MEAN_${SAME_HOUR_TOLERANCE_MINUTES}MIN_TOLERANCE",
                anomalies = anomalies,
                forecastExpectation = forecastExpectation,
                explanation = explanation,
                limitations = listOf(
                    "The baseline is the mean of locally stored same-hour observations within " +
                        "$BASELINE_RADIUS_KM km; it is not a climate normal or a provider-issued baseline.",
                    "Historical records only exist for hours Hydro has previously observed on this device.",
                    "Thresholds are caller-supplied planning thresholds, not medical or regulatory limits.",
                    "Forecast expectations are model output and may themselves be wrong; they are not observations."
                ),
                freshness = observation.freshness
            )
        )
    }

    override fun encodeResult(result: AnomalyAnalysisResult): JsonValue.ObjectValue {
        val anomalyJson = result.anomalies.map { anomaly ->
            JsonValue.ObjectValue(
                linkedMapOf(
                    "variable" to JsonValue.StringValue(anomaly.variable),
                    "unit" to JsonValue.StringValue(anomaly.unit),
                    "observed" to SafeJsonCodec.number(anomaly.observed),
                    "baseline_mean" to SafeJsonCodec.number(round(anomaly.baselineMean)),
                    "baseline_std_dev" to SafeJsonCodec.number(round(anomaly.baselineStdDev)),
                    "delta" to SafeJsonCodec.number(round(anomaly.delta)),
                    "threshold" to SafeJsonCodec.number(anomaly.threshold),
                    "direction" to JsonValue.StringValue(anomaly.direction.name),
                    "severity" to JsonValue.StringValue(anomaly.severity.name)
                )
            )
        }

        val forecastJson = result.forecastExpectation?.let { expectation ->
            JsonValue.ObjectValue(
                linkedMapOf<String, JsonValue>().apply {
                    put("valid_at", JsonValue.StringValue(expectation.validAt.toString()))
                    expectation.airTemperatureCelsius?.let { put("air_temperature_celsius", SafeJsonCodec.number(it)) }
                    expectation.relativeHumidityPercent?.let { put("relative_humidity_percent", SafeJsonCodec.number(it)) }
                    expectation.airTemperatureDeltaCelsius?.let { put("air_temperature_delta_celsius", SafeJsonCodec.number(round(it))) }
                    expectation.humidityDeltaPercent?.let { put("humidity_delta_percent", SafeJsonCodec.number(round(it))) }
                }
            )
        }

        return JsonValue.ObjectValue(
            linkedMapOf<String, JsonValue>().apply {
                put("status", JsonValue.StringValue(result.status.name))
                put("observed_at", JsonValue.StringValue(result.observedAt.toString()))
                put("observation_kind", JsonValue.StringValue(result.observationKind))
                put("baseline_sample_count", SafeJsonCodec.number(result.baselineSampleCount))
                put("baseline_window_days", SafeJsonCodec.number(result.baselineWindowDays))
                put("baseline_method", JsonValue.StringValue(result.baselineMethod))
                put("anomalies", JsonValue.ArrayValue(anomalyJson))
                forecastJson?.let { put("forecast_expectation", it) }
                put("freshness", ToolEncodingSupport.encodeFreshness(result.freshness))
                put("explanation", JsonValue.StringValue(result.explanation))
                put("limitations", JsonValue.ArrayValue(result.limitations.map(JsonValue::StringValue)))
            }
        )
    }

    private suspend fun forecastExpectationFor(
        params: AnalyzeAnomalyParams,
        observed: EnvironmentalObservation,
        policy: CachePolicy
    ): ForecastExpectation? {
        val forecast = when (
            val result = weatherRepository.getHourlyForecast(params.location, FORECAST_LOOKUP_HOURS, policy)
        ) {
            is EngineResult.Failure -> return null
            is EngineResult.Success -> result.value.data
        }
        val point = nearestForecastPoint(forecast, observed.observedAt, SAME_HOUR_TOLERANCE_MINUTES) ?: return null
        val forecastTemp = point.variables.airTemperature.rawValueOrNull()
        val forecastHumidity = point.variables.relativeHumidity.rawValueOrNull()
        val observedTemp = observed.variables.airTemperature.rawValueOrNull()
        val observedHumidity = observed.variables.relativeHumidity.rawValueOrNull()
        return ForecastExpectation(
            validAt = point.validAt,
            airTemperatureCelsius = forecastTemp,
            relativeHumidityPercent = forecastHumidity,
            airTemperatureDeltaCelsius = if (forecastTemp != null && observedTemp != null) observedTemp - forecastTemp else null,
            humidityDeltaPercent = if (forecastHumidity != null && observedHumidity != null) observedHumidity - forecastHumidity else null
        )
    }

    private fun sameLocalHourDistanceMinutes(
        record: StoredEnvironmentalRecord,
        observed: EnvironmentalObservation
    ): Long {
        val zone = runCatching { ZoneId.of(record.timeZoneId) }.getOrDefault(observed.timeZone)
        val recordMinute = record.validAt.atZone(zone).toLocalTime().toSecondOfDay() / 60L
        val observedMinute = observed.observedAt.atZone(observed.timeZone).toLocalTime().toSecondOfDay() / 60L
        val raw = abs(recordMinute - observedMinute)
        return minOf(raw, MINUTES_PER_DAY - raw)
    }

    private fun buildAnomaly(
        variable: String,
        unit: String,
        samples: List<Double>,
        observedValue: Double?,
        threshold: Double
    ): VariableAnomaly? {
        if (observedValue == null || samples.size < MIN_BASELINE_SAMPLES) return null
        val mean = samples.average()
        val variance = samples.sumOf { (it - mean).pow(2) } / samples.size
        val stdDev = sqrt(variance)
        val delta = observedValue - mean
        val magnitude = abs(delta)
        val direction = when {
            magnitude < threshold -> AnomalyDirection.WITHIN_THRESHOLD
            delta > 0 -> AnomalyDirection.ABOVE_BASELINE
            else -> AnomalyDirection.BELOW_BASELINE
        }
        val severity = when {
            magnitude < threshold -> AnomalySeverity.NORMAL
            magnitude >= threshold * SEVERE_MULTIPLIER -> AnomalySeverity.SEVERE
            else -> AnomalySeverity.ELEVATED
        }
        return VariableAnomaly(
            variable = variable,
            unit = unit,
            observed = observedValue,
            baselineMean = mean,
            baselineStdDev = stdDev,
            delta = delta,
            threshold = threshold,
            direction = direction,
            severity = severity
        )
    }

    companion object {
        const val MIN_BASELINE_SAMPLES = 3
        const val MAX_BASELINE_RECORDS = 500
        const val BASELINE_RADIUS_KM = 10.0
        const val SAME_HOUR_TOLERANCE_MINUTES = 90L
        const val SEVERE_MULTIPLIER = 2.0
        const val FORECAST_LOOKUP_HOURS = 6
        private const val MINUTES_PER_DAY = 1440L
    }
}

// ============================================================================
// 9. get_route_conditions
// ============================================================================

data class GetRouteConditionsParams(
    val origin: GeoLocation,
    val destination: GeoLocation,
    val travelMode: TravelMode,
    val departAt: Instant
)

data class RouteSampleConditions(
    val fraction: Double,
    val location: GeoLocation,
    val eta: Instant,
    val forecastValidAt: Instant,
    val heatIndexCelsius: Double?,
    val category: String?,
    val airTemperatureCelsius: Double?,
    val relativeHumidityPercent: Double?,
    val uvIndex: Double?
)

data class RouteConditionsResult(
    val distanceKm: Double,
    val durationMinutes: Long,
    val travelMode: TravelMode,
    val routeMethod: String,
    val departAt: Instant,
    val samples: List<RouteSampleConditions>,
    val worstCategory: String?,
    val advisories: List<String>,
    val limitations: List<String>,
    val forecastFreshness: DataQualityAndFreshness
)

class GetRouteConditionsTool(
    private val routingProvider: RoutingProvider,
    private val weatherRepository: WeatherRepository,
    private val assessmentEngine: HeatAssessmentEngine,
    private val clock: Clock = Clock.systemUTC()
) : HydroTool<GetRouteConditionsParams, RouteConditionsResult> {

    override val descriptor = ToolDescriptor(
        name = "get_route_conditions",
        description = "Estimate route distance and duration, then evaluate forecast heat conditions at " +
            "sampled points along the route at their expected arrival times.",
        requiredCapabilities = setOf(
            ToolCapability.ROUTING_READ,
            ToolCapability.WEATHER_PROVIDER_READ,
            ToolCapability.DETERMINISTIC_ANALYSIS
        ),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("origin_latitude", ToolSchemaType.NUMBER, "Origin latitude [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("origin_longitude", ToolSchemaType.NUMBER, "Origin longitude [-180, 180].", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("destination_latitude", ToolSchemaType.NUMBER, "Destination latitude [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("destination_longitude", ToolSchemaType.NUMBER, "Destination longitude [-180, 180].", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("travel_mode", ToolSchemaType.ENUM, "Travel mode.", required = true, enumValues = TravelMode.entries.map { it.name }),
                ToolPropertySchema("depart_at", ToolSchemaType.STRING, "ISO-8601 departure instant; defaults to now.", required = false, maxLength = 40),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Optional IANA timezone ID.", required = false, maxLength = 64)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("status", ToolSchemaType.STRING, "EVALUATED."),
                ToolPropertySchema("distance_km", ToolSchemaType.NUMBER, "Estimated route distance."),
                ToolPropertySchema("duration_minutes", ToolSchemaType.INTEGER, "Estimated travel duration."),
                ToolPropertySchema("travel_mode", ToolSchemaType.STRING, "Requested travel mode."),
                ToolPropertySchema("route_method", ToolSchemaType.STRING, "How geometry/duration were produced."),
                ToolPropertySchema("samples", ToolSchemaType.ARRAY, "Conditions sampled along the route.", arrayItemType = ToolSchemaType.OBJECT),
                ToolPropertySchema("worst_category", ToolSchemaType.STRING, "Worst NWS Heat Index category on the route.", required = false),
                ToolPropertySchema("advisories", ToolSchemaType.ARRAY, "Grounded route advisories.", arrayItemType = ToolSchemaType.STRING),
                ToolPropertySchema("limitations", ToolSchemaType.ARRAY, "Explicit route and sampling limitations.", arrayItemType = ToolSchemaType.STRING)
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(25),
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "NETWORK_UNAVAILABLE", "PROVIDER_ERROR", "UNSUPPORTED_CALCULATION", "TOOL_TIMEOUT")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<GetRouteConditionsParams> {
        val origin = when (
            val parsed = ToolEncodingSupport.parseLocation(
                rawArgs,
                latitudeKey = "origin_latitude",
                longitudeKey = "origin_longitude"
            )
        ) {
            is EngineResult.Failure -> return parsed
            is EngineResult.Success -> parsed.value
        }
        val destination = when (
            val parsed = ToolEncodingSupport.parseLocation(
                rawArgs,
                latitudeKey = "destination_latitude",
                longitudeKey = "destination_longitude"
            )
        ) {
            is EngineResult.Failure -> return parsed
            is EngineResult.Success -> parsed.value
        }
        if (origin.distanceKilometersTo(destination) < MIN_ROUTE_KM) {
            return EngineResult.Failure(
                EngineError.InvalidInput(
                    field = "destination_latitude",
                    rejectedValueSummary = "too_close",
                    message = "Origin and destination are less than ${MIN_ROUTE_KM}km apart; there is no route to evaluate."
                )
            )
        }
        val mode = TravelMode.valueOf(rawArgs.getString("travel_mode")!!)
        val departAt = rawArgs.getString("depart_at")?.let {
            when (val parsed = ToolEncodingSupport.parseInstant(it, "depart_at")) {
                is EngineResult.Failure -> return parsed
                is EngineResult.Success -> parsed.value
            }
        } ?: clock.instant()

        return EngineResult.Success(
            GetRouteConditionsParams(
                origin = origin,
                destination = destination,
                travelMode = mode,
                departAt = departAt
            )
        )
    }

    override suspend fun executeTyped(params: GetRouteConditionsParams): EngineResult<RouteConditionsResult> {
        val route: RoutePlan = when (
            val planned = routingProvider.planRoute(
                RouteRequest(
                    origin = params.origin,
                    destination = params.destination,
                    travelMode = params.travelMode,
                    departAt = params.departAt
                )
            )
        ) {
            is EngineResult.Failure -> return planned
            is EngineResult.Success -> planned.value
        }

        val hoursNeeded = max(
            MIN_FORECAST_HOURS,
            ceil(route.duration.toMinutes() / 60.0).toInt() + FORECAST_PADDING_HOURS
        ).coerceAtMost(MAX_FORECAST_HOURS)

        val forecast = when (
            val result = weatherRepository.getHourlyForecast(params.origin, hoursNeeded)
        ) {
            is EngineResult.Failure -> return result
            is EngineResult.Success -> result.value
        }

        val samples = route.geometry.mapIndexedNotNull { index, point ->
            val fraction = if (route.geometry.size == 1) 0.0 else index.toDouble() / (route.geometry.size - 1)
            val eta = params.departAt.plusMillis((route.duration.toMillis() * fraction).toLong())
            val forecastPoint = nearestForecastPoint(forecast.data, eta, MAX_FORECAST_SNAP_MINUTES)
                ?: return@mapIndexedNotNull null
            sampleAt(fraction, point, eta, forecastPoint)
        }

        if (samples.isEmpty()) {
            return EngineResult.Failure(
                EngineError.UnsupportedCalculation(
                    methodId = "get_route_conditions",
                    reason = "INSUFFICIENT_FORECAST_COVERAGE",
                    message = "The forecast does not cover any sampled arrival time on this route " +
                        "(${params.departAt} + ${route.duration.toMinutes()} minutes)."
                )
            )
        }

        val worst = samples
            .mapNotNull { sample -> sample.category?.let { NwsHeatIndexCategory.valueOf(it) } }
            .maxByOrNull { it.severityRank }

        return EngineResult.Success(
            RouteConditionsResult(
                distanceKm = route.distanceKm,
                durationMinutes = route.duration.toMinutes(),
                travelMode = params.travelMode,
                routeMethod = route.method,
                departAt = params.departAt,
                samples = samples,
                worstCategory = worst?.name,
                advisories = advisoriesFor(worst),
                limitations = buildList {
                    addAll(route.limitations)
                    add(
                        "Conditions are sampled from the origin's hourly forecast grid, not from a " +
                            "spatial route field; cross-city gradients are not resolved."
                    )
                    add("Arrival times are estimates, so the sampled hour may differ from the real arrival hour.")
                    add("Heat Index describes ambient conditions and is not a medical exposure limit.")
                },
                forecastFreshness = forecast.freshness
            )
        )
    }

    override fun encodeResult(result: RouteConditionsResult): JsonValue.ObjectValue {
        val sampleJson = result.samples.map { sample ->
            JsonValue.ObjectValue(
                linkedMapOf<String, JsonValue>().apply {
                    put("fraction", SafeJsonCodec.number(round(sample.fraction)))
                    put("latitude", SafeJsonCodec.number(sample.location.latitude))
                    put("longitude", SafeJsonCodec.number(sample.location.longitude))
                    put("eta", JsonValue.StringValue(sample.eta.toString()))
                    put("forecast_valid_at", JsonValue.StringValue(sample.forecastValidAt.toString()))
                    sample.heatIndexCelsius?.let { put("heat_index_celsius", SafeJsonCodec.number(round(it))) }
                    sample.category?.let { put("category", JsonValue.StringValue(it)) }
                    sample.airTemperatureCelsius?.let { put("air_temperature_celsius", SafeJsonCodec.number(round(it))) }
                    sample.relativeHumidityPercent?.let { put("relative_humidity_percent", SafeJsonCodec.number(round(it))) }
                    sample.uvIndex?.let { put("uv_index", SafeJsonCodec.number(round(it))) }
                }
            )
        }
        return JsonValue.ObjectValue(
            linkedMapOf<String, JsonValue>().apply {
                put("status", JsonValue.StringValue("EVALUATED"))
                put("data_nature", JsonValue.StringValue(ValueNature.FORECAST.name))
                put("distance_km", SafeJsonCodec.number(round(result.distanceKm)))
                put("duration_minutes", SafeJsonCodec.number(result.durationMinutes))
                put("travel_mode", JsonValue.StringValue(result.travelMode.name))
                put("route_method", JsonValue.StringValue(result.routeMethod))
                put("depart_at", JsonValue.StringValue(result.departAt.toString()))
                put("samples", JsonValue.ArrayValue(sampleJson))
                result.worstCategory?.let { put("worst_category", JsonValue.StringValue(it)) }
                put("advisories", JsonValue.ArrayValue(result.advisories.map(JsonValue::StringValue)))
                put("freshness", ToolEncodingSupport.encodeFreshness(result.forecastFreshness))
                put("limitations", JsonValue.ArrayValue(result.limitations.map(JsonValue::StringValue)))
            }
        )
    }

    private fun sampleAt(
        fraction: Double,
        location: GeoLocation,
        eta: Instant,
        point: HourlyForecastPoint
    ): RouteSampleConditions {
        val quality = DataQualityAndFreshness.evaluateForecast(
            retrievedAt = point.issuedOrRetrievedAt,
            evaluatedAt = clock.instant(),
            anchorTime = point.validAt,
            deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
            completenessRatio = point.variables.completenessRatio()
        )
        val assessment = assessmentEngine.assessSnapshot(
            location = location,
            validAt = point.validAt,
            inputNature = ValueNature.FORECAST,
            inputSource = point.source,
            variables = point.variables,
            quality = quality
        )
        return RouteSampleConditions(
            fraction = fraction,
            location = location,
            eta = eta,
            forecastValidAt = point.validAt,
            heatIndexCelsius = (assessment as? HeatAssessment.Supported)?.heatIndexCelsius?.value,
            category = (assessment as? HeatAssessment.Supported)?.category?.name,
            airTemperatureCelsius = point.variables.airTemperature.rawValueOrNull(),
            relativeHumidityPercent = point.variables.relativeHumidity.rawValueOrNull(),
            uvIndex = point.variables.uvIndex.rawValueOrNull()
        )
    }

    private fun advisoriesFor(worst: NwsHeatIndexCategory?): List<String> = when (worst) {
        null -> listOf("Heat Index could not be computed for the sampled hours; rely on the raw variables only.")
        NwsHeatIndexCategory.BELOW_CAUTION_THRESHOLD -> listOf(
            "Heat Index stays below the NWS caution threshold for the sampled hours."
        )
        NwsHeatIndexCategory.CAUTION -> listOf(
            "Fatigue is possible with prolonged exposure; plan shade and water breaks."
        )
        NwsHeatIndexCategory.EXTREME_CAUTION -> listOf(
            "Heat cramps or exhaustion are possible with prolonged exposure; shorten exposure and carry water."
        )
        NwsHeatIndexCategory.DANGER -> listOf(
            "Heat cramps or heat exhaustion are likely and heat stroke is possible; avoid or reschedule this route."
        )
        NwsHeatIndexCategory.EXTREME_DANGER -> listOf(
            "Heat stroke is highly likely with continued exposure; do not undertake this route without a compelling reason."
        )
    }

    companion object {
        const val MIN_ROUTE_KM = 0.05
        const val MIN_FORECAST_HOURS = 6
        const val MAX_FORECAST_HOURS = 168
        const val FORECAST_PADDING_HOURS = 2
        const val MAX_FORECAST_SNAP_MINUTES = 90L
    }
}

// ============================================================================
// 10. get_heat_map
// ============================================================================

data class GetHeatMapParams(
    val center: GeoLocation,
    val radiusKm: Double,
    val resolutionKm: Double,
    val validAt: Instant,
    val metric: HeatMapMetric
)

class GetHeatMapTool(
    private val heatMapProvider: HeatMapProvider,
    private val clock: Clock = Clock.systemUTC()
) : HydroTool<GetHeatMapParams, HeatMapLayer> {

    override val descriptor = ToolDescriptor(
        name = "get_heat_map",
        description = "Return a gridded heat-map layer with its resolution, valid timestamp, legend, " +
            "and provenance. Fails rather than interpolating a map Hydro cannot source.",
        requiredCapabilities = setOf(ToolCapability.HEAT_MAP_READ),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("latitude", ToolSchemaType.NUMBER, "Map centre latitude [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("longitude", ToolSchemaType.NUMBER, "Map centre longitude [-180, 180].", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("radius_km", ToolSchemaType.NUMBER, "Half-width of the requested square in km [1, 200].", required = false, minimum = 1.0, maximum = 200.0),
                ToolPropertySchema("resolution_km", ToolSchemaType.NUMBER, "Grid cell size in km [1, 50].", required = false, minimum = 1.0, maximum = 50.0),
                ToolPropertySchema("valid_at", ToolSchemaType.STRING, "ISO-8601 target time; defaults to now.", required = false, maxLength = 40),
                ToolPropertySchema("metric", ToolSchemaType.ENUM, "Layer metric.", required = false, enumValues = HeatMapMetric.entries.map { it.name }),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Optional IANA timezone ID.", required = false, maxLength = 64)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("metric", ToolSchemaType.STRING, "Layer metric."),
                ToolPropertySchema("valid_at", ToolSchemaType.STRING, "ISO-8601 layer valid time."),
                ToolPropertySchema("resolution_km", ToolSchemaType.NUMBER, "Grid cell size."),
                ToolPropertySchema("cell_count", ToolSchemaType.INTEGER, "Number of returned grid cells."),
                ToolPropertySchema("bounds", ToolSchemaType.OBJECT, "Requested square bounds."),
                ToolPropertySchema("cells", ToolSchemaType.ARRAY, "Grid cells with values.", arrayItemType = ToolSchemaType.OBJECT),
                ToolPropertySchema("legend", ToolSchemaType.ARRAY, "Legend metadata for rendering.", arrayItemType = ToolSchemaType.OBJECT),
                ToolPropertySchema("source", ToolSchemaType.OBJECT, "Layer provenance."),
                ToolPropertySchema("limitations", ToolSchemaType.ARRAY, "Explicit layer limitations.", arrayItemType = ToolSchemaType.STRING)
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(30),
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "UNSUPPORTED_CALCULATION", "PROVIDER_ERROR", "TOOL_TIMEOUT")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<GetHeatMapParams> {
        val center = when (val parsed = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> return parsed
            is EngineResult.Success -> parsed.value
        }
        val radius = rawArgs.getDouble("radius_km") ?: 25.0
        val resolution = rawArgs.getDouble("resolution_km") ?: 5.0
        if (radius !in 1.0..200.0) {
            return EngineResult.Failure(
                EngineError.InvalidInput("radius_km", radius.toString(), "radius_km must be between 1 and 200.")
            )
        }
        if (resolution !in 1.0..50.0) {
            return EngineResult.Failure(
                EngineError.InvalidInput("resolution_km", resolution.toString(), "resolution_km must be between 1 and 50.")
            )
        }
        val cellsPerSide = (2.0 * radius / resolution).toInt() + 1
        if (cellsPerSide * cellsPerSide > MAX_GRID_CELLS) {
            return EngineResult.Failure(
                EngineError.InvalidInput(
                    "resolution_km",
                    resolution.toString(),
                    "Requested grid would contain ${cellsPerSide * cellsPerSide} cells; the limit is " +
                        "$MAX_GRID_CELLS. Increase resolution_km or reduce radius_km."
                )
            )
        }
        val validAt = rawArgs.getString("valid_at")?.let {
            when (val parsed = ToolEncodingSupport.parseInstant(it, "valid_at")) {
                is EngineResult.Failure -> return parsed
                is EngineResult.Success -> parsed.value
            }
        } ?: clock.instant()
        val metric = rawArgs.getString("metric")?.let { HeatMapMetric.valueOf(it) }
            ?: HeatMapMetric.HEAT_INDEX_CELSIUS

        return EngineResult.Success(
            GetHeatMapParams(
                center = center,
                radiusKm = radius,
                resolutionKm = resolution,
                validAt = validAt,
                metric = metric
            )
        )
    }

    override suspend fun executeTyped(params: GetHeatMapParams): EngineResult<HeatMapLayer> =
        heatMapProvider.heatMap(
            HeatMapRequest(
                center = params.center,
                radiusKm = params.radiusKm,
                resolutionKm = params.resolutionKm,
                validAt = params.validAt,
                metric = params.metric
            )
        )

    override fun encodeResult(result: HeatMapLayer): JsonValue.ObjectValue {
        val cells = result.cells.map { cell ->
            JsonValue.ObjectValue(
                linkedMapOf<String, JsonValue>().apply {
                    put("latitude", SafeJsonCodec.number(cell.location.latitude))
                    put("longitude", SafeJsonCodec.number(cell.location.longitude))
                    put("value", SafeJsonCodec.number(round(cell.value)))
                    cell.category?.let { put("category", JsonValue.StringValue(it)) }
                }
            )
        }
        val legend = result.legend.map { entry ->
            JsonValue.ObjectValue(
                linkedMapOf<String, JsonValue>().apply {
                    put("label", JsonValue.StringValue(entry.label))
                    put("min_value", SafeJsonCodec.number(entry.minValue))
                    entry.maxValueExclusive?.let { put("max_value_exclusive", SafeJsonCodec.number(it)) }
                    put("color_hex", JsonValue.StringValue(entry.colorHex))
                }
            )
        }
        val latitudes = result.cells.map { it.location.latitude }
        val longitudes = result.cells.map { it.location.longitude }
        return JsonValue.ObjectValue(
            linkedMapOf<String, JsonValue>().apply {
                put("data_nature", JsonValue.StringValue(ValueNature.FORECAST.name))
                put("metric", JsonValue.StringValue(result.metric.name))
                put("unit", JsonValue.StringValue(result.metric.canonicalUnit))
                put("valid_at", JsonValue.StringValue(result.validAt.toString()))
                put("issued_or_retrieved_at", JsonValue.StringValue(result.issuedOrRetrievedAt.toString()))
                put("resolution_km", SafeJsonCodec.number(result.resolutionKm))
                put("cell_count", SafeJsonCodec.number(result.cells.size))
                put(
                    "bounds",
                    JsonValue.ObjectValue(
                        linkedMapOf(
                            "min_latitude" to SafeJsonCodec.number(latitudes.min()),
                            "max_latitude" to SafeJsonCodec.number(latitudes.max()),
                            "min_longitude" to SafeJsonCodec.number(longitudes.min()),
                            "max_longitude" to SafeJsonCodec.number(longitudes.max())
                        )
                    )
                )
                put("cells", JsonValue.ArrayValue(cells))
                put("legend", JsonValue.ArrayValue(legend))
                put("source", ToolEncodingSupport.encodeSource(result.source))
                put("limitations", JsonValue.ArrayValue(result.limitations.map(JsonValue::StringValue)))
            }
        )
    }

    companion object {
        const val MAX_GRID_CELLS = 400
    }
}

// ============================================================================
// Shared helpers
// ============================================================================

private fun nearestForecastPoint(
    forecast: WeatherForecast,
    instant: Instant,
    toleranceMinutes: Long
): HourlyForecastPoint? =
    forecast.hourly
        .minByOrNull { abs(Duration.between(it.validAt, instant).toMinutes()) }
        ?.takeIf { abs(Duration.between(it.validAt, instant).toMinutes()) <= toleranceMinutes }

private fun round(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0

private fun signed(value: Double): String = if (value >= 0) "+${round(value)}" else round(value).toString()
