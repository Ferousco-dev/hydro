package com.ferousco.hydro.core.tools

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.error.flatMap
import com.ferousco.hydro.core.error.map
import com.ferousco.hydro.core.history.EnvironmentalHistoryRepository
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.ActivityIntensity
import com.ferousco.hydro.core.model.ActivityPlanningOutcome
import com.ferousco.hydro.core.model.ActivityPlanningRequest
import com.ferousco.hydro.core.model.CandidateWindowEvaluation
import com.ferousco.hydro.core.model.DataAcquisitionMethod
import com.ferousco.hydro.core.model.DataQualityAndFreshness
import com.ferousco.hydro.core.model.DeliveryOrigin
import com.ferousco.hydro.core.model.EnvironmentalDataSource
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.HistoricalComparisonOutcome
import com.ferousco.hydro.core.model.HistoricalComparisonPolicy
import com.ferousco.hydro.core.model.HistoricalRecordDataType
import com.ferousco.hydro.core.model.HistoricalWeatherSeries
import com.ferousco.hydro.core.model.IrradianceUnit
import com.ferousco.hydro.core.model.MeasuredQuantity
import com.ferousco.hydro.core.model.PercentageUnit
import com.ferousco.hydro.core.model.PhysicalUnit
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.UnavailableReason
import com.ferousco.hydro.core.model.UvIndexUnit
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.WeatherForecast
import com.ferousco.hydro.core.planning.ActivityPlanningService
import com.ferousco.hydro.core.provider.CachePolicy
import com.ferousco.hydro.core.provider.WeatherRepository
import com.ferousco.hydro.core.provider.WeatherRetrievalEnvelope
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// ============================================================================
// 1. get_current_weather
// ============================================================================

data class GetCurrentWeatherParams(
    val location: GeoLocation,
    val allowCacheFallback: Boolean
)

class GetCurrentWeatherTool(
    private val weatherRepository: WeatherRepository
) : HydroTool<GetCurrentWeatherParams, WeatherRetrievalEnvelope<EnvironmentalObservation>> {

    override val descriptor = ToolDescriptor(
        name = "get_current_weather",
        description = "Retrieve normalized current environmental observation for a WGS84 coordinate, preserving provenance and freshness.",
        requiredCapabilities = setOf(ToolCapability.WEATHER_PROVIDER_READ),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("latitude", ToolSchemaType.NUMBER, "Latitude in degrees [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("longitude", ToolSchemaType.NUMBER, "Longitude in degrees [-180, 180].", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Optional IANA timezone ID (e.g. 'Africa/Lagos').", required = false, maxLength = 64),
                ToolPropertySchema("allow_cache_fallback", ToolSchemaType.BOOLEAN, "Whether cached data may be returned if live network is unavailable.", required = false)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("data_nature", ToolSchemaType.STRING, "Always OBSERVED for observations."),
                ToolPropertySchema("observation_kind", ToolSchemaType.STRING, "Physical or model assimilation observation kind."),
                ToolPropertySchema("observed_at", ToolSchemaType.STRING, "ISO-8601 observation timestamp."),
                ToolPropertySchema("freshness", ToolSchemaType.OBJECT, "Freshness and delivery-origin metadata."),
                ToolPropertySchema("source", ToolSchemaType.OBJECT, "Provider, dataset, and license provenance."),
                ToolPropertySchema("variables", ToolSchemaType.OBJECT, "Normalized environmental variables and availability.")
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(15),
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "NETWORK_UNAVAILABLE", "PROVIDER_ERROR", "INVALID_PROVIDER_RESPONSE", "TOOL_TIMEOUT")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<GetCurrentWeatherParams> {
        val loc = when (val parsedLoc = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> return parsedLoc
            is EngineResult.Success -> parsedLoc.value
        }
        val allowCache = rawArgs.getBoolean("allow_cache_fallback") ?: true
        return EngineResult.Success(GetCurrentWeatherParams(loc, allowCache))
    }

    override suspend fun executeTyped(
        params: GetCurrentWeatherParams
    ): EngineResult<WeatherRetrievalEnvelope<EnvironmentalObservation>> {
        val policy = if (params.allowCacheFallback) {
            CachePolicy.PREFER_FRESH_FALLBACK_CACHE
        } else {
            CachePolicy.NETWORK_ONLY
        }
        return weatherRepository.getCurrentObservation(params.location, policy)
    }

    override fun encodeResult(
        result: WeatherRetrievalEnvelope<EnvironmentalObservation>
    ): JsonValue.ObjectValue {
        val obs = result.data
        return JsonValue.ObjectValue(
            linkedMapOf(
                "data_nature" to JsonValue.StringValue(ValueNature.OBSERVED.name),
                "observation_kind" to JsonValue.StringValue(obs.observationKind.name),
                "observed_at" to JsonValue.StringValue(obs.observedAt.toString()),
                "timezone" to JsonValue.StringValue(obs.timeZone.id),
                "freshness" to ToolEncodingSupport.encodeFreshness(result.freshness),
                "source" to ToolEncodingSupport.encodeSource(obs.source),
                "variables" to ToolEncodingSupport.encodeVariables(obs.variables)
            )
        )
    }
}

// ============================================================================
// 2. get_hourly_forecast
// ============================================================================

data class GetHourlyForecastParams(
    val location: GeoLocation,
    val forecastHours: Int,
    val allowCacheFallback: Boolean
)

