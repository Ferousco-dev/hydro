package com.ferousco.hydro.core

import com.ferousco.hydro.core.ai.HydroOrchestrator
import com.ferousco.hydro.core.ai.HydroOrchestratorConfig
import com.ferousco.hydro.core.ai.LlmClient
import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.history.EnvironmentalHistoryRepository
import com.ferousco.hydro.core.history.EnvironmentalHistoryStore
import com.ferousco.hydro.core.history.InMemoryEnvironmentalHistoryStore
import com.ferousco.hydro.core.integration.HeatMapProvider
import com.ferousco.hydro.core.integration.LocationSource
import com.ferousco.hydro.core.integration.NotificationPolicy
import com.ferousco.hydro.core.integration.NotificationSink
import com.ferousco.hydro.core.integration.RoutingProvider
import com.ferousco.hydro.core.integration.UnavailableHeatMapProvider
import com.ferousco.hydro.core.integration.UnavailableLocationSource
import com.ferousco.hydro.core.integration.UnavailableNotificationSink
import com.ferousco.hydro.core.integration.UnavailableWearableDataSource
import com.ferousco.hydro.core.integration.WearableDataSource
import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.NoOpDiagnosticLogger
import com.ferousco.hydro.core.memory.HistoricalBaselineSelector
import com.ferousco.hydro.core.memory.HistoricalComparisonService
import com.ferousco.hydro.core.memory.YesterdayComparison
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.FreshnessPolicy
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.RetentionPolicy
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.planning.ActivityPlanningConfig
import com.ferousco.hydro.core.planning.ActivityPlanningService
import com.ferousco.hydro.core.preferences.InMemoryUserPreferencesStore
import com.ferousco.hydro.core.preferences.UserPreferences
import com.ferousco.hydro.core.preferences.UserPreferencesStore
import com.ferousco.hydro.core.proactive.ProactiveCheckResult
import com.ferousco.hydro.core.proactive.ProactiveDecisionEngine
import com.ferousco.hydro.core.proactive.ProactiveDelivery
import com.ferousco.hydro.core.proactive.ProactiveEvaluationRequest
import com.ferousco.hydro.core.provider.DefaultWeatherRepository
import com.ferousco.hydro.core.provider.WeatherCacheConfig
import com.ferousco.hydro.core.provider.WeatherProvider
import com.ferousco.hydro.core.provider.WeatherRepository
import com.ferousco.hydro.core.provider.openmeteo.OpenMeteoConfig
import com.ferousco.hydro.core.provider.openmeteo.OpenMeteoWeatherProvider
import com.ferousco.hydro.core.recommendation.Recommendation
import com.ferousco.hydro.core.recommendation.RecommendationEngine
import com.ferousco.hydro.core.recommendation.RecommendationRequest
import com.ferousco.hydro.core.recommendation.RecommendationService
import com.ferousco.hydro.core.tools.HydroToolRegistry
import com.ferousco.hydro.core.tools.AllowAllToolPermissionGate
import com.ferousco.hydro.core.tools.ToolPermissionGate
import java.time.Clock
import java.time.Duration

/**
 * Cohesive entry point assembling Hydro's core environmental intelligence engine
 * independently of Android Compose UI or language-model runtimes.
 */
