package com.ferousco.hydro.core

import com.ferousco.hydro.core.ai.HydroGemmaOrchestrator
import com.ferousco.hydro.core.ai.HydroGemmaOrchestratorConfig
import com.ferousco.hydro.core.ai.OnDeviceLlmClient
import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.history.EnvironmentalHistoryRepository
import com.ferousco.hydro.core.history.EnvironmentalHistoryStore
import com.ferousco.hydro.core.history.InMemoryEnvironmentalHistoryStore
import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.NoOpDiagnosticLogger
import com.ferousco.hydro.core.model.FreshnessPolicy
import com.ferousco.hydro.core.model.RetentionPolicy
import com.ferousco.hydro.core.planning.ActivityPlanningConfig
import com.ferousco.hydro.core.planning.ActivityPlanningService
import com.ferousco.hydro.core.provider.DefaultWeatherRepository
import com.ferousco.hydro.core.provider.WeatherCacheConfig
import com.ferousco.hydro.core.provider.WeatherProvider
import com.ferousco.hydro.core.provider.WeatherRepository
import com.ferousco.hydro.core.provider.openmeteo.OpenMeteoConfig
import com.ferousco.hydro.core.provider.openmeteo.OpenMeteoWeatherProvider
import com.ferousco.hydro.core.tools.HydroToolRegistry
import java.time.Clock

/**
 * Cohesive entry point assembling Hydro's core environmental intelligence engine
 * independently of Android Compose UI or language-model runtimes.
 */
class HydroEngine(
    val weatherRepository: WeatherRepository,
    val assessmentEngine: HeatAssessmentEngine,
    val planningService: ActivityPlanningService,
    val historyRepository: EnvironmentalHistoryRepository,
    val toolRegistry: HydroToolRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) {
    /**
     * Binds an on-device open-weight LLM client (such as `LiteRtGemma3nClient` running
     * `google/gemma-3n-E2B-it-litert-lm`) to this engine's deterministic [HydroToolRegistry].
     */
    fun createGemmaOrchestrator(
        llmClient: OnDeviceLlmClient,
        config: HydroGemmaOrchestratorConfig = HydroGemmaOrchestratorConfig()
    ): HydroGemmaOrchestrator = HydroGemmaOrchestrator(
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
                clock = clock,
                logger = logger
            )
            return HydroEngine(
                weatherRepository = weatherRepo,
                assessmentEngine = assessment,
                planningService = planning,
                historyRepository = history,
                toolRegistry = tools,
                clock = clock,
                logger = logger
            )
        }
    }
}
