package com.ferousco.hydro.core

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.history.EnvironmentalHistoryRepository
import com.ferousco.hydro.core.history.InMemoryEnvironmentalHistoryStore
import com.ferousco.hydro.core.integration.GreatCircleRoutingProvider
import com.ferousco.hydro.core.integration.HeatMapCell
import com.ferousco.hydro.core.integration.HeatMapLayer
import com.ferousco.hydro.core.integration.HeatMapLegendEntry
import com.ferousco.hydro.core.integration.HeatMapMetric
import com.ferousco.hydro.core.integration.HeatMapProvider
import com.ferousco.hydro.core.integration.HeatMapRequest
import com.ferousco.hydro.core.integration.InMemoryNotificationSink
import com.ferousco.hydro.core.integration.LocationFix
import com.ferousco.hydro.core.integration.LocationSource
import com.ferousco.hydro.core.integration.NotificationPolicy
import com.ferousco.hydro.core.integration.NotificationSink
import com.ferousco.hydro.core.integration.RoutingProvider
import com.ferousco.hydro.core.integration.UnavailableHeatMapProvider
import com.ferousco.hydro.core.integration.UnavailableLocationSource
import com.ferousco.hydro.core.integration.UnavailableWearableDataSource
import com.ferousco.hydro.core.integration.WearableDataSource
import com.ferousco.hydro.core.integration.WearableMetric
import com.ferousco.hydro.core.integration.WearableReadRequest
import com.ferousco.hydro.core.integration.WearableReadResult
import com.ferousco.hydro.core.integration.WearableSample
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HistoricalWeatherSeries
import com.ferousco.hydro.core.model.WeatherForecast
import com.ferousco.hydro.core.planning.ActivityPlanningService
import com.ferousco.hydro.core.provider.CachePolicy
import com.ferousco.hydro.core.provider.WeatherRepository
import com.ferousco.hydro.core.provider.WeatherRetrievalEnvelope
import com.ferousco.hydro.core.tools.HydroToolRegistry
import com.ferousco.hydro.core.tools.SetBasedToolPermissionGate
import com.ferousco.hydro.core.tools.ToolCallOutcome
import com.ferousco.hydro.core.tools.ToolCapability
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C2 coverage for the seven tool-parity additions from the product document §6.
 *
 * Each test drives the public registry surface (schema validation, capability gating, execution,
 * JSON encoding) rather than calling tool classes directly, so the tests exercise the same path the
 * orchestrator will use.
 */
class ExtendedHydroToolsTest {

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = MutableClock(now)

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeLocationSource(var fix: EngineResult<LocationFix>) : LocationSource {
        override suspend fun currentLocation(maxAge: Duration): EngineResult<LocationFix> = fix
    }

    private class FakeWeatherRepository(
        var current: EngineResult<WeatherRetrievalEnvelope<EnvironmentalObservation>> =
            EngineResult.Failure(EngineError.NetworkUnavailable("Unconfigured observation")),
        var forecast: EngineResult<WeatherRetrievalEnvelope<WeatherForecast>> =
            EngineResult.Failure(EngineError.NetworkUnavailable("Unconfigured forecast")),
        var historical: EngineResult<WeatherRetrievalEnvelope<HistoricalWeatherSeries>> =
            EngineResult.Failure(EngineError.NetworkUnavailable("Unconfigured history"))
    ) : WeatherRepository {
        override suspend fun getCurrentObservation(
            location: GeoLocation,
            cachePolicy: CachePolicy
        ): EngineResult<WeatherRetrievalEnvelope<EnvironmentalObservation>> = current

        override suspend fun getHourlyForecast(
            location: GeoLocation,
            forecastHours: Int,
            cachePolicy: CachePolicy
        ): EngineResult<WeatherRetrievalEnvelope<WeatherForecast>> = forecast

        override suspend fun getHistoricalWeather(
            location: GeoLocation,
            startDate: LocalDate,
            endDate: LocalDate,
            cachePolicy: CachePolicy
        ): EngineResult<WeatherRetrievalEnvelope<HistoricalWeatherSeries>> = historical

        override fun clearCache() = Unit
    }