class HydroEngine(
    val weatherRepository: WeatherRepository,
    val assessmentEngine: HeatAssessmentEngine,
    val planningService: ActivityPlanningService,
    val recommendationService: RecommendationService,
    val historyRepository: EnvironmentalHistoryRepository,
    val toolRegistry: HydroToolRegistry,
    /** Exposed so callers can draw the actual route geometry, not just the sampled conditions. */
    val routingProvider: com.ferousco.hydro.core.integration.RoutingProvider =
        com.ferousco.hydro.core.integration.GreatCircleRoutingProvider(),
    val preferencesStore: UserPreferencesStore = InMemoryUserPreferencesStore(),
    private val clock: Clock = Clock.systemUTC(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) {
    /**
     * Reads the saved [UserPreferences].
     *
     * Preferences are data, not prompt text: the UI edits them through these helpers and
     * `query_memory` reads them back for the orchestrator.
     */
    suspend fun loadUserPreferences(): EngineResult<UserPreferences> = preferencesStore.load()

    /** Replaces the saved preferences and returns the persisted, timestamped record. */
    suspend fun saveUserPreferences(preferences: UserPreferences): EngineResult<UserPreferences> =
        preferencesStore.save(preferences)

    /**
     * Applies a transform to the saved preferences atomically.
     *
     * [transform] must return a valid [UserPreferences]; invalid values throw, which surfaces as a
     * failed update rather than a silently persisted bad record.
     */
    suspend fun updateUserPreferences(
        transform: (UserPreferences) -> UserPreferences
    ): EngineResult<UserPreferences> = try {
        preferencesStore.update(transform)
    } catch (ex: IllegalArgumentException) {
        EngineResult.Failure(
            EngineError.InvalidInput(
                field = "user_preferences",
                rejectedValueSummary = "invalid",
                message = ex.message ?: "The updated preferences are not valid."
            )
        )
    }

    /**
     * Runs one deterministic proactive pass: evaluate thresholds, then hand any alert to
     * `create_notification` so the single policy implementation applies.
     *
     * No language model is involved. A background scheduler (WorkManager on Android) calls this with
     * whatever assessments it has; missing data produces a rationale, not a fabricated alert.
     */
    suspend fun runProactiveCheck(
        request: ProactiveEvaluationRequest,
        permissionGate: ToolPermissionGate = AllowAllToolPermissionGate
    ): ProactiveCheckResult {
        val decision = ProactiveDecisionEngine.evaluate(request)
        val deliveries = decision.alerts.map { alert ->
            ProactiveDelivery(
                alert = alert,
                outcome = toolRegistry.execute(
                    toolName = "create_notification",
                    rawArguments = alert.toToolArguments(request.timeZone),
                    permissionGate = permissionGate
                )
            )
        }
        return ProactiveCheckResult(decision = decision, deliveries = deliveries)
    }

    /**
     * Fetches current conditions and a short forecast, assesses them, and returns deterministic
     * guidance: urgency, ordered actions, evidence, limitations, and the coolest usable window.
     *
     * This is the one-call entry point a UI screen should use. It does not call a language model, and
     * a failed current-observation fetch is returned as a failure rather than a guessed recommendation.
     */
    suspend fun recommendForLocation(
        location: GeoLocation,
        activityCategory: ActivityCategory? = null,
        plannedDuration: Duration? = null,
        lookahead: Duration = RecommendationService.DEFAULT_LOOKAHEAD
    ): EngineResult<Recommendation> = recommendationService.forLocation(
        location = location,
        activityCategory = activityCategory,
        plannedDuration = plannedDuration,
        lookahead = lookahead
    )

    /**
     * Re-runs only the pure decision stage over assessments the caller already holds.
     *
     * Useful when a screen has just refreshed current conditions and a forecast and does not want a
     * second network round-trip. The language model may rephrase the result, never override it.
     */
    fun recommend(request: RecommendationRequest): Recommendation = RecommendationEngine.recommend(request)

    /**
     * Answers "how does the latest stored observation compare with yesterday?".
     *
     * The caller supplies the records it is allowed to read; this method never widens the query. An
     * absent baseline returns [YesterdayComparison.Unavailable] with a reason instead of `null`.
     */
    fun compareLatestToYesterday(
        records: List<StoredEnvironmentalRecord>,
        tolerance: Duration = HistoricalBaselineSelector.DEFAULT_TOLERANCE,
        maxDistanceKm: Double = HistoricalBaselineSelector.DEFAULT_MAX_DISTANCE_KM
    ): YesterdayComparison = HistoricalComparisonService.latestVersusYesterday(
        records = records,
        tolerance = tolerance,
        maxDistanceKm = maxDistanceKm
    )

    /**
     * Binds a language-model client to this engine's deterministic [HydroToolRegistry].
     *
     * [llmClient] is normally the hosted [com.ferousco.hydro.core.ai.GeminiApiLlmClient]; the
     * Android `LiteRtGemma3nClient` remains available for on-device experiments. The orchestrator
     * never performs arithmetic itself — every number in a final answer must come from a tool.
     */
    fun createOrchestrator(
        llmClient: LlmClient,
        config: HydroOrchestratorConfig = HydroOrchestratorConfig()
    ): HydroOrchestrator = HydroOrchestrator(
        llmClient = llmClient,
        toolRegistry = toolRegistry,
        config = config,
        clock = clock,
        logger = logger
    )

    companion object {
        fun create(
            weatherProvider: WeatherProvider? = null,
            historyStore: EnvironmentalHistoryStore = InMemoryEnvironmentalHistoryStore(),
            freshnessPolicy: FreshnessPolicy = FreshnessPolicy(),
            cacheConfig: WeatherCacheConfig = WeatherCacheConfig(),
            planningConfig: ActivityPlanningConfig = ActivityPlanningConfig(),
            retentionPolicy: RetentionPolicy = RetentionPolicy(),
            openMeteoConfig: OpenMeteoConfig = OpenMeteoConfig(),
            locationSource: LocationSource = UnavailableLocationSource,
            routingProvider: RoutingProvider = com.ferousco.hydro.core.integration.GreatCircleRoutingProvider(),
            heatMapProvider: HeatMapProvider = com.ferousco.hydro.core.integration.OpenMeteoGridHeatMapProvider(),
            wearableDataSource: WearableDataSource = UnavailableWearableDataSource,
            notificationSink: NotificationSink = UnavailableNotificationSink,
            notificationPolicy: NotificationPolicy = NotificationPolicy(),
            preferencesStore: UserPreferencesStore = InMemoryUserPreferencesStore(),
            newsProvider: com.ferousco.hydro.core.integration.NewsProvider =
                com.ferousco.hydro.core.integration.UnavailableNewsProvider,
            airQualityProvider: com.ferousco.hydro.core.integration.AirQualityProvider =
                com.ferousco.hydro.core.integration.OpenMeteoAirQualityProvider(),
            solarClimateProvider: com.ferousco.hydro.core.integration.SolarClimateProvider =
                com.ferousco.hydro.core.integration.NasaPowerSolarClimateProvider(),
            geocoder: com.ferousco.hydro.core.integration.Geocoder =
                com.ferousco.hydro.core.integration.OpenMeteoGeocoder(),
            pollenProvider: com.ferousco.hydro.core.integration.PollenProvider =
                com.ferousco.hydro.core.integration.UnavailablePollenProvider,
            placesProvider: com.ferousco.hydro.core.integration.PlacesProvider =
                com.ferousco.hydro.core.integration.UnavailablePlacesProvider,
            googleWeatherProvider: com.ferousco.hydro.core.integration.GoogleWeatherProvider =
                com.ferousco.hydro.core.integration.UnavailableGoogleWeatherProvider,
            isochroneProvider: com.ferousco.hydro.core.integration.IsochroneProvider =
                com.ferousco.hydro.core.integration.UnavailableIsochroneProvider,
            areaInsightsProvider: com.ferousco.hydro.core.integration.AreaInsightsProvider =
                com.ferousco.hydro.core.integration.UnavailableAreaInsightsProvider,
            elevationProvider: com.ferousco.hydro.core.integration.ElevationProvider =
                com.ferousco.hydro.core.integration.UnavailableElevationProvider,
            timeZoneProvider: com.ferousco.hydro.core.integration.TimeZoneProvider =
                com.ferousco.hydro.core.integration.UnavailableTimeZoneProvider,
            clock: Clock = Clock.systemUTC(),
            logger: DiagnosticLogger = NoOpDiagnosticLogger
        ): HydroEngine {
            val effectiveProvider = weatherProvider ?: OpenMeteoWeatherProvider(
                config = openMeteoConfig,
                freshnessPolicy = freshnessPolicy,
                clock = clock,
                logger = logger
            )
            val weatherRepo = DefaultWeatherRepository(
                provider = effectiveProvider,
                freshnessPolicy = freshnessPolicy,
                cacheConfig = cacheConfig,
                clock = clock,
                logger = logger
            )
            val assessment = HeatAssessmentEngine(
                clock = clock,
                logger = logger
            )
            val planning = ActivityPlanningService(
                assessmentEngine = assessment,
                freshnessPolicy = freshnessPolicy,
                config = planningConfig,
                clock = clock,
                logger = logger
            )
            val history = EnvironmentalHistoryRepository(
                store = historyStore,
                initialRetentionPolicy = retentionPolicy,
                clock = clock,
                logger = logger
            )
            val tools = HydroToolRegistry.createDefault(
                weatherRepository = weatherRepo,
                assessmentEngine = assessment,
                planningService = planning,
                historyRepository = history,
                locationSource = locationSource,
                routingProvider = routingProvider,
                heatMapProvider = heatMapProvider,
                wearableDataSource = wearableDataSource,
                notificationSink = notificationSink,
                notificationPolicy = notificationPolicy,
                preferencesStore = preferencesStore,
                newsProvider = newsProvider,
                airQualityProvider = airQualityProvider,
                solarClimateProvider = solarClimateProvider,
                geocoder = geocoder,
                pollenProvider = pollenProvider,
                placesProvider = placesProvider,
                googleWeatherProvider = googleWeatherProvider,
                isochroneProvider = isochroneProvider,
                areaInsightsProvider = areaInsightsProvider,
                elevationProvider = elevationProvider,
                timeZoneProvider = timeZoneProvider,
                clock = clock,
                logger = logger
            )
            return HydroEngine(
                weatherRepository = weatherRepo,
                assessmentEngine = assessment,
                planningService = planning,
                recommendationService = RecommendationService(
                    weatherRepository = weatherRepo,
                    assessmentEngine = assessment,
                    clock = clock,
                    logger = logger
                ),
                historyRepository = history,
                toolRegistry = tools,
                routingProvider = routingProvider,
                preferencesStore = preferencesStore,
                clock = clock,
                logger = logger
            )
        }
    }
}