class GetHourlyForecastTool(
    private val weatherRepository: WeatherRepository
) : HydroTool<GetHourlyForecastParams, WeatherRetrievalEnvelope<WeatherForecast>> {

    override val descriptor = ToolDescriptor(
        name = "get_hourly_forecast",
        description = "Retrieve normalized hourly weather forecast for up to 168 hours, strictly marked as FORECAST.",
        requiredCapabilities = setOf(ToolCapability.WEATHER_PROVIDER_READ),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("latitude", ToolSchemaType.NUMBER, "Latitude in degrees [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("longitude", ToolSchemaType.NUMBER, "Longitude in degrees [-180, 180].", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("forecast_hours", ToolSchemaType.INTEGER, "Number of forecast hours [1, 168].", required = false, minimum = 1.0, maximum = 168.0),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Optional IANA timezone ID.", required = false, maxLength = 64),
                ToolPropertySchema("allow_cache_fallback", ToolSchemaType.BOOLEAN, "Whether cached forecast may be returned on live network failure.", required = false)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("data_nature", ToolSchemaType.STRING, "Always FORECAST."),
                ToolPropertySchema("issued_or_retrieved_at", ToolSchemaType.STRING, "ISO-8601 retrieval timestamp."),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Resolved IANA timezone."),
                ToolPropertySchema("freshness", ToolSchemaType.OBJECT, "Freshness and delivery-origin metadata."),
                ToolPropertySchema("source", ToolSchemaType.OBJECT, "Provider provenance."),
                ToolPropertySchema("hourly", ToolSchemaType.ARRAY, "Hourly forecast entries.", arrayItemType = ToolSchemaType.OBJECT)
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(15),
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "NETWORK_UNAVAILABLE", "PROVIDER_ERROR", "INVALID_PROVIDER_RESPONSE", "TOOL_TIMEOUT")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<GetHourlyForecastParams> {
        val loc = when (val parsedLoc = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> return parsedLoc
            is EngineResult.Success -> parsedLoc.value
        }
        val hours = rawArgs.getInt("forecast_hours") ?: 24
        if (hours !in 1..168) {
            return EngineResult.Failure(
                EngineError.InvalidInput("forecast_hours", hours.toString(), "forecast_hours must be between 1 and 168.")
            )
        }
        val allowCache = rawArgs.getBoolean("allow_cache_fallback") ?: true
        return EngineResult.Success(GetHourlyForecastParams(loc, hours, allowCache))
    }

    override suspend fun executeTyped(
        params: GetHourlyForecastParams
    ): EngineResult<WeatherRetrievalEnvelope<WeatherForecast>> {
        val policy = if (params.allowCacheFallback) CachePolicy.PREFER_FRESH_FALLBACK_CACHE else CachePolicy.NETWORK_ONLY
        return weatherRepository.getHourlyForecast(params.location, params.forecastHours, policy)
    }

    override fun encodeResult(
        result: WeatherRetrievalEnvelope<WeatherForecast>
    ): JsonValue.ObjectValue {
        val fcst = result.data
        val hourlyItems = fcst.hourly.map { pt ->
            JsonValue.ObjectValue(
                linkedMapOf(
                    "valid_at" to JsonValue.StringValue(pt.validAt.toString()),
                    "local_time" to JsonValue.StringValue(pt.localDateTime.toString()),
                    "variables" to ToolEncodingSupport.encodeVariables(pt.variables)
                )
            )
        }
        return JsonValue.ObjectValue(
            linkedMapOf(
                "data_nature" to JsonValue.StringValue(ValueNature.FORECAST.name),
                "issued_or_retrieved_at" to JsonValue.StringValue(fcst.issuedOrRetrievedAt.toString()),
                "timezone" to JsonValue.StringValue(fcst.timeZone.id),
                "freshness" to ToolEncodingSupport.encodeFreshness(result.freshness),
                "source" to ToolEncodingSupport.encodeSource(fcst.source),
                "hourly" to JsonValue.ArrayValue(hourlyItems)
            )
        )
    }
}

// ============================================================================
// 3. get_historical_weather
// ============================================================================

data class GetHistoricalWeatherParams(
    val location: GeoLocation,
    val startDate: LocalDate,
    val endDate: LocalDate
)