    private class FakeHeatMapProvider(private val layer: HeatMapLayer) : HeatMapProvider {
        override suspend fun heatMap(request: HeatMapRequest): EngineResult<HeatMapLayer> =
            EngineResult.Success(layer)
    }

    private class FakeWearableDataSource(
        private val result: WearableReadResult
    ) : WearableDataSource {
        override suspend fun read(request: WearableReadRequest): EngineResult<WearableReadResult> =
            EngineResult.Success(result)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun historyRepository(): EnvironmentalHistoryRepository =
        EnvironmentalHistoryRepository(InMemoryEnvironmentalHistoryStore(), clock = clock)

    private fun envelope(
        observation: EnvironmentalObservation
    ): EngineResult<WeatherRetrievalEnvelope<EnvironmentalObservation>> =
        EngineResult.Success(
            WeatherRetrievalEnvelope(
                data = observation,
                deliveryOrigin = com.ferousco.hydro.core.model.DeliveryOrigin.LIVE_NETWORK,
                freshness = observation.quality
            )
        )

    private fun forecastEnvelope(
        forecast: WeatherForecast
    ): EngineResult<WeatherRetrievalEnvelope<WeatherForecast>> =
        EngineResult.Success(
            WeatherRetrievalEnvelope(
                data = forecast,
                deliveryOrigin = com.ferousco.hydro.core.model.DeliveryOrigin.LIVE_NETWORK,
                freshness = forecast.quality
            )
        )

    private fun registry(
        weatherRepository: WeatherRepository,
        historyRepository: EnvironmentalHistoryRepository,
        clock: Clock = this.clock,
        locationSource: LocationSource = UnavailableLocationSource,
        routingProvider: RoutingProvider = GreatCircleRoutingProvider(),
        heatMapProvider: HeatMapProvider = UnavailableHeatMapProvider,
        wearableDataSource: WearableDataSource = UnavailableWearableDataSource,
        notificationSink: NotificationSink = InMemoryNotificationSink(clock),
        notificationPolicy: NotificationPolicy = NotificationPolicy()
    ): HydroToolRegistry {
        val assessmentEngine = HeatAssessmentEngine(clock)
        return HydroToolRegistry.createDefault(
            weatherRepository = weatherRepository,
            assessmentEngine = assessmentEngine,
            planningService = ActivityPlanningService(assessmentEngine = assessmentEngine, clock = clock),
            historyRepository = historyRepository,
            locationSource = locationSource,
            routingProvider = routingProvider,
            heatMapProvider = heatMapProvider,
            wearableDataSource = wearableDataSource,
            notificationSink = notificationSink,
            notificationPolicy = notificationPolicy,
            clock = clock
        )
    }

    private fun forecast(
        start: Instant = now,
        hours: Int = 12,
        airTempC: Double = 34.0,
        rhPercent: Double = 65.0
    ): WeatherForecast = TestFixtures.buildHourlyForecast(
        startHour = start,
        hourlySpecs = List(hours) { Triple(airTempC, rhPercent, 8.0) },
        retrievedAt = start,
        evaluatedAt = start
    )

    // ------------------------------------------------------------------
    // 1. Registration surface
    // ------------------------------------------------------------------

    @Test
    fun `registry exposes all 24 tool names with strict schemas`() = runBlocking {
        val registry = registry(FakeWeatherRepository(), historyRepository())

        assertEquals(
            setOf(
                "get_weather",
                "get_hourly_forecast",
                "get_historical_weather",
                "calculate_heat_risk",
                "compare_forecast_periods",
                "query_memory",
                "get_current_location",
                "analyze_anomaly",
                "get_route_conditions",
                "get_heat_map",
                "get_wearable_data",
                "create_notification",
                "plan_hydration",
                "get_environmental_news",
                "get_air_quality",
                "get_solar_climate",
                "geocode_place",
                "get_pollen",
                "find_nearby_relief",
                "get_google_weather",
                "find_relief_within_reach",
                "get_area_insights",
                "get_elevation_profile",
                "get_time_zone"
            ),
            registry.descriptors().map { it.name }.toSet()
        )

        val declarations = com.ferousco.hydro.core.json.SafeJsonCodec
            .parse(registry.exportToolDeclarationsJson())
            .getOrNull() as com.ferousco.hydro.core.json.JsonValue.ArrayValue
        assertEquals(24, declarations.items.size)
        for (item in declarations.items) {
            val decl = item as com.ferousco.hydro.core.json.JsonValue.ObjectValue
            assertEquals(
                false,
                decl.getObject("parameters")!!.getBoolean("additionalProperties"),
                "Tool ${decl.getString("name")} must reject additional properties."
            )
        }
    }

    // ------------------------------------------------------------------
    // 2. get_current_location
    // ------------------------------------------------------------------

    @Test
    fun `get_current_location returns freshness metadata and enforces max age`() = runBlocking {
        val fix = LocationFix(
            location = TestFixtures.IBADAN,
            capturedAt = now.minusSeconds(60),
            accuracyMeters = 12.5,
            provider = "gps"
        )
        val source = FakeLocationSource(EngineResult.Success(fix))
        val registry = registry(
            FakeWeatherRepository(),
            historyRepository(),
            locationSource = source
        )

        val fresh = registry.executeJson(
            "get_current_location",
            """{"max_age_seconds": 900, "include_label": true}"""
        )
        assertTrue(fresh is ToolCallOutcome.Success)
        assertEquals(TestFixtures.IBADAN.latitude, fresh.jsonResult.getDouble("latitude"))
        assertEquals(TestFixtures.IBADAN.longitude, fresh.jsonResult.getDouble("longitude"))
        assertEquals("FRESH", fresh.jsonResult.getString("freshness_state"))
        assertEquals(12.5, fresh.jsonResult.getDouble("accuracy_meters"))
        assertEquals("Ibadan", fresh.jsonResult.getString("label"))
        assertEquals("gps", fresh.jsonResult.getString("provider"))
        assertEquals(false, fresh.jsonResult.getBoolean("is_mock"))

        // Labels can resolve to a street address; include_label=false must strip them.
        val withoutLabel = registry.executeJson(
            "get_current_location",
            """{"max_age_seconds": 900, "include_label": false}"""
        )
        assertTrue(withoutLabel is ToolCallOutcome.Success)
        assertEquals(null, withoutLabel.jsonResult.getString("label"))

        // Stale fix beyond the requested age.
        source.fix = EngineResult.Success(fix.copy(capturedAt = now.minusSeconds(3_600)))
        val stale = registry.executeJson("get_current_location", """{"max_age_seconds": 900}""")
        assertTrue(stale is ToolCallOutcome.Failure)
        assertEquals("STALE_DATA", stale.errorCode)

        // A fix dated in the future is a provider fault, not a fresh reading.
        source.fix = EngineResult.Success(fix.copy(capturedAt = now.plusSeconds(60)))
        val future = registry.executeJson("get_current_location", """{"max_age_seconds": 900}""")
        assertTrue(future is ToolCallOutcome.Failure)
        assertEquals("INVALID_PROVIDER_RESPONSE", future.errorCode)

        // Capability gating: location is never implicit.
        source.fix = EngineResult.Success(fix)
        val denied = registry.executeJson(
            "get_current_location",
            """{}""",
            permissionGate = SetBasedToolPermissionGate(setOf(ToolCapability.WEATHER_PROVIDER_READ))
        )
        assertTrue(denied is ToolCallOutcome.Failure)
        assertEquals("PERMISSION_DENIED", denied.errorCode)

        // No adapter wired is an explicit failure, not a fabricated coordinate.
        val unavailable = registry(
            FakeWeatherRepository(),
            historyRepository(),
            locationSource = UnavailableLocationSource
        ).executeJson("get_current_location", """{}""")
        assertTrue(unavailable is ToolCallOutcome.Failure)
        assertEquals("UNSUPPORTED_CALCULATION", unavailable.errorCode)
        assertEquals(
            "LOCATION_SOURCE_NOT_CONFIGURED",
            (unavailable.error as EngineError.UnsupportedCalculation).reason
        )
    }

    // ------------------------------------------------------------------
    // 3. analyze_anomaly
    // ------------------------------------------------------------------

    @Test
    fun `analyze_anomaly refuses to call a reading anomalous without a baseline`() = runBlocking {
        val observation = TestFixtures.buildObservation(
            observedAt = now,
            retrievedAt = now.plusSeconds(60),
            airTempC = 33.0,
            rhPercent = 68.0
        )
        val repository = FakeWeatherRepository(
            current = envelope(observation),
            forecast = forecastEnvelope(forecast(start = now, airTempC = 30.0, rhPercent = 60.0))
        )
        val registry = registry(repository, historyRepository())

        val result = registry.executeJson(
            "analyze_anomaly",
            """{"latitude": 7.3775, "longitude": 3.9470, "timezone": "Africa/Lagos"}"""
        )
        assertTrue(result is ToolCallOutcome.Success)
        assertEquals("INSUFFICIENT_BASELINE", result.jsonResult.getString("status"))
        assertEquals(0, result.jsonResult.getInt("baseline_sample_count"))
        assertEquals(0, result.jsonResult.getArray("anomalies")!!.items.size)
        assertTrue(result.jsonResult.getString("explanation")!!.contains("required"))
        assertEquals(
            3.0,
            result.jsonResult.getObject("forecast_expectation")!!
                .getDouble("air_temperature_delta_celsius")
        )
    }

    @Test
    fun `analyze_anomaly evaluates same-hour baseline and reports direction and severity`() = runBlocking {
        val history = historyRepository()
        // Three same-local-hour (13:00 Africa/Lagos == 12:00Z) observations at 30 C / 60 %.
        for (day in 23..25) {
            val seeded = TestFixtures.buildObservation(
                observedAt = Instant.parse("2026-10-${day}T12:00:00Z"),
                retrievedAt = Instant.parse("2026-10-${day}T12:05:00Z"),
                airTempC = 30.0,
                rhPercent = 60.0
            )
            history.recordObservation(seeded, recordId = "baseline-$day")
        }

        val observation = TestFixtures.buildObservation(
            observedAt = now,
            retrievedAt = now.plusSeconds(60),
            airTempC = 33.0,
            rhPercent = 68.0
        )
        val repository = FakeWeatherRepository(
            current = envelope(observation),
            forecast = forecastEnvelope(forecast(start = now, airTempC = 30.0, rhPercent = 60.0))
        )
        val result = registry(repository, history).executeJson(
            "analyze_anomaly",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "timezone": "Africa/Lagos",
              "lookback_days": 7,
              "temperature_threshold_celsius": 2.0,
              "humidity_threshold_percent": 10.0
            }
            """.trimIndent()
        )
        assertTrue(result is ToolCallOutcome.Success)
        assertEquals("EVALUATED", result.jsonResult.getString("status"))
        assertEquals(3, result.jsonResult.getInt("baseline_sample_count"))

        val anomalies = result.jsonResult.getArray("anomalies")!!.items
            .map { it as com.ferousco.hydro.core.json.JsonValue.ObjectValue }
        assertEquals(2, anomalies.size)

        val temperature = anomalies.first { it.getString("variable") == "air_temperature" }
        assertEquals("ABOVE_BASELINE", temperature.getString("direction"))
        assertEquals("ELEVATED", temperature.getString("severity"))
        assertEquals(3.0, temperature.getDouble("delta"))
        assertEquals(30.0, temperature.getDouble("baseline_mean"))

        val humidity = anomalies.first { it.getString("variable") == "relative_humidity" }
        assertEquals("WITHIN_THRESHOLD", humidity.getString("direction"))
        assertEquals("NORMAL", humidity.getString("severity"))
    }

    // ------------------------------------------------------------------
    // 4. get_route_conditions
    // ------------------------------------------------------------------

    @Test
    fun `get_route_conditions samples arrival-time conditions along a great-circle estimate`() = runBlocking {
        val repository = FakeWeatherRepository(forecast = forecastEnvelope(forecast(hours = 12)))
        val registry = registry(
            repository,
            historyRepository(),
            routingProvider = GreatCircleRoutingProvider(sampleCount = 5)
        )

        val result = registry.executeJson(
            "get_route_conditions",
            """
            {
              "origin_latitude": 7.3775,
              "origin_longitude": 3.9470,
              "destination_latitude": 6.5244,
              "destination_longitude": 3.3792,
              "travel_mode": "DRIVING",
              "depart_at": "2026-10-26T12:00:00Z",
              "timezone": "Africa/Lagos"
            }
            """.trimIndent()
        )
        assertTrue(result is ToolCallOutcome.Success)
        assertEquals("EVALUATED", result.jsonResult.getString("status"))
        assertEquals("GREAT_CIRCLE_ESTIMATE", result.jsonResult.getString("route_method"))
        assertEquals(5, result.jsonResult.getArray("samples")!!.items.size)
        assertTrue(result.jsonResult.getDouble("distance_km")!! > 100.0)
        assertNotNull(result.jsonResult.getString("worst_category"))
        assertTrue(
            result.jsonResult.getArray("limitations")!!.items.any {
                (it as com.ferousco.hydro.core.json.JsonValue.StringValue).value.contains("great-circle")
            }
        )

        val firstSample = result.jsonResult.getArray("samples")!!.items.first()
            as com.ferousco.hydro.core.json.JsonValue.ObjectValue
        assertEquals(0.0, firstSample.getDouble("fraction"))
        assertEquals("2026-10-26T12:00:00Z", firstSample.getString("eta"))
        assertNotNull(firstSample.getDouble("heat_index_celsius"))

        // Degenerate route is rejected before any provider call.
        val samePoint = registry.executeJson(
            "get_route_conditions",
            """
            {
              "origin_latitude": 7.3775,
              "origin_longitude": 3.9470,
              "destination_latitude": 7.3775,
              "destination_longitude": 3.9470,
              "travel_mode": "WALKING"
            }
            """.trimIndent()
        )
        assertTrue(samePoint is ToolCallOutcome.Failure)
        assertEquals("INVALID_ARGUMENTS", samePoint.errorCode)

        // Routing capability is required, not implied by weather access.
        val denied = registry.executeJson(
            "get_route_conditions",
            """
            {
              "origin_latitude": 7.3775,
              "origin_longitude": 3.9470,
              "destination_latitude": 6.5244,
              "destination_longitude": 3.3792,
              "travel_mode": "DRIVING"
            }
            """.trimIndent(),
            permissionGate = SetBasedToolPermissionGate(
                setOf(ToolCapability.WEATHER_PROVIDER_READ, ToolCapability.DETERMINISTIC_ANALYSIS)
            )
        )
        assertTrue(denied is ToolCallOutcome.Failure)
        assertEquals("PERMISSION_DENIED", denied.errorCode)
    }

    // ------------------------------------------------------------------
    // 5. get_heat_map
    // ------------------------------------------------------------------

    @Test
    fun `get_heat_map returns provider layer or fails explicitly when unconfigured`() = runBlocking {
        val unavailable = registry(FakeWeatherRepository(), historyRepository())
            .executeJson("get_heat_map", """{"latitude": 7.3775, "longitude": 3.9470}""")
        assertTrue(unavailable is ToolCallOutcome.Failure)
        assertEquals("UNSUPPORTED_CALCULATION", unavailable.errorCode)
        assertEquals(
            "HEAT_MAP_PROVIDER_NOT_CONFIGURED",
            (unavailable.error as EngineError.UnsupportedCalculation).reason
        )

        val layer = HeatMapLayer(
            metric = HeatMapMetric.HEAT_INDEX_CELSIUS,
            validAt = now,
            issuedOrRetrievedAt = now,
            resolutionKm = 5.0,
            cells = listOf(
                HeatMapCell(TestFixtures.IBADAN, 41.2, "DANGER"),
                HeatMapCell(TestFixtures.IBADAN.copy(latitude = 7.47), 38.0, "EXTREME_CAUTION"),
                HeatMapCell(TestFixtures.IBADAN.copy(longitude = 4.04), 35.5, "EXTREME_CAUTION"),
                HeatMapCell(TestFixtures.IBADAN.copy(latitude = 7.47, longitude = 4.04), 33.0, "CAUTION")
            ),
            legend = listOf(
                HeatMapLegendEntry("Caution", 26.7, 32.2, "#f6c343"),
                HeatMapLegendEntry("Danger", 39.4, null, "#d64545")
            ),
            source = TestFixtures.TEST_FORECAST_SOURCE,
            limitations = listOf("Gridded model output; not station-measured.")
        )
        val configured = registry(
            FakeWeatherRepository(),
            historyRepository(),
            heatMapProvider = FakeHeatMapProvider(layer)
        ).executeJson(
            "get_heat_map",
            """{"latitude": 7.3775, "longitude": 3.9470, "radius_km": 25.0, "resolution_km": 5.0}"""
        )
        assertTrue(configured is ToolCallOutcome.Success)
        assertEquals("HEAT_INDEX_CELSIUS", configured.jsonResult.getString("metric"))
        assertEquals(4, configured.jsonResult.getInt("cell_count"))
        assertEquals(2, configured.jsonResult.getArray("legend")!!.items.size)
        val bounds = configured.jsonResult.getObject("bounds")!!
        assertEquals(7.3775, bounds.getDouble("min_latitude"))
        assertEquals(7.47, bounds.getDouble("max_latitude"))

        // The grid is bounded so a single tool call cannot ask for an unbounded map.
        val tooLarge = registry(
            FakeWeatherRepository(),
            historyRepository(),
            heatMapProvider = FakeHeatMapProvider(layer)
        ).executeJson(
            "get_heat_map",
            """{"latitude": 7.3775, "longitude": 3.9470, "radius_km": 200.0, "resolution_km": 1.0}"""
        )
        assertTrue(tooLarge is ToolCallOutcome.Failure)
        assertEquals("INVALID_ARGUMENTS", tooLarge.errorCode)
    }

    // ------------------------------------------------------------------
    // 6. get_wearable_data
    // ------------------------------------------------------------------

    @Test
    fun `get_wearable_data returns only authorised samples and never estimates`() = runBlocking {
        val unavailable = registry(FakeWeatherRepository(), historyRepository())
            .executeJson("get_wearable_data", """{"metrics": ["HEART_RATE_BPM"]}""")
        assertTrue(unavailable is ToolCallOutcome.Failure)
        assertEquals("UNSUPPORTED_CALCULATION", unavailable.errorCode)
        assertEquals(
            "WEARABLE_SOURCE_NOT_CONFIGURED",
            (unavailable.error as EngineError.UnsupportedCalculation).reason
        )

        val source = FakeWearableDataSource(
            WearableReadResult(
                samples = listOf(
                    WearableSample(
                        metric = WearableMetric.HEART_RATE_BPM,
                        recordedAt = now.minusSeconds(120),
                        value = 72.0,
                        unit = "bpm",
                        sourceDevice = "Pixel Watch",
                        quality = "HIGH"
                    )
                ),
                unavailableMetrics = mapOf(
                    WearableMetric.SWEAT_RATE_LITERS_PER_HOUR to "not reported by this device"
                ),
                permissionGranted = true,
                dataSource = "Health Connect",
                limitations = listOf("Only user-authorised metrics are returned.")
            )
        )
        val result = registry(
            FakeWeatherRepository(),
            historyRepository(),
            wearableDataSource = source
        ).executeJson(
            "get_wearable_data",
            """{"metrics": ["HEART_RATE_BPM", "SWEAT_RATE_LITERS_PER_HOUR"], "lookback_minutes": 60}"""
        )
        assertTrue(result is ToolCallOutcome.Success)
        assertEquals("OBSERVED", result.jsonResult.getString("data_nature"))
        assertEquals(true, result.jsonResult.getBoolean("permission_granted"))
        assertEquals(1, result.jsonResult.getInt("sample_count"))
        assertEquals(1, result.jsonResult.getArray("unavailable_metrics")!!.items.size)
        val sample = result.jsonResult.getArray("samples")!!.items.first()
            as com.ferousco.hydro.core.json.JsonValue.ObjectValue
        assertEquals("HEART_RATE_BPM", sample.getString("metric"))
        assertEquals("HIGH", sample.getString("quality"))
        assertEquals("Pixel Watch", sample.getString("source_device"))

        val unsupported = registry(FakeWeatherRepository(), historyRepository())
            .executeJson("get_wearable_data", """{"metrics": ["BLOOD_GLUCOSE"]}""")
        assertTrue(unsupported is ToolCallOutcome.Failure)
        assertEquals("INVALID_ARGUMENTS", unsupported.errorCode)

        val tooWide = registry(FakeWeatherRepository(), historyRepository())
            .executeJson(
                "get_wearable_data",
                """
                {
                  "metrics": ["HEART_RATE_BPM"],
                  "since": "2026-10-24T12:00:00Z",
                  "until": "2026-10-26T12:00:00Z"
                }
                """.trimIndent()
            )
        assertTrue(tooWide is ToolCallOutcome.Failure)
        assertEquals("INVALID_ARGUMENTS", tooWide.errorCode)
    }

    // ------------------------------------------------------------------
    // 7. create_notification
    // ------------------------------------------------------------------

    @Test
    fun `create_notification enforces dedup, rate limits, confidence, and quiet hours`() = runBlocking {
        val sink = InMemoryNotificationSink(clock)
        val registry = registry(
            FakeWeatherRepository(),
            historyRepository(),
            notificationSink = sink
        )

        fun args(key: String, severity: String = "WARNING", confidence: Double = 0.9): String =
            """
            {
              "title": "Heat warning",
              "body": "Conditions are dangerous for outdoor work this afternoon.",
              "severity": "$severity",
              "category": "HEAT",
              "dedup_key": "$key",
              "trigger": "HEAT_THRESHOLD",
              "confidence": $confidence
            }
            """.trimIndent()

        val first = registry.executeJson("create_notification", args("heat:ibadan:1"))
        assertTrue(first is ToolCallOutcome.Success)
        assertEquals("SUBMITTED", first.jsonResult.getString("status"))
        assertNotNull(first.jsonResult.getString("notification_id"))

        val duplicate = registry.executeJson("create_notification", args("heat:ibadan:1"))
        assertTrue(duplicate is ToolCallOutcome.Success)
        assertEquals("SUPPRESSED", duplicate.jsonResult.getString("status"))
        assertEquals("DUPLICATE", duplicate.jsonResult.getString("reason"))
        assertNotNull(duplicate.jsonResult.getString("retry_after"))

        assertEquals("SUBMITTED", (registry.executeJson("create_notification", args("heat:ibadan:2")) as ToolCallOutcome.Success).jsonResult.getString("status"))
        assertEquals("SUBMITTED", (registry.executeJson("create_notification", args("heat:ibadan:3")) as ToolCallOutcome.Success).jsonResult.getString("status"))

        val rateLimited = registry.executeJson("create_notification", args("heat:ibadan:4"))
        assertTrue(rateLimited is ToolCallOutcome.Success)
        assertEquals("RATE_LIMITED", rateLimited.jsonResult.getString("reason"))

        val lowConfidence = registry.executeJson(
            "create_notification",
            args("heat:ibadan:5", confidence = 0.3)
        )
        assertTrue(lowConfidence is ToolCallOutcome.Success)
        assertEquals("LOW_CONFIDENCE", lowConfidence.jsonResult.getString("reason"))

        // Only the three permitted alerts reached the platform sink.
        assertEquals(3, sink.deliveredIntents().size)

        // Quiet hours block non-critical alerts but let a high-confidence critical alert through.
        val nightClock = MutableClock(Instant.parse("2026-10-26T23:00:00Z"))
        val nightRegistry = registry(
            FakeWeatherRepository(),
            historyRepository(),
            clock = nightClock,
            notificationSink = InMemoryNotificationSink(nightClock)
        )
        val quiet = nightRegistry.executeJson("create_notification", args("heat:night:1"))
        assertTrue(quiet is ToolCallOutcome.Success)
        assertEquals("QUIET_HOURS", quiet.jsonResult.getString("reason"))

        val critical = nightRegistry.executeJson(
            "create_notification",
            args("heat:night:2", severity = "CRITICAL", confidence = 0.95)
        )
        assertTrue(critical is ToolCallOutcome.Success)
        assertEquals("SUBMITTED", critical.jsonResult.getString("status"))

        // A critical alert below the stricter confidence threshold is held.
        val underconfidentCritical = nightRegistry.executeJson(
            "create_notification",
            args("heat:night:3", severity = "CRITICAL", confidence = 0.6)
        )
        assertTrue(underconfidentCritical is ToolCallOutcome.Success)
        assertEquals("LOW_CONFIDENCE", underconfidentCritical.jsonResult.getString("reason"))

        // Writing to the notification tray requires explicit capability.
        val denied = registry.executeJson(
            "create_notification",
            args("heat:ibadan:9"),
            permissionGate = SetBasedToolPermissionGate(setOf(ToolCapability.DETERMINISTIC_ANALYSIS))
        )
        assertTrue(denied is ToolCallOutcome.Failure)
        assertEquals("PERMISSION_DENIED", denied.errorCode)
    }

    // ------------------------------------------------------------------
    // 8. plan_hydration
    // ------------------------------------------------------------------

    @Test
    fun `plan_hydration computes a grounded plan with a drinking schedule`() = runBlocking {
        val repository = FakeWeatherRepository(
            forecast = forecastEnvelope(forecast(start = now, hours = 8, airTempC = 36.0, rhPercent = 70.0))
        )
        val result = registry(repository, historyRepository()).executeJson(
            "plan_hydration",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "timezone": "Africa/Lagos",
              "activity_category": "RUNNING",
              "duration_minutes": 60,
              "intensity": "MODERATE",
              "body_mass_kg": 70.0,
              "depart_at": "2026-10-26T12:00:00Z"
            }
            """.trimIndent()
        )
        assertTrue(result is ToolCallOutcome.Success)
        assertEquals("ESTIMATED", result.jsonResult.getString("status"))
        assertEquals("FORECAST", result.jsonResult.getString("data_nature"))
        assertEquals(true, result.jsonResult.getBoolean("body_mass_provided"))
        assertEquals("HEAT_INDEX", result.jsonResult.getString("heat_basis"))
        assertEquals(300, result.jsonResult.getInt("pre_hydration_ml"))
        assertNotNull(result.jsonResult.getString("heat_category"))

        val target = result.jsonResult.getInt("target_fluid_ml")!!
        assertTrue(target in 100..4_000, "Unexpected fluid target: $target")
        val range = result.jsonResult.getObject("range_ml")!!
        assertTrue(range.getInt("min")!! < target)
        assertTrue(range.getInt("max")!! > target)

        val schedule = result.jsonResult.getArray("schedule")!!.items
        assertTrue(schedule.size >= 2)
        val first = schedule.first() as com.ferousco.hydro.core.json.JsonValue.ObjectValue
        assertEquals(-120, first.getInt("at_minute"))
        assertEquals(300, first.getInt("fluid_ml"))

        val sodium = result.jsonResult.getObject("sodium_mg_range")!!
        assertTrue(sodium.getInt("max")!! > sodium.getInt("min")!!)

        val limitations = result.jsonResult.getArray("limitations")!!.items
            .map { (it as com.ferousco.hydro.core.json.JsonValue.StringValue).value }
        assertTrue(limitations.any { it.contains("not medical advice") })
        assertTrue(limitations.any { it.contains("hyponatraemia") })

        // A departure the forecast cannot cover is an explicit failure, not an invented plan.
        val uncovered = registry(repository, historyRepository()).executeJson(
            "plan_hydration",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "activity_category": "WALKING",
              "duration_minutes": 60,
              "depart_at": "2026-11-05T12:00:00Z"
            }
            """.trimIndent()
        )
        assertTrue(uncovered is ToolCallOutcome.Failure)
        assertEquals("UNSUPPORTED_CALCULATION", uncovered.errorCode)

        // Out-of-range duration is rejected by the schema.
        val badDuration = registry(repository, historyRepository()).executeJson(
            "plan_hydration",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "activity_category": "WALKING",
              "duration_minutes": 5
            }
            """.trimIndent()
        )
        assertTrue(badDuration is ToolCallOutcome.Failure)
        assertEquals("INVALID_ARGUMENTS", badDuration.errorCode)
    }

    // ------------------------------------------------------------------
    // 9. Notification policy helper
    // ------------------------------------------------------------------

    @Test
    fun `notification quiet-hours window correctly spans midnight`() {
        val policy = NotificationPolicy()
        assertTrue(policy.isQuietHours(java.time.LocalTime.of(22, 0)))
        assertTrue(policy.isQuietHours(java.time.LocalTime.of(2, 0)))
        assertTrue(policy.isQuietHours(java.time.LocalTime.of(6, 59)))
        assertFalse(policy.isQuietHours(java.time.LocalTime.of(7, 0)))
        assertFalse(policy.isQuietHours(java.time.LocalTime.of(12, 0)))
        assertFalse(policy.isQuietHours(java.time.LocalTime.of(21, 59)))
    }
}
