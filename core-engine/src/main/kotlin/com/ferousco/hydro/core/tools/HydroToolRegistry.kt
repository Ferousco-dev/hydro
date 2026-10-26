package com.ferousco.hydro.core.tools

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.history.EnvironmentalHistoryRepository
import com.ferousco.hydro.core.integration.GreatCircleRoutingProvider
import com.ferousco.hydro.core.integration.AirQualityProvider
import com.ferousco.hydro.core.integration.Geocoder
import com.ferousco.hydro.core.integration.GoogleWeatherProvider
import com.ferousco.hydro.core.integration.OpenMeteoGeocoder
import com.ferousco.hydro.core.integration.PlacesProvider
import com.ferousco.hydro.core.integration.PollenProvider
import com.ferousco.hydro.core.integration.UnavailableGoogleWeatherProvider
import com.ferousco.hydro.core.integration.UnavailablePlacesProvider
import com.ferousco.hydro.core.integration.UnavailablePollenProvider
import com.ferousco.hydro.core.integration.HeatMapProvider
import com.ferousco.hydro.core.integration.NewsProvider
import com.ferousco.hydro.core.integration.NasaPowerSolarClimateProvider
import com.ferousco.hydro.core.integration.OpenMeteoAirQualityProvider
import com.ferousco.hydro.core.integration.SolarClimateProvider
import com.ferousco.hydro.core.integration.UnavailableNewsProvider
import com.ferousco.hydro.core.integration.LocationSource
import com.ferousco.hydro.core.integration.NotificationPolicy
import com.ferousco.hydro.core.integration.NotificationSink
import com.ferousco.hydro.core.integration.RoutingProvider
import com.ferousco.hydro.core.integration.UnavailableHeatMapProvider
import com.ferousco.hydro.core.integration.UnavailableLocationSource
import com.ferousco.hydro.core.integration.UnavailableNotificationSink
import com.ferousco.hydro.core.integration.UnavailableWearableDataSource
import com.ferousco.hydro.core.integration.WearableDataSource
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.NoOpDiagnosticLogger
import com.ferousco.hydro.core.planning.ActivityPlanningService
import com.ferousco.hydro.core.preferences.InMemoryUserPreferencesStore
import com.ferousco.hydro.core.preferences.UserPreferencesStore
import com.ferousco.hydro.core.provider.WeatherRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.time.Clock

interface HydroTool<P : Any, R : Any> {
    val descriptor: ToolDescriptor

    fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<P>

    suspend fun executeTyped(params: P): EngineResult<R>

    fun encodeResult(result: R): JsonValue.ObjectValue
}

sealed interface ToolCallOutcome<out R> {
    val toolName: String

    data class Success<R>(
        override val toolName: String,
        val typedResult: R,
        val jsonResult: JsonValue.ObjectValue
    ) : ToolCallOutcome<R>

    data class Failure(
        override val toolName: String,
        val errorCode: String,
        val error: EngineError,
        val retryable: Boolean,
        val jsonError: JsonValue.ObjectValue
    ) : ToolCallOutcome<Nothing>
}

/**
 * Closed, policy-enforcing tool registry for Gemini-driven orchestration.
 *
 * Security invariants:
 * - Only explicitly registered [HydroTool] instances can be invoked.
 * - Rejects unknown tool names, unexpected parameters (`additionalProperties = false`),
 *   malformed JSON, oversized payloads, and out-of-range arguments before execution.
 * - Enforces [ToolPermissionGate] capabilities and bounded execution timeouts.
 * - Provides zero arbitrary code execution, zero arbitrary URL/network access, and zero arbitrary file access.
 */