class GetHistoricalWeatherTool(
    private val weatherRepository: WeatherRepository,
    private val clock: Clock = Clock.systemUTC()
) : HydroTool<GetHistoricalWeatherParams, WeatherRetrievalEnvelope<HistoricalWeatherSeries>> {

    override val descriptor = ToolDescriptor(
        name = "get_historical_weather",
        description = "Retrieve archived historical weather reanalysis observations for a bounded past date range (max 31 days per tool call).",
        requiredCapabilities = setOf(ToolCapability.WEATHER_PROVIDER_READ),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("latitude", ToolSchemaType.NUMBER, "Latitude in degrees [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("longitude", ToolSchemaType.NUMBER, "Longitude in degrees [-180, 180].", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("start_date", ToolSchemaType.STRING, "Start date in ISO-8601 YYYY-MM-DD format.", required = true, maxLength = 10),
                ToolPropertySchema("end_date", ToolSchemaType.STRING, "End date in ISO-8601 YYYY-MM-DD format.", required = true, maxLength = 10),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Optional IANA timezone ID.", required = false, maxLength = 64)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("data_nature", ToolSchemaType.STRING, "Always OBSERVED (historical reanalysis)."),
                ToolPropertySchema("observation_kind", ToolSchemaType.STRING, "HISTORICAL_REANALYSIS_OBSERVATION."),
                ToolPropertySchema("start_date", ToolSchemaType.STRING, "Start date."),
                ToolPropertySchema("end_date", ToolSchemaType.STRING, "End date."),
                ToolPropertySchema("hour_count", ToolSchemaType.INTEGER, "Number of hourly observations returned."),
                ToolPropertySchema("freshness", ToolSchemaType.OBJECT, "Quality metadata."),
                ToolPropertySchema("source", ToolSchemaType.OBJECT, "Provider provenance."),
                ToolPropertySchema("hourly_observations", ToolSchemaType.ARRAY, "Hourly historical observations.", arrayItemType = ToolSchemaType.OBJECT)
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(20),
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "NETWORK_UNAVAILABLE", "PROVIDER_ERROR", "INVALID_PROVIDER_RESPONSE", "TOOL_TIMEOUT")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<GetHistoricalWeatherParams> {
        val loc = when (val parsedLoc = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> return parsedLoc
            is EngineResult.Success -> parsedLoc.value
        }
        val startStr = rawArgs.getString("start_date")!!
        val endStr = rawArgs.getString("end_date")!!
        val startDate = try {
            LocalDate.parse(startStr)
        } catch (_: Exception) {
            return EngineResult.Failure(EngineError.InvalidInput("start_date", startStr, "Invalid ISO date for start_date; expected YYYY-MM-DD."))
        }
        val endDate = try {
            LocalDate.parse(endStr)
        } catch (_: Exception) {
            return EngineResult.Failure(EngineError.InvalidInput("end_date", endStr, "Invalid ISO date for end_date; expected YYYY-MM-DD."))
        }
        if (endDate.isBefore(startDate)) {
            return EngineResult.Failure(EngineError.InvalidInput("end_date", endStr, "end_date cannot precede start_date."))
        }
        val spanDays = ChronoUnit.DAYS.between(startDate, endDate) + 1
        if (spanDays > MAX_TOOL_HISTORICAL_DAYS) {
            return EngineResult.Failure(
                EngineError.InvalidInput("end_date", "${spanDays}d", "Tool historical window cannot exceed $MAX_TOOL_HISTORICAL_DAYS days.")
            )
        }
        val today = LocalDate.ofInstant(clock.instant(), loc.timeZone ?: ZoneOffset.UTC)
        if (endDate.isAfter(today)) {
            return EngineResult.Failure(
                EngineError.InvalidInput("end_date", endStr, "end_date ($endDate) cannot be in the future relative to $today.")
            )
        }
        return EngineResult.Success(GetHistoricalWeatherParams(loc, startDate, endDate))
    }

    override suspend fun executeTyped(
        params: GetHistoricalWeatherParams
    ): EngineResult<WeatherRetrievalEnvelope<HistoricalWeatherSeries>> =
        weatherRepository.getHistoricalWeather(params.location, params.startDate, params.endDate)

    override fun encodeResult(
        result: WeatherRetrievalEnvelope<HistoricalWeatherSeries>
    ): JsonValue.ObjectValue {
        val series = result.data
        val items = series.hourlyObservations.map { obs ->
            JsonValue.ObjectValue(
                linkedMapOf(
                    "observed_at" to JsonValue.StringValue(obs.observedAt.toString()),
                    "variables" to ToolEncodingSupport.encodeVariables(obs.variables)
                )
            )
        }
        return JsonValue.ObjectValue(
            linkedMapOf(
                "data_nature" to JsonValue.StringValue(ValueNature.OBSERVED.name),
                "observation_kind" to JsonValue.StringValue("HISTORICAL_REANALYSIS_OBSERVATION"),
                "start_date" to JsonValue.StringValue(series.startDate.toString()),
                "end_date" to JsonValue.StringValue(series.endDate.toString()),
                "hour_count" to SafeJsonCodec.number(series.hourlyObservations.size),
                "freshness" to ToolEncodingSupport.encodeFreshness(result.freshness),
                "source" to ToolEncodingSupport.encodeSource(series.source),
                "hourly_observations" to JsonValue.ArrayValue(items)
            )
        )
    }

    companion object {
        const val MAX_TOOL_HISTORICAL_DAYS = 31L
    }
}

// ============================================================================
// 4. calculate_heat_assessment
// ============================================================================

data class CalculateHeatAssessmentParams(
    val location: GeoLocation,
    val validAt: Instant,
    val retrievedAt: Instant,
    val inputNature: ValueNature,
    val variables: EnvironmentalSnapshotVariables,
    val allowStaleInput: Boolean
)

class CalculateHeatAssessmentTool(
    private val assessmentEngine: HeatAssessmentEngine,
    private val clock: Clock = Clock.systemUTC()
) : HydroTool<CalculateHeatAssessmentParams, HeatAssessment> {

    override val descriptor = ToolDescriptor(
        name = "calculate_heat_assessment",
        description = "Deterministically calculate NOAA/NWS Heat Index (SR 90-23) and optional WHO UV category from validated environmental inputs.",
        requiredCapabilities = setOf(ToolCapability.DETERMINISTIC_ANALYSIS),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("latitude", ToolSchemaType.NUMBER, "Latitude in degrees [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("longitude", ToolSchemaType.NUMBER, "Longitude in degrees [-180, 180].", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("valid_at", ToolSchemaType.STRING, "ISO-8601 instant when measurement/forecast applies.", required = true, maxLength = 40),
                ToolPropertySchema("data_nature", ToolSchemaType.ENUM, "Whether inputs are OBSERVED or FORECAST.", required = true, enumValues = listOf("OBSERVED", "FORECAST")),
                ToolPropertySchema("air_temperature_celsius", ToolSchemaType.NUMBER, "Dry-bulb air temperature in °C (omit if unavailable).", required = false, minimum = -95.0, maximum = 65.0),
                ToolPropertySchema("relative_humidity_percent", ToolSchemaType.NUMBER, "Relative humidity in % [0, 100] (omit if unavailable).", required = false, minimum = 0.0, maximum = 100.0),
                ToolPropertySchema("wind_speed_ms", ToolSchemaType.NUMBER, "Optional 10m wind speed in m/s.", required = false, minimum = 0.0, maximum = 150.0),
                ToolPropertySchema("uv_index", ToolSchemaType.NUMBER, "Optional WHO UV index [0, 25].", required = false, minimum = 0.0, maximum = 25.0),
                ToolPropertySchema("solar_radiation_wm2", ToolSchemaType.NUMBER, "Optional shortwave solar radiation in W/m².", required = false, minimum = 0.0, maximum = 2000.0),
                ToolPropertySchema("retrieved_at", ToolSchemaType.STRING, "Optional ISO-8601 retrieval timestamp.", required = false, maxLength = 40),
                ToolPropertySchema("allow_stale_input", ToolSchemaType.BOOLEAN, "Whether stale inputs may be assessed with stale warnings.", required = false)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("status", ToolSchemaType.STRING, "SUPPORTED, INCOMPLETE, or UNSUPPORTED."),
                ToolPropertySchema("method", ToolSchemaType.OBJECT, "Authoritative calculation method citation."),
                ToolPropertySchema("input_nature", ToolSchemaType.STRING, "OBSERVED or FORECAST."),
                ToolPropertySchema("category", ToolSchemaType.STRING, "NWS Heat Index category when status=SUPPORTED.", required = false),
                ToolPropertySchema("heat_index_celsius", ToolSchemaType.NUMBER, "Derived Heat Index in °C when status=SUPPORTED.", required = false),
                ToolPropertySchema("explanation", ToolSchemaType.STRING, "Grounded deterministic explanation."),
                ToolPropertySchema("limitations", ToolSchemaType.ARRAY, "Documented scientific limitations.", arrayItemType = ToolSchemaType.STRING)
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(5),
            mode = ToolFailureMode.STRUCTURED_ERROR_NON_RETRYABLE_BY_DEFAULT,
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<CalculateHeatAssessmentParams> {
        val loc = when (val parsedLoc = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> return parsedLoc
            is EngineResult.Success -> parsedLoc.value
        }
        val validAt = when (val v = ToolEncodingSupport.parseInstant(rawArgs.getString("valid_at")!!, "valid_at")) {
            is EngineResult.Failure -> return v
            is EngineResult.Success -> v.value
        }
        val retrievedAt = rawArgs.getString("retrieved_at")?.let {
            when (val r = ToolEncodingSupport.parseInstant(it, "retrieved_at")) {
                is EngineResult.Failure -> return r
                is EngineResult.Success -> r.value
            }
        } ?: clock.instant()

        val nature = ValueNature.valueOf(rawArgs.getString("data_nature")!!)
        val source = EnvironmentalDataSource(
            providerId = "tool-supplied-input",
            providerName = "Validated Tool Input",
            endpointOrDataset = "calculate_heat_assessment",
            acquisitionMethod = if (nature == ValueNature.FORECAST) {
                DataAcquisitionMethod.NUMERICAL_WEATHER_FORECAST
            } else {
                DataAcquisitionMethod.STATION_MEASUREMENT
            },
            license = "Caller supplied",
            attribution = "Caller supplied"
        )

        fun <U : PhysicalUnit> optionalMetric(key: String, unit: U): FieldAvailability<U> {
            val v = rawArgs.getDouble(key) ?: return FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
            return FieldAvailability.Available(
                MeasuredQuantity(v, unit, validAt, loc, source, nature)
            )
        }

        val vars = EnvironmentalSnapshotVariables(
            airTemperature = optionalMetric("air_temperature_celsius", TemperatureUnit.CELSIUS),
            relativeHumidity = optionalMetric("relative_humidity_percent", PercentageUnit.PERCENT),
            windSpeed = optionalMetric("wind_speed_ms", SpeedUnit.METERS_PER_SECOND),
            feelsLikeTemperature = FieldAvailability.Unavailable(UnavailableReason.NOT_REQUESTED),
            uvIndex = optionalMetric("uv_index", UvIndexUnit.WHO_INDEX),
            solarRadiation = optionalMetric("solar_radiation_wm2", IrradianceUnit.WATTS_PER_SQUARE_METER)
        )

        return EngineResult.Success(
            CalculateHeatAssessmentParams(
                location = loc,
                validAt = validAt,
                retrievedAt = retrievedAt,
                inputNature = nature,
                variables = vars,
                allowStaleInput = rawArgs.getBoolean("allow_stale_input") ?: false
            )
        )
    }

    override suspend fun executeTyped(params: CalculateHeatAssessmentParams): EngineResult<HeatAssessment> {
        val now = clock.instant()
        val quality = if (params.inputNature == ValueNature.FORECAST) {
            DataQualityAndFreshness.evaluateForecast(
                retrievedAt = params.retrievedAt,
                evaluatedAt = now,
                anchorTime = params.validAt,
                deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
                completenessRatio = params.variables.completenessRatio()
            )
        } else {
            DataQualityAndFreshness.evaluateCurrentObservation(
                retrievedAt = params.retrievedAt,
                evaluatedAt = now,
                observedAt = params.validAt,
                deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
                completenessRatio = params.variables.completenessRatio()
            )
        }
        val assessment = assessmentEngine.assessSnapshot(
            location = params.location,
            validAt = params.validAt,
            inputNature = params.inputNature,
            inputSource = EnvironmentalDataSource(
                providerId = "tool-supplied-input",
                providerName = "Validated Tool Input",
                endpointOrDataset = "calculate_heat_assessment",
                acquisitionMethod = if (params.inputNature == ValueNature.FORECAST) {
                    DataAcquisitionMethod.NUMERICAL_WEATHER_FORECAST
                } else {
                    DataAcquisitionMethod.STATION_MEASUREMENT
                },
                license = "Caller supplied",
                attribution = "Caller supplied"
            ),
            variables = params.variables,
            quality = quality,
            allowStaleInput = params.allowStaleInput
        )
        return EngineResult.Success(assessment)
    }

    override fun encodeResult(result: HeatAssessment): JsonValue.ObjectValue =
        ToolEncodingSupport.encodeHeatAssessment(result)
}

// ============================================================================
// 5. compare_forecast_periods
// ============================================================================

data class CompareForecastPeriodsParams(
    val location: GeoLocation,
    val activityCategory: ActivityCategory,
    val plannedDuration: Duration,
    val intensity: ActivityIntensity?,
    val earliestDeparture: Instant,
    val latestDeparture: Instant,
    val candidateStep: Duration,
    val forecastHours: Int
)

class CompareForecastPeriodsTool(
    private val weatherRepository: WeatherRepository,
    private val planningService: ActivityPlanningService
) : HydroTool<CompareForecastPeriodsParams, ActivityPlanningOutcome> {

    override val descriptor = ToolDescriptor(
        name = "compare_forecast_periods",
        description = "Compare candidate departure times across an hourly weather forecast window for a planned outdoor activity.",
        requiredCapabilities = setOf(ToolCapability.WEATHER_PROVIDER_READ, ToolCapability.DETERMINISTIC_ANALYSIS),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("latitude", ToolSchemaType.NUMBER, "Latitude in degrees [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("longitude", ToolSchemaType.NUMBER, "Longitude in degrees [-180, 180].", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("activity_category", ToolSchemaType.ENUM, "Activity category.", required = true, enumValues = ActivityCategory.entries.map { it.name }),
                ToolPropertySchema("duration_minutes", ToolSchemaType.INTEGER, "Planned duration in minutes [15, 720].", required = true, minimum = 15.0, maximum = 720.0),
                ToolPropertySchema("intensity", ToolSchemaType.ENUM, "Optional activity intensity.", required = false, enumValues = ActivityIntensity.entries.map { it.name }),
                ToolPropertySchema("earliest_departure", ToolSchemaType.STRING, "ISO-8601 earliest departure timestamp.", required = true, maxLength = 40),
                ToolPropertySchema("latest_departure", ToolSchemaType.STRING, "ISO-8601 latest departure timestamp.", required = true, maxLength = 40),
                ToolPropertySchema("step_minutes", ToolSchemaType.INTEGER, "Candidate step interval in minutes [15, 180].", required = false, minimum = 15.0, maximum = 180.0),
                ToolPropertySchema("forecast_hours", ToolSchemaType.INTEGER, "Forecast horizon in hours [6, 168].", required = false, minimum = 6.0, maximum = 168.0),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Optional IANA timezone ID.", required = false, maxLength = 64)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("status", ToolSchemaType.STRING, "EVALUATED or INSUFFICIENT_DATA."),
                ToolPropertySchema("verdict", ToolSchemaType.STRING, "Relative exposure verdict (never a medical safety guarantee).", required = false),
                ToolPropertySchema("lowest_exposure_departures", ToolSchemaType.ARRAY, "Lowest exposure candidate instants.", required = false, arrayItemType = ToolSchemaType.STRING),
                ToolPropertySchema("candidates", ToolSchemaType.ARRAY, "Evaluated candidate windows.", required = false, arrayItemType = ToolSchemaType.OBJECT),
                ToolPropertySchema("supporting_evidence", ToolSchemaType.ARRAY, "Supporting deterministic evidence.", required = false, arrayItemType = ToolSchemaType.STRING),
                ToolPropertySchema("preparation_advice", ToolSchemaType.ARRAY, "Grounded preparation notes.", required = false, arrayItemType = ToolSchemaType.STRING),
                ToolPropertySchema("limitations", ToolSchemaType.ARRAY, "Explicit planning limitations.", arrayItemType = ToolSchemaType.STRING)
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(15),
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "NETWORK_UNAVAILABLE", "PROVIDER_ERROR", "TOOL_TIMEOUT")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<CompareForecastPeriodsParams> {
        val loc = when (val parsedLoc = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> return parsedLoc
            is EngineResult.Success -> parsedLoc.value
        }
        val category = ActivityCategory.valueOf(rawArgs.getString("activity_category")!!)
        val durationMin = rawArgs.getInt("duration_minutes")!!
        val intensity = rawArgs.getString("intensity")?.let(ActivityIntensity::valueOf)
        val earliest = when (val e = ToolEncodingSupport.parseInstant(rawArgs.getString("earliest_departure")!!, "earliest_departure")) {
            is EngineResult.Failure -> return e
            is EngineResult.Success -> e.value
        }
        val latest = when (val l = ToolEncodingSupport.parseInstant(rawArgs.getString("latest_departure")!!, "latest_departure")) {
            is EngineResult.Failure -> return l
            is EngineResult.Success -> l.value
        }
        if (latest.isBefore(earliest)) {
            return EngineResult.Failure(
                EngineError.InvalidInput("latest_departure", latest.toString(), "latest_departure cannot precede earliest_departure.")
            )
        }
        val windowHours = Duration.between(earliest, latest).toHours()
        if (windowHours > 96) {
            return EngineResult.Failure(
                EngineError.InvalidInput("latest_departure", "${windowHours}h", "Candidate departure span cannot exceed 96 hours.")
            )
        }
        val stepMin = rawArgs.getInt("step_minutes") ?: 60
        val forecastHours = rawArgs.getInt("forecast_hours") ?: 72

        return EngineResult.Success(
            CompareForecastPeriodsParams(
                location = loc,
                activityCategory = category,
                plannedDuration = Duration.ofMinutes(durationMin.toLong()),
                intensity = intensity,
                earliestDeparture = earliest,
                latestDeparture = latest,
                candidateStep = Duration.ofMinutes(stepMin.toLong()),
                forecastHours = forecastHours
            )
        )
    }

    override suspend fun executeTyped(params: CompareForecastPeriodsParams): EngineResult<ActivityPlanningOutcome> {
        return weatherRepository.getHourlyForecast(params.location, params.forecastHours).map { envelope ->
            val request = ActivityPlanningRequest(
                location = params.location,
                activityCategory = params.activityCategory,
                plannedDuration = params.plannedDuration,
                intensity = params.intensity,
                earliestDeparture = params.earliestDeparture,
                latestDeparture = params.latestDeparture,
                candidateStep = params.candidateStep,
                forecast = envelope.data,
                allowStaleForecast = false
            )
            planningService.evaluate(request)
        }
    }

    override fun encodeResult(result: ActivityPlanningOutcome): JsonValue.ObjectValue {
        return when (result) {
            is ActivityPlanningOutcome.InsufficientData -> JsonValue.ObjectValue(
                linkedMapOf(
                    "status" to JsonValue.StringValue("INSUFFICIENT_DATA"),
                    "reason" to JsonValue.StringValue(result.reason),
                    "freshness" to ToolEncodingSupport.encodeFreshness(result.forecastFreshness),
                    "limitations" to JsonValue.ArrayValue(result.limitations.map(JsonValue::StringValue))
                )
            )
            is ActivityPlanningOutcome.Evaluated -> {
                val candidateJson = result.candidates.map { c ->
                    when (c) {
                        is CandidateWindowEvaluation.Complete -> JsonValue.ObjectValue(
                            linkedMapOf(
                                "status" to JsonValue.StringValue("COMPLETE"),
                                "departure_time" to JsonValue.StringValue(c.departureTime.toString()),
                                "local_departure_time" to JsonValue.StringValue(c.localDepartureTime.toString()),
                                "rank" to SafeJsonCodec.number(c.rank),
                                "tied_for_lowest_exposure" to JsonValue.BooleanValue(c.isTiedForLowestExposure),
                                "worst_category" to JsonValue.StringValue(c.worstCategory.name),
                                "peak_heat_index_celsius" to SafeJsonCodec.number(c.peakHeatIndexCelsius),
                                "mean_heat_index_celsius" to SafeJsonCodec.number(c.meanHeatIndexCelsius),
                                "max_air_temperature_celsius" to SafeJsonCodec.number(c.conditions.maxAirTemperatureCelsius)
                            )
                        )
                        is CandidateWindowEvaluation.IncompleteCoverage -> JsonValue.ObjectValue(
                            linkedMapOf(
                                "status" to JsonValue.StringValue("INCOMPLETE_COVERAGE"),
                                "departure_time" to JsonValue.StringValue(c.departureTime.toString()),
                                "reason" to JsonValue.StringValue(c.reason)
                            )
                        )
                    }
                }
                val relativeJson = result.relativeComparisons.map { r ->
                    JsonValue.ObjectValue(
                        linkedMapOf(
                            "candidate_departure" to JsonValue.StringValue(r.candidateDeparture.toString()),
                            "reference_departure" to JsonValue.StringValue(r.referenceLowestExposureDeparture.toString()),
                            "peak_heat_index_delta_celsius" to SafeJsonCodec.number(r.peakHeatIndexDeltaCelsius),
                            "summary" to JsonValue.StringValue(r.summary)
                        )
                    )
                }
                JsonValue.ObjectValue(
                    linkedMapOf(
                        "status" to JsonValue.StringValue("EVALUATED"),
                        "verdict" to JsonValue.StringValue(result.verdict.name),
                        "lowest_exposure_departures" to JsonValue.ArrayValue(
                            result.lowestExposureDepartures.map { JsonValue.StringValue(it.toString()) }
                        ),
                        "candidates" to JsonValue.ArrayValue(candidateJson),
                        "relative_comparisons" to JsonValue.ArrayValue(relativeJson),
                        "freshness" to ToolEncodingSupport.encodeFreshness(result.forecastFreshness),
                        "supporting_evidence" to JsonValue.ArrayValue(result.supportingEvidence.map(JsonValue::StringValue)),
                        "preparation_advice" to JsonValue.ArrayValue(result.preparationAdvice.map(JsonValue::StringValue)),
                        "limitations" to JsonValue.ArrayValue(result.limitations.map(JsonValue::StringValue))
                    )
                )
            }
        }
    }
}

// ============================================================================
// 6. query_local_history
// ============================================================================

data class QueryLocalHistoryParams(
    val location: GeoLocation,
    val radiusKm: Double,
    val dataTypes: Set<HistoricalRecordDataType>,
    val limit: Int,
    val compareBaselineId: String?,
    val compareTargetId: String?,
    val requireObservedBaseline: Boolean
)

data class QueryLocalHistoryResult(
    val records: List<StoredEnvironmentalRecord>,
    val comparison: HistoricalComparisonOutcome?
)

class QueryLocalHistoryTool(
    private val historyRepository: EnvironmentalHistoryRepository
) : HydroTool<QueryLocalHistoryParams, QueryLocalHistoryResult> {

    override val descriptor = ToolDescriptor(
        name = "query_local_history",
        description = "Query permitted local environmental history near a coordinate and optionally compare two compatible stored records.",
        requiredCapabilities = setOf(ToolCapability.LOCAL_HISTORY_READ),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("latitude", ToolSchemaType.NUMBER, "Latitude in degrees [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("longitude", ToolSchemaType.NUMBER, "Longitude in degrees [-180, 180].", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("radius_km", ToolSchemaType.NUMBER, "Search radius in km [0.1, 50.0].", required = false, minimum = 0.1, maximum = 50.0),
                ToolPropertySchema("record_types", ToolSchemaType.ARRAY, "Optional subset of record types (OBSERVATION, HISTORICAL_REANALYSIS_OBSERVATION, ISSUED_FORECAST).", required = false, arrayItemType = ToolSchemaType.STRING, maxItems = 3),
                ToolPropertySchema("limit", ToolSchemaType.INTEGER, "Maximum records to return [1, 50].", required = false, minimum = 1.0, maximum = 50.0),
                ToolPropertySchema("compare_baseline_id", ToolSchemaType.STRING, "Optional baseline record ID for compatible comparison.", required = false, maxLength = 128),
                ToolPropertySchema("compare_target_id", ToolSchemaType.STRING, "Optional target record ID for compatible comparison.", required = false, maxLength = 128),
                ToolPropertySchema("require_observed_baseline", ToolSchemaType.BOOLEAN, "If true, reject comparisons where baseline is an ISSUED_FORECAST.", required = false)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("record_count", ToolSchemaType.INTEGER, "Number of matching local records."),
                ToolPropertySchema("records", ToolSchemaType.ARRAY, "Matched local records.", arrayItemType = ToolSchemaType.OBJECT),
                ToolPropertySchema("comparison", ToolSchemaType.OBJECT, "Optional compatibility-validated comparison result.", required = false)
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(10),
            mode = ToolFailureMode.STRUCTURED_ERROR_NON_RETRYABLE_BY_DEFAULT,
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "PERSISTENCE_FAILURE")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<QueryLocalHistoryParams> {
        val loc = when (val parsedLoc = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> return parsedLoc
            is EngineResult.Success -> parsedLoc.value
        }
        val radiusKm = rawArgs.getDouble("radius_km") ?: 10.0
        val limit = rawArgs.getInt("limit") ?: 20
        val typesArr = rawArgs.getArray("record_types")?.items
        val types = if (typesArr == null || typesArr.isEmpty()) {
            HistoricalRecordDataType.entries.toSet()
        } else {
            val parsed = LinkedHashSet<HistoricalRecordDataType>()
            for (item in typesArr) {
                val rawName = (item as? JsonValue.StringValue)?.value ?: continue
                val dt = HistoricalRecordDataType.entries.firstOrNull { it.name == rawName }
                    ?: return EngineResult.Failure(
                        EngineError.InvalidInput("record_types", rawName, "Unknown record type '$rawName'.")
                    )
                parsed.add(dt)
            }
            parsed
        }

        val baseId = rawArgs.getString("compare_baseline_id")
        val targetId = rawArgs.getString("compare_target_id")
        if ((baseId == null) != (targetId == null)) {
            return EngineResult.Failure(
                EngineError.InvalidInput(
                    "compare_baseline_id",
                    "partial_pair",
                    "Both compare_baseline_id and compare_target_id must be provided together when requesting a comparison."
                )
            )
        }

        return EngineResult.Success(
            QueryLocalHistoryParams(
                location = loc,
                radiusKm = radiusKm,
                dataTypes = types,
                limit = limit,
                compareBaselineId = baseId,
                compareTargetId = targetId,
                requireObservedBaseline = rawArgs.getBoolean("require_observed_baseline") ?: false
            )
        )
    }

    override suspend fun executeTyped(params: QueryLocalHistoryParams): EngineResult<QueryLocalHistoryResult> {
        return historyRepository.queryRecentRecords(
            location = params.location,
            maxDistanceKm = params.radiusKm,
            dataTypes = params.dataTypes,
            limit = params.limit
        ).flatMap { records ->
            if (params.compareBaselineId != null && params.compareTargetId != null) {
                historyRepository.queryRecentRecords(limit = 500).flatMap { pool ->
                    val baseline = pool.firstOrNull { it.id == params.compareBaselineId }
                        ?: return@flatMap EngineResult.Failure(
                            EngineError.InvalidInput("compare_baseline_id", params.compareBaselineId, "Baseline record '${params.compareBaselineId}' not found in local history.")
                        )
                    val target = pool.firstOrNull { it.id == params.compareTargetId }
                        ?: return@flatMap EngineResult.Failure(
                            EngineError.InvalidInput("compare_target_id", params.compareTargetId, "Target record '${params.compareTargetId}' not found in local history.")
                        )
                    val outcome = historyRepository.compareRecords(
                        baseline = baseline,
                        target = target,
                        policy = HistoricalComparisonPolicy(
                            maxDistanceKm = params.radiusKm,
                            requireObservedBaseline = params.requireObservedBaseline
                        )
                    )
                    EngineResult.Success(QueryLocalHistoryResult(records, outcome))
                }
            } else {
                EngineResult.Success(QueryLocalHistoryResult(records, null))
            }
        }
    }

    override fun encodeResult(result: QueryLocalHistoryResult): JsonValue.ObjectValue {
        val recordsJson = result.records.map { rec ->
            JsonValue.ObjectValue(
                linkedMapOf(
                    "id" to JsonValue.StringValue(rec.id),
                    "data_type" to JsonValue.StringValue(rec.dataType.name),
                    "valid_at" to JsonValue.StringValue(rec.validAt.toString()),
                    "retrieved_at" to JsonValue.StringValue(rec.retrievedAt.toString()),
                    "source_provider" to JsonValue.StringValue(rec.source.providerId),
                    "variables" to ToolEncodingSupport.encodeVariables(rec.variables)
                )
            )
        }
        val map = linkedMapOf<String, JsonValue>(
            "record_count" to SafeJsonCodec.number(result.records.size),
            "records" to JsonValue.ArrayValue(recordsJson)
        )
        result.comparison?.let { comp ->
            map["comparison"] = when (comp) {
                is HistoricalComparisonOutcome.Compatible -> JsonValue.ObjectValue(
                    linkedMapOf(
                        "compatible" to JsonValue.BooleanValue(true),
                        "pairing_kind" to JsonValue.StringValue(comp.pairingKind.name),
                        "distance_km" to SafeJsonCodec.number(comp.distanceKm),
                        "summary" to JsonValue.StringValue(comp.summary),
                        "deltas" to JsonValue.ArrayValue(
                            comp.variableDeltas.map { d ->
                                JsonValue.ObjectValue(
                                    linkedMapOf(
                                        "variable" to JsonValue.StringValue(d.variableName),
                                        "unit" to JsonValue.StringValue(d.canonicalUnitSymbol),
                                        "baseline" to SafeJsonCodec.number(d.baselineValue),
                                        "target" to SafeJsonCodec.number(d.targetValue),
                                        "delta" to SafeJsonCodec.number(d.delta)
                                    )
                                )
                            }
                        ),
                        "caveats" to JsonValue.ArrayValue(comp.caveats.map(JsonValue::StringValue))
                    )
                )
                is HistoricalComparisonOutcome.Incompatible -> JsonValue.ObjectValue(
                    linkedMapOf(
                        "compatible" to JsonValue.BooleanValue(false),
                        "reason" to JsonValue.StringValue(comp.reason.name),
                        "explanation" to JsonValue.StringValue(comp.explanation)
                    )
                )
            }
        }
        return JsonValue.ObjectValue(map)
    }
}

// ============================================================================
// Internal JSON Encoding Helpers
// ============================================================================

internal object ToolEncodingSupport {

    fun parseLocation(rawArgs: JsonValue.ObjectValue): EngineResult<GeoLocation> {
        val lat = rawArgs.getDouble("latitude")
            ?: return EngineResult.Failure(EngineError.MissingRequiredInputs("parseLocation", listOf("latitude"), "Missing latitude."))
        val lon = rawArgs.getDouble("longitude")
            ?: return EngineResult.Failure(EngineError.MissingRequiredInputs("parseLocation", listOf("longitude"), "Missing longitude."))
        val tzStr = rawArgs.getString("timezone")
        val tz = if (!tzStr.isNullOrBlank()) {
            try {
                ZoneId.of(tzStr)
            } catch (_: Exception) {
                return EngineResult.Failure(EngineError.InvalidInput("timezone", tzStr, "Unrecognized IANA timezone '$tzStr'."))
            }
        } else {
            null
        }
        return GeoLocation.validated(latitude = lat, longitude = lon, timeZone = tz)
    }

    fun parseInstant(raw: String, fieldName: String): EngineResult<Instant> = try {
        EngineResult.Success(Instant.parse(raw))
    } catch (_: Exception) {
        EngineResult.Failure(EngineError.InvalidInput(fieldName, raw, "Invalid ISO-8601 instant for '$fieldName'."))
    }

    fun encodeFreshness(q: DataQualityAndFreshness): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf(
            "retrieved_at" to JsonValue.StringValue(q.retrievedAt.toString()),
            "evaluated_at" to JsonValue.StringValue(q.evaluatedAt.toString()),
            "age_seconds" to SafeJsonCodec.number(q.age.seconds),
            "delivery_origin" to JsonValue.StringValue(q.deliveryOrigin.name),
            "freshness_state" to JsonValue.StringValue(q.freshnessState.name),
            "is_current_and_live" to JsonValue.BooleanValue(q.isCurrentAndLive),
            "completeness_ratio" to SafeJsonCodec.number(q.completenessRatio),
            "flags" to JsonValue.ArrayValue(q.flags.map { JsonValue.StringValue(it.name) })
        )
    )

    fun encodeSource(src: EnvironmentalDataSource): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf<String, JsonValue>().apply {
            put("provider_id", JsonValue.StringValue(src.providerId))
            put("provider_name", JsonValue.StringValue(src.providerName))
            put("endpoint_or_dataset", JsonValue.StringValue(src.endpointOrDataset))
            put("acquisition_method", JsonValue.StringValue(src.acquisitionMethod.name))
            src.modelInfo?.let { put("model_info", JsonValue.StringValue(it)) }
            put("license", JsonValue.StringValue(src.license))
            put("attribution", JsonValue.StringValue(src.attribution))
        }
    )

    fun <U : PhysicalUnit> encodeAvailability(field: FieldAvailability<U>): JsonValue.ObjectValue = when (field) {
        is FieldAvailability.Available -> JsonValue.ObjectValue(
            linkedMapOf(
                "available" to JsonValue.BooleanValue(true),
                "value" to SafeJsonCodec.number(field.quantity.value),
                "unit" to JsonValue.StringValue(field.quantity.unit.symbol),
                "nature" to JsonValue.StringValue(field.quantity.nature.name)
            )
        )
        is FieldAvailability.Unavailable -> JsonValue.ObjectValue(
            linkedMapOf<String, JsonValue>().apply {
                put("available", JsonValue.BooleanValue(false))
                put("reason", JsonValue.StringValue(field.reason.name))
                field.detail?.let { put("detail", JsonValue.StringValue(it)) }
            }
        )
    }

    fun encodeVariables(vars: EnvironmentalSnapshotVariables): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf<String, JsonValue>().apply {
            put("air_temperature", encodeAvailability(vars.airTemperature))
            put("relative_humidity", encodeAvailability(vars.relativeHumidity))
            put("wind_speed", encodeAvailability(vars.windSpeed))
            put("feels_like_temperature", encodeAvailability(vars.feelsLikeTemperature))
            put("uv_index", encodeAvailability(vars.uvIndex))
            put("solar_radiation", encodeAvailability(vars.solarRadiation))
            vars.isDaylight?.let { put("is_daylight", JsonValue.BooleanValue(it)) }
            vars.wmoWeatherCode?.let { put("wmo_weather_code", SafeJsonCodec.number(it)) }
        }
    )

    fun encodeHeatAssessment(assessment: HeatAssessment): JsonValue.ObjectValue {
        val methodObj = JsonValue.ObjectValue(
            linkedMapOf(
                "method_id" to JsonValue.StringValue(assessment.primaryMethod.methodId),
                "name" to JsonValue.StringValue(assessment.primaryMethod.name),
                "authority" to JsonValue.StringValue(assessment.primaryMethod.authority),
                "citation" to JsonValue.StringValue(assessment.primaryMethod.citation)
            )
        )
        val missingArr = JsonValue.ArrayValue(
            assessment.missingInformation.map { m ->
                JsonValue.ObjectValue(
                    linkedMapOf(
                        "variable" to JsonValue.StringValue(m.variableName),
                        "role" to JsonValue.StringValue(m.role.name),
                        "reason" to JsonValue.StringValue(m.reason.name)
                    )
                )
            }
        )
        return when (assessment) {
            is HeatAssessment.Supported -> JsonValue.ObjectValue(
                linkedMapOf<String, JsonValue>().apply {
                    put("status", JsonValue.StringValue("SUPPORTED"))
                    put("method", methodObj)
                    put("input_nature", JsonValue.StringValue(assessment.inputNature.name))
                    put("valid_at", JsonValue.StringValue(assessment.validAt.toString()))
                    put("assessed_at", JsonValue.StringValue(assessment.assessedAt.toString()))
                    put("category", JsonValue.StringValue(assessment.category.name))
                    put("heat_index_celsius", SafeJsonCodec.number(assessment.heatIndexCelsius.value))
                    put("heat_index_fahrenheit", SafeJsonCodec.number(assessment.heatIndexFahrenheit))
                    assessment.uvCategory?.let { put("uv_category", JsonValue.StringValue(it.name)) }
                    put("formula_branch", JsonValue.StringValue(assessment.formulaBranchUsed))
                    put("freshness", encodeFreshness(assessment.quality))
                    put("missing_information", missingArr)
                    put("explanation", JsonValue.StringValue(assessment.explanation))
                    put("limitations", JsonValue.ArrayValue(assessment.limitations.map(JsonValue::StringValue)))
                }
            )
            is HeatAssessment.Incomplete -> JsonValue.ObjectValue(
                linkedMapOf(
                    "status" to JsonValue.StringValue("INCOMPLETE"),
                    "method" to methodObj,
                    "input_nature" to JsonValue.StringValue(assessment.inputNature.name),
                    "freshness" to encodeFreshness(assessment.quality),
                    "missing_information" to missingArr,
                    "explanation" to JsonValue.StringValue(assessment.explanation),
                    "limitations" to JsonValue.ArrayValue(assessment.limitations.map(JsonValue::StringValue))
                )
            )
            is HeatAssessment.Unsupported -> JsonValue.ObjectValue(
                linkedMapOf(
                    "status" to JsonValue.StringValue("UNSUPPORTED"),
                    "reason" to JsonValue.StringValue(assessment.reason.name),
                    "method" to methodObj,
                    "input_nature" to JsonValue.StringValue(assessment.inputNature.name),
                    "freshness" to encodeFreshness(assessment.quality),
                    "missing_information" to missingArr,
                    "explanation" to JsonValue.StringValue(assessment.explanation),
                    "limitations" to JsonValue.ArrayValue(assessment.limitations.map(JsonValue::StringValue))
                )
            )
        }
    }
}
