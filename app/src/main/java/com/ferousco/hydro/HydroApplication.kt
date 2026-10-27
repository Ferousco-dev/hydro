package com.ferousco.hydro

import android.app.Application
import com.ferousco.hydro.core.HydroEngine
import com.ferousco.hydro.core.ai.HydroOrchestrator
import com.ferousco.hydro.core.ai.HydroOrchestratorConfig
import com.ferousco.hydro.core.ai.LlmModelCatalog
import com.ferousco.hydro.core.ai.gemini.GeminiApiConfig
import com.ferousco.hydro.core.ai.FallbackLlmClient
import com.ferousco.hydro.core.ai.HostedLlmModelSpec
import com.ferousco.hydro.core.ai.LlmClient
import com.ferousco.hydro.core.ai.gemini.GeminiApiLlmClient
import com.ferousco.hydro.core.integration.AfricaGeocoder
import com.ferousco.hydro.core.integration.AirQualityProvider
import com.ferousco.hydro.core.integration.FallbackAirQualityProvider
import com.ferousco.hydro.core.integration.FallbackGeocoder
import com.ferousco.hydro.core.integration.FallbackPlacesProvider
import com.ferousco.hydro.core.integration.FallbackRoutingProvider
import com.ferousco.hydro.core.integration.GoogleAirQualityProvider
import com.ferousco.hydro.core.integration.GoogleAreaInsightsProvider
import com.ferousco.hydro.core.integration.GoogleElevationProvider
import com.ferousco.hydro.core.integration.GoogleIsochroneProvider
import com.ferousco.hydro.core.integration.GoogleTimeZoneProvider
import com.ferousco.hydro.core.integration.GoogleGeocoder
import com.ferousco.hydro.core.integration.GoogleMapsConfig
import com.ferousco.hydro.core.integration.GooglePlacesProvider
import com.ferousco.hydro.core.integration.GooglePollenProvider
import com.ferousco.hydro.core.integration.GoogleRoutesProvider
import com.ferousco.hydro.core.integration.GoogleWeatherApiProvider
import com.ferousco.hydro.core.integration.GreatCircleRoutingProvider
import com.ferousco.hydro.core.integration.OpenMeteoAirQualityProvider
import com.ferousco.hydro.core.integration.OpenMeteoGeocoder
import com.ferousco.hydro.core.integration.OsrmRoutingProvider
import com.ferousco.hydro.core.integration.OverpassPlacesProvider
import com.ferousco.hydro.core.integration.TavilyNewsProvider
import com.ferousco.hydro.core.integration.UnavailableNewsProvider
import com.ferousco.hydro.data.local.HydroDatabase
import com.ferousco.hydro.data.local.RoomEnvironmentalHistoryStore
import com.ferousco.hydro.data.local.RoomUserPreferencesStore
import com.ferousco.hydro.location.AndroidLocationSource
import com.ferousco.hydro.location.DeviceLocationProvider
import com.ferousco.hydro.logging.AndroidDiagnosticLogger
import com.ferousco.hydro.wearables.HealthConnectWearableDataSource
import com.ferousco.hydro.monitoring.ProactiveMonitorScheduler
import com.ferousco.hydro.notifications.AndroidNotificationSink

/**
 * Process-wide composition root for the Android app.
 *
 * Owns the single Room database, [HydroEngine] instance, and the Gemini-backed orchestrator so the
 * UI never constructs network clients, database handles, or assessment engines on its own.
 *
 * The language layer is optional by design: if `GEMINI_API_KEY` is absent, [orchestrator] still
 * exists but reports `ModelUnavailable`, and every deterministic capability (weather, heat index,
 * planning, history) keeps working.
 */
class HydroApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Idempotent: the unique work name means repeated launches update the existing job.
        ProactiveMonitorScheduler.schedule(this)
    }

    val database: HydroDatabase by lazy {
        HydroDatabase.getInstance(this)
    }

    /**
     * Delivers alerts through the Android notification channels. Policy (dedup, quiet hours, rate
     * limits, preferences) is applied by the tool layer before this sink is called.
     */
    val notificationSink: AndroidNotificationSink by lazy {
        AndroidNotificationSink(this)
    }

    private val google = GoogleMapsConfig(BuildConfig.GOOGLE_MAPS_API_KEY)

    /** Health Connect reader, shared by the engine's assistant tool and the app's own screens. */
    val wearables: HealthConnectWearableDataSource by lazy { HealthConnectWearableDataSource(this) }

    /** Google's air quality when it works, Open-Meteo's modelled estimate otherwise. */
    val airQualityProvider: AirQualityProvider by lazy {
        FallbackAirQualityProvider(GoogleAirQualityProvider(google), OpenMeteoAirQualityProvider())
    }

    val engine: HydroEngine by lazy {
        HydroEngine.create(
            // With a Google key we use real routing, geocoding, places, weather, air quality and
            // pollen; without one every provider falls back to a free source or reports unavailable.
            // Google first; if it has no key, or the key is not usable yet, the free OpenStreetMap
            // providers answer instead, so a working key switches everything back automatically.
            routingProvider = FallbackRoutingProvider(
                GoogleRoutesProvider(google),
                FallbackRoutingProvider(OsrmRoutingProvider(), GreatCircleRoutingProvider())
            ),
            geocoder = AfricaGeocoder(FallbackGeocoder(GoogleGeocoder(google), OpenMeteoGeocoder())),
            airQualityProvider = airQualityProvider,
            pollenProvider = GooglePollenProvider(google),
            placesProvider = FallbackPlacesProvider(GooglePlacesProvider(google), OverpassPlacesProvider()),
            googleWeatherProvider = GoogleWeatherApiProvider(google),
            isochroneProvider = GoogleIsochroneProvider(google),
            areaInsightsProvider = GoogleAreaInsightsProvider(google),
            elevationProvider = GoogleElevationProvider(google),
            timeZoneProvider = GoogleTimeZoneProvider(google),
            wearableDataSource = wearables,
            historyStore = RoomEnvironmentalHistoryStore(database.environmentalHistoryDao()),
            preferencesStore = RoomUserPreferencesStore(database.userPreferencesDao()),
            locationSource = AndroidLocationSource(DeviceLocationProvider(this)),
            notificationSink = notificationSink,
            newsProvider = if (BuildConfig.TAVILY_API_KEY.isBlank()) {
                UnavailableNewsProvider
            } else {
                TavilyNewsProvider(BuildConfig.TAVILY_API_KEY)
            },
            logger = AndroidDiagnosticLogger()
        )
    }

    /**
     * Hosted Gemini client. The key is injected at build time from `local.properties` or the
     * environment; see `app/build.gradle.kts`.
     */
    val llmClient: LlmClient by lazy {
        fun client(model: HostedLlmModelSpec) = GeminiApiLlmClient(
            config = GeminiApiConfig(apiKey = BuildConfig.GEMINI_API_KEY, model = model),
            logger = AndroidDiagnosticLogger()
        )
        // A free-tier key runs out of the main model's daily quota; the lighter one keeps answering.
        FallbackLlmClient(
            primary = client(LlmModelCatalog.DEFAULT),
            fallback = client(LlmModelCatalog.GEMINI_3_5_FLASH_LITE)
        )
    }

    val orchestrator: HydroOrchestrator by lazy {
        engine.createOrchestrator(
            llmClient = llmClient,
            config = HydroOrchestratorConfig(showSourceAttribution = false)
        )
    }
}