class HydroToolRegistry(
    tools: List<HydroTool<*, *>>,
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) {
    private val toolsByName: Map<String, HydroTool<*, *>>

    init {
        val map = LinkedHashMap<String, HydroTool<*, *>>()
        for (tool in tools) {
            val name = tool.descriptor.name
            require(!map.containsKey(name)) { "Duplicate registration for tool '$name'." }
            map[name] = tool
        }
        toolsByName = map
    }

    fun descriptors(): List<ToolDescriptor> = toolsByName.values.map { it.descriptor }

    fun descriptorFor(toolName: String): ToolDescriptor? = toolsByName[toolName]?.descriptor

    fun exportToolDeclarationsJson(): String {
        val array = JsonValue.ArrayValue(descriptors().map { it.toJsonDeclaration() })
        return SafeJsonCodec.stringify(array)
    }

    fun exportOpenApiToolsJson(): String {
        val array = JsonValue.ArrayValue(descriptors().map { it.toOpenApiFunctionDeclaration() })
        return SafeJsonCodec.stringify(array)
    }

    suspend fun executeJson(
        toolName: String,
        rawArgumentsJson: String,
        permissionGate: ToolPermissionGate = AllowAllToolPermissionGate
    ): ToolCallOutcome<Any> {
        val parsed = SafeJsonCodec.parseObject(
            input = rawArgumentsJson,
            sourceName = "ai_tool_arguments",
            maxChars = MAX_TOOL_ARGUMENT_JSON_CHARS,
            maxDepth = MAX_TOOL_ARGUMENT_JSON_DEPTH
        )
        return when (parsed) {
            is EngineResult.Failure -> buildFailure(
                toolName = toolName,
                error = EngineError.InvalidInput(
                    field = "arguments_json",
                    rejectedValueSummary = "invalid_json",
                    message = parsed.error.message
                )
            )
            is EngineResult.Success -> execute(toolName, parsed.value, permissionGate)
        }
    }

    suspend fun execute(
        toolName: String,
        rawArguments: JsonValue.ObjectValue,
        permissionGate: ToolPermissionGate = AllowAllToolPermissionGate
    ): ToolCallOutcome<Any> {
        currentCoroutineContext().ensureActive()

        val tool = toolsByName[toolName]
        if (tool == null) {
            logger.warn(COMPONENT, "Rejected unregistered tool call", mapOf("toolName" to toolName))
            return buildFailure(
                toolName = toolName,
                error = EngineError.InvalidInput(
                    field = "tool_name",
                    rejectedValueSummary = toolName,
                    message = "Tool '$toolName' is not registered in HydroToolRegistry."
                ),
                explicitCode = "TOOL_NOT_REGISTERED"
            )
        }

        val deniedCap = tool.descriptor.requiredCapabilities.firstOrNull { !permissionGate.isCapabilityPermitted(it) }
        if (deniedCap != null) {
            logger.warn(
                COMPONENT,
                "Denied tool execution due to capability policy",
                mapOf("toolName" to toolName, "capability" to deniedCap.name)
            )
            return buildFailure(
                toolName = toolName,
                error = EngineError.PolicyDenied(
                    capability = deniedCap.name,
                    message = "Execution of '$toolName' is denied because capability '${deniedCap.name}' is not permitted."
                )
            )
        }

        val structuralCheck = tool.descriptor.parameterSchema.validateStructural(rawArguments)
        if (structuralCheck is EngineResult.Failure) {
            logger.info(
                COMPONENT,
                "Rejected invalid tool arguments (schema)",
                mapOf("toolName" to toolName, "errorType" to (structuralCheck.error::class.simpleName ?: "EngineError"))
            )
            return buildFailure(toolName, structuralCheck.error)
        }

        return executeTypedInternal(tool, rawArguments)
    }

    private suspend fun <P : Any, R : Any> executeTypedInternal(
        tool: HydroTool<P, R>,
        rawArguments: JsonValue.ObjectValue
    ): ToolCallOutcome<Any> {
        val toolName = tool.descriptor.name
        val parsedParams = when (val p = tool.parseAndValidateArguments(rawArguments)) {
            is EngineResult.Failure -> {
                logger.info(
                    COMPONENT,
                    "Rejected invalid tool arguments (domain)",
                    mapOf("toolName" to toolName, "errorType" to (p.error::class.simpleName ?: "EngineError"))
                )
                return buildFailure(toolName, p.error)
            }
            is EngineResult.Success -> p.value
        }

        val timeoutMillis = tool.descriptor.failureSpec.executionTimeout.toMillis()
        val result: EngineResult<R> = try {
            withTimeout(timeoutMillis) {
                tool.executeTyped(parsedParams)
            }
        } catch (tce: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            EngineResult.Failure(
                EngineError.ProviderError(
                    providerId = "hydro-tool-executor",
                    statusCode = null,
                    providerReason = "tool_execution_timeout",
                    isTransient = true,
                    message = "Tool '$toolName' timed out after ${timeoutMillis}ms."
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            logger.error(
                COMPONENT,
                "Unexpected exception during tool execution",
                mapOf("toolName" to toolName, "exception" to (ex::class.simpleName ?: "Exception"))
            )
            EngineResult.Failure(
                EngineError.ProviderError(
                    providerId = "hydro-tool-executor",
                    statusCode = null,
                    providerReason = ex::class.simpleName,
                    isTransient = false,
                    message = "Unexpected failure while executing '$toolName'."
                )
            )
        }

        return when (result) {
            is EngineResult.Success -> {
                val json = tool.encodeResult(result.value)
                ToolCallOutcome.Success(
                    toolName = toolName,
                    typedResult = result.value,
                    jsonResult = json
                )
            }
            is EngineResult.Failure -> buildFailure(toolName, result.error)
        }
    }

    private fun buildFailure(
        toolName: String,
        error: EngineError,
        explicitCode: String? = null
    ): ToolCallOutcome.Failure {
        val code = explicitCode ?: mapErrorCode(error)
        val retryable = when (error) {
            is EngineError.NetworkUnavailable -> true
            is EngineError.ProviderError -> error.isTransient
            else -> false
        }
        val jsonErr = JsonValue.ObjectValue(
            linkedMapOf(
                "ok" to JsonValue.BooleanValue(false),
                "tool" to JsonValue.StringValue(toolName),
                "error_code" to JsonValue.StringValue(code),
                "retryable" to JsonValue.BooleanValue(retryable),
                "message" to JsonValue.StringValue(error.message)
            )
        )
        return ToolCallOutcome.Failure(
            toolName = toolName,
            errorCode = code,
            error = error,
            retryable = retryable,
            jsonError = jsonErr
        )
    }

    companion object {
        private const val COMPONENT = "HydroToolRegistry"
        const val MAX_TOOL_ARGUMENT_JSON_CHARS = 32_768
        const val MAX_TOOL_ARGUMENT_JSON_DEPTH = 8

        fun mapErrorCode(error: EngineError): String = when (error) {
            is EngineError.NetworkUnavailable -> "NETWORK_UNAVAILABLE"
            is EngineError.ProviderError -> if (error.providerReason == "tool_execution_timeout") "TOOL_TIMEOUT" else "PROVIDER_ERROR"
            is EngineError.InvalidResponse -> "INVALID_PROVIDER_RESPONSE"
            is EngineError.MissingRequiredInputs -> "MISSING_REQUIRED_INPUTS"
            is EngineError.StaleData -> "STALE_DATA"
            is EngineError.UnsupportedCalculation -> "UNSUPPORTED_CALCULATION"
            is EngineError.PersistenceFailure -> "PERSISTENCE_FAILURE"
            is EngineError.Cancelled -> "CANCELLED"
            is EngineError.InvalidInput -> "INVALID_ARGUMENTS"
            is EngineError.PolicyDenied -> "PERMISSION_DENIED"
        }

        fun createDefault(
            weatherRepository: WeatherRepository,
            assessmentEngine: HeatAssessmentEngine,
            planningService: ActivityPlanningService,
            historyRepository: EnvironmentalHistoryRepository,
            locationSource: LocationSource = UnavailableLocationSource,
            routingProvider: RoutingProvider = GreatCircleRoutingProvider(),
            heatMapProvider: HeatMapProvider = UnavailableHeatMapProvider,
            wearableDataSource: WearableDataSource = UnavailableWearableDataSource,
            notificationSink: NotificationSink = UnavailableNotificationSink,
            notificationPolicy: NotificationPolicy = NotificationPolicy(),
            preferencesStore: UserPreferencesStore = InMemoryUserPreferencesStore(),
            newsProvider: NewsProvider = UnavailableNewsProvider,
            airQualityProvider: AirQualityProvider = OpenMeteoAirQualityProvider(),
            solarClimateProvider: SolarClimateProvider = NasaPowerSolarClimateProvider(),
            geocoder: Geocoder = OpenMeteoGeocoder(),
            pollenProvider: PollenProvider = UnavailablePollenProvider,
            placesProvider: PlacesProvider = UnavailablePlacesProvider,
            googleWeatherProvider: GoogleWeatherProvider = UnavailableGoogleWeatherProvider,
            clock: Clock = Clock.systemUTC(),
            logger: DiagnosticLogger = NoOpDiagnosticLogger
        ): HydroToolRegistry {
            return HydroToolRegistry(
                tools = listOf(
                    GetCurrentWeatherTool(weatherRepository),
                    GetHourlyForecastTool(weatherRepository),
                    GetHistoricalWeatherTool(weatherRepository, clock),
                    CalculateHeatAssessmentTool(assessmentEngine, clock),
                    CompareForecastPeriodsTool(weatherRepository, planningService),
                    QueryLocalHistoryTool(historyRepository, preferencesStore, clock),
                    GetCurrentLocationTool(locationSource, clock),
                    AnalyzeAnomalyTool(weatherRepository, historyRepository, clock),
                    GetRouteConditionsTool(routingProvider, weatherRepository, assessmentEngine, clock),
                    GetHeatMapTool(heatMapProvider, clock),
                    GetWearableDataTool(wearableDataSource, clock),
                    CreateNotificationTool(notificationSink, notificationPolicy, preferencesStore, clock),
                    PlanHydrationTool(weatherRepository, assessmentEngine, clock),
                    GetEnvironmentalNewsTool(newsProvider),
                    GetAirQualityTool(airQualityProvider),
                    GetSolarClimateTool(solarClimateProvider),
                    GeocodePlaceTool(geocoder),
                    GetPollenTool(pollenProvider),
                    FindNearbyReliefTool(placesProvider),
                    GetGoogleWeatherTool(googleWeatherProvider)
                ),
                logger = logger
            )
        }
    }
}
