package com.ferousco.hydro.core

import com.ferousco.hydro.core.HydroEngine
import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.history.EnvironmentalHistoryRepository
import com.ferousco.hydro.core.history.InMemoryEnvironmentalHistoryStore
import com.ferousco.hydro.core.integration.InMemoryNotificationSink
import com.ferousco.hydro.core.integration.NotificationPolicy
import com.ferousco.hydro.core.integration.NotificationSeverity
import com.ferousco.hydro.core.integration.NotificationSink
import com.ferousco.hydro.core.integration.UnavailableHeatMapProvider
import com.ferousco.hydro.core.integration.UnavailableLocationSource
import com.ferousco.hydro.core.integration.UnavailableWearableDataSource
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.ActivityIntensity
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HistoricalWeatherSeries
import com.ferousco.hydro.core.model.WeatherForecast
import com.ferousco.hydro.core.planning.ActivityPlanningService
import com.ferousco.hydro.core.provider.CachePolicy
import com.ferousco.hydro.core.provider.WeatherRepository
import com.ferousco.hydro.core.provider.WeatherRetrievalEnvelope
import com.ferousco.hydro.core.preferences.ActivityPreferences
import com.ferousco.hydro.core.preferences.AlertPreferences
import com.ferousco.hydro.core.preferences.DistanceUnitPreference
import com.ferousco.hydro.core.preferences.InMemoryUserPreferencesStore
import com.ferousco.hydro.core.preferences.SavedLocation
import com.ferousco.hydro.core.preferences.TemperatureUnitPreference
import com.ferousco.hydro.core.preferences.UserPreferences
import com.ferousco.hydro.core.preferences.UserPreferencesCodec
import com.ferousco.hydro.core.preferences.UserPreferencesStore
import com.ferousco.hydro.core.preferences.VolumeUnitPreference
import com.ferousco.hydro.core.tools.HydroToolRegistry
import com.ferousco.hydro.core.tools.QueryLocalHistoryTool
import com.ferousco.hydro.core.tools.ToolCallOutcome
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C3 coverage: local user preferences, forecast-versus-observation verification, and the
 * deterministic notification policy that reads both.
 *
 * Everything is driven through the public registry surface so the tests exercise the same path the
 * orchestrator uses, not a private shortcut.
 */
class MemoryAndPreferencesTest {

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = MutableClock(now)

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun historyRepository(
        clock: Clock = this.clock
    ): EnvironmentalHistoryRepository =
        EnvironmentalHistoryRepository(InMemoryEnvironmentalHistoryStore(), clock = clock)

    /** Minimal repository stub: C3 tests never exercise the weather provider path. */
    private class StubWeatherRepository : WeatherRepository {
        override suspend fun getCurrentObservation(
            location: GeoLocation,
            cachePolicy: CachePolicy
        ): EngineResult<WeatherRetrievalEnvelope<EnvironmentalObservation>> =
            EngineResult.Failure(EngineError.NetworkUnavailable("Not configured for this test"))

        override suspend fun getHourlyForecast(
            location: GeoLocation,
            forecastHours: Int,
            cachePolicy: CachePolicy
        ): EngineResult<WeatherRetrievalEnvelope<WeatherForecast>> =
            EngineResult.Failure(EngineError.NetworkUnavailable("Not configured for this test"))

        override suspend fun getHistoricalWeather(
            location: GeoLocation,
            startDate: java.time.LocalDate,
            endDate: java.time.LocalDate,
            cachePolicy: CachePolicy
        ): EngineResult<WeatherRetrievalEnvelope<HistoricalWeatherSeries>> =
            EngineResult.Failure(EngineError.NetworkUnavailable("Not configured for this test"))

        override fun clearCache() = Unit
    }

    private fun registry(
        clock: Clock = this.clock,
        historyRepository: EnvironmentalHistoryRepository = historyRepository(clock),
        preferencesStore: UserPreferencesStore = InMemoryUserPreferencesStore(clock = clock),
        notificationSink: NotificationSink = InMemoryNotificationSink(clock),
        notificationPolicy: NotificationPolicy = NotificationPolicy()
    ): HydroToolRegistry {
        val assessmentEngine = HeatAssessmentEngine(clock)
        return HydroToolRegistry.createDefault(
            weatherRepository = StubWeatherRepository(),
            assessmentEngine = assessmentEngine,
            planningService = ActivityPlanningService(assessmentEngine = assessmentEngine, clock = clock),
            historyRepository = historyRepository,
            locationSource = UnavailableLocationSource,
            heatMapProvider = UnavailableHeatMapProvider,
            wearableDataSource = UnavailableWearableDataSource,
            notificationSink = notificationSink,
            notificationPolicy = notificationPolicy,
            preferencesStore = preferencesStore,
            clock = clock
        )
    }

    private fun alertJson(
        severity: String = "WARNING",
        dedupKey: String = "heat:ibadan:2026-10-26",
        confidence: Double = 0.9
    ): String = """
        {
          "title": "Heat warning",
          "body": "Heat index is expected to reach 41°C by 15:00.",
          "severity": "$severity",
          "category": "HEAT",
          "dedup_key": "$dedupKey",
          "confidence": $confidence,
          "timezone": "UTC"
        }
    """.trimIndent()

    private fun seedForecast(
        repository: EnvironmentalHistoryRepository,
        validAt: Instant,
        issuedAt: Instant,
        forecastTempC: Double,
        forecastRh: Double = 60.0,
        id: String
    ) = runBlocking {
        val forecast = TestFixtures.buildHourlyForecast(
            startHour = validAt,
            hourlySpecs = listOf(Triple(forecastTempC, forecastRh, 8.0)),
            retrievedAt = issuedAt
        )
        repository.recordIssuedForecastPoint(
            forecast.hourly.first(),
            timeZone = ZoneOffset.UTC,
            recordId = id
        )
    }

    private fun seedObservation(
        repository: EnvironmentalHistoryRepository,
        validAt: Instant,
        observedTempC: Double,
        observedRh: Double = 68.0,
        id: String
    ) = runBlocking {
        repository.recordObservation(
            TestFixtures.buildObservation(
                observedAt = validAt,
                retrievedAt = validAt.plusSeconds(300),
                airTempC = observedTempC,
                rhPercent = observedRh
            ),
            recordId = id
        )
    }

    // ------------------------------------------------------------------
    // 1. Preferences codec
    // ------------------------------------------------------------------

    @Test
    fun `preferences codec round-trips every field`() {
        val original = UserPreferences(
            temperatureUnit = TemperatureUnitPreference.FAHRENHEIT,
            distanceUnit = DistanceUnitPreference.MILES,
            volumeUnit = VolumeUnitPreference.FLUID_OUNCES,
            alerts = AlertPreferences(
                enabled = true,
                minimumSeverity = NotificationSeverity.WARNING,
                quietHoursStart = LocalTime.of(21, 30),
                quietHoursEnd = LocalTime.of(6, 15),
                allowCriticalDuringQuietHours = false,
                maxNotificationsPerHour = 2,
                dedupWindowHours = 12
            ),
            activity = ActivityPreferences(
                preferredActivities = setOf(ActivityCategory.RUNNING, ActivityCategory.CYCLING),
                defaultIntensity = ActivityIntensity.VIGOROUS,
                bodyMassKg = 72.5,
                homeLocation = TestFixtures.IBADAN,
                savedLocations = listOf(SavedLocation("Work", TestFixtures.LAGOS))
            ),
            consentedPersonalization = true,
            updatedAt = Instant.parse("2026-10-26T10:00:00Z")
        )

        val encoded = UserPreferencesCodec.encodeToString(original)
        val decoded = UserPreferencesCodec.decodeFromString(encoded).getOrNull()

        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `preferences codec tolerates unknown fields and rejects newer schemas`() {
        val encoded = UserPreferencesCodec.encodeToString(UserPreferences())

        val withUnknownField = encoded.dropLast(1) + ""","future_setting":42}"""
        assertTrue(UserPreferencesCodec.decodeFromString(withUnknownField) is EngineResult.Success)

        val newerSchema = encoded.replace("\"schema_version\":1", "\"schema_version\":2")
        val rejected = UserPreferencesCodec.decodeFromString(newerSchema)
        assertTrue(rejected is EngineResult.Failure)
        assertTrue(rejected.error is EngineError.PersistenceFailure)
    }

    @Test
    fun `preferences codec rejects corrupt payloads instead of silently defaulting`() {
        val malformed = UserPreferencesCodec.decodeFromString("""{"units":""")
        assertTrue(malformed is EngineResult.Failure)
        assertTrue(malformed.error is EngineError.PersistenceFailure)

        val badTime = UserPreferencesCodec.decodeFromString(
            """{"schema_version":1,"alerts":{"quiet_hours_start":"not-a-time"}}"""
        )
        assertTrue(badTime is EngineResult.Failure)
        assertTrue(badTime.error is EngineError.PersistenceFailure)
    }

    @Test
    fun `tool projection withholds personalisation data without consent`() {
        val unconsented = UserPreferences(
            activity = ActivityPreferences(
                bodyMassKg = 80.0,
                homeLocation = TestFixtures.IBADAN,
                savedLocations = listOf(SavedLocation("Home", TestFixtures.IBADAN))
            ),
            consentedPersonalization = false
        )
        val withheld = UserPreferencesCodec.encodeForTool(unconsented)
        assertEquals(true, withheld.getBoolean("personalization_withheld"))
        assertEquals(false, withheld.getBoolean("consented_personalization"))
        val withheldActivity = withheld.getObject("activity")!!
        assertNull(withheldActivity.getDouble("body_mass_kg"))
        assertNull(withheldActivity.getObject("home_location"))
        assertNull(withheldActivity.getArray("saved_locations"))
        // Non-personal fields remain available so units and activity defaults still work.
        assertEquals("MODERATE", withheldActivity.getString("default_intensity"))

        val consented = UserPreferencesCodec.encodeForTool(
            unconsented.copy(consentedPersonalization = true)
        )
        assertEquals(false, consented.getBoolean("personalization_withheld"))
        assertEquals(80.0, consented.getObject("activity")!!.getDouble("body_mass_kg"))
        assertNotNull(consented.getObject("activity")!!.getObject("home_location"))
    }

    @Test
    fun `in-memory store stamps the save time and schema version`() = runBlocking {
        val store = InMemoryUserPreferencesStore(clock = clock)
        val saved = store.save(UserPreferences()).getOrNull()

        assertNotNull(saved)
        assertEquals(now, saved.updatedAt)
        assertEquals(UserPreferences.CURRENT_SCHEMA_VERSION, saved.schemaVersion)
        assertEquals(saved, store.load().getOrNull())
    }

    // ------------------------------------------------------------------
    // 2. query_memory: preferences
    // ------------------------------------------------------------------

    @Test
    fun `query_memory returns saved preferences only when asked`() = runBlocking {
        val repository = historyRepository()
        val preferences = UserPreferences(
            temperatureUnit = TemperatureUnitPreference.FAHRENHEIT,
            alerts = AlertPreferences(minimumSeverity = NotificationSeverity.WARNING)
        )
        val store = InMemoryUserPreferencesStore(preferences, clock)
        val registry = registry(historyRepository = repository, preferencesStore = store)

        val without = registry.executeJson(
            "query_memory",
            """{"latitude": 7.3775, "longitude": 3.9470}"""
        )
        assertTrue(without is ToolCallOutcome.Success)
        assertNull(without.jsonResult.getObject("preferences"))
        assertNull(without.jsonResult.getObject("forecast_verification"))

        val with = registry.executeJson(
            "query_memory",
            """{"latitude": 7.3775, "longitude": 3.9470, "include_preferences": true}"""
        )
        assertTrue(with is ToolCallOutcome.Success)
        val encoded = assertNotNull(with.jsonResult.getObject("preferences"))
        assertEquals("FAHRENHEIT", encoded.getObject("units")!!.getString("temperature"))
        assertEquals(
            "WARNING",
            encoded.getObject("alerts")!!.getString("minimum_severity")
        )
        assertEquals(false, encoded.getBoolean("personalization_withheld"))
    }

    @Test
    fun `query_memory fails explicitly when no preferences store is configured`() = runBlocking {
        val tool = QueryLocalHistoryTool(historyRepository())
        val registry = HydroToolRegistry(listOf(tool))

        val result = registry.executeJson(
            "query_memory",
            """{"latitude": 7.3775, "longitude": 3.9470, "include_preferences": true}"""
        )
        assertTrue(result is ToolCallOutcome.Failure)
        assertEquals("UNSUPPORTED_CALCULATION", result.errorCode)
        assertEquals(
            "PREFERENCES_STORE_NOT_CONFIGURED",
            (result.error as EngineError.UnsupportedCalculation).reason
        )
    }

    // ------------------------------------------------------------------
    // 3. query_memory: forecast verification
    // ------------------------------------------------------------------

    @Test
    fun `forecast verification reports descriptive error against stored observations`() = runBlocking {
        val repository = historyRepository()
        // Forecast A: issued six hours ahead, predicted 30°C.
        seedForecast(
            repository = repository,
            validAt = now,
            issuedAt = now.minus(Duration.ofHours(6)),
            forecastTempC = 30.0,
            id = "fcst-a"
        )
        // Forecast B: issued three hours ahead, predicted 31°C.
        seedForecast(
            repository = repository,
            validAt = now,
            issuedAt = now.minus(Duration.ofHours(3)),
            forecastTempC = 31.0,
            id = "fcst-b"
        )
        // The observation both forecasts are scored against.
        seedObservation(repository, validAt = now, observedTempC = 33.0, id = "obs-main")
        // An observation with no forecast at that hour must be reported as unpaired.
        seedObservation(
            repository,
            validAt = now.minus(Duration.ofHours(2)),
            observedTempC = 29.0,
            id = "obs-unpaired"
        )
        // A pair outside the lookback window must not leak into the statistics.
        seedForecast(
            repository = repository,
            validAt = now.minus(Duration.ofDays(10)),
            issuedAt = now.minus(Duration.ofDays(10)).minus(Duration.ofHours(5)),
            forecastTempC = 10.0,
            id = "fcst-old"
        )
        seedObservation(
            repository,
            validAt = now.minus(Duration.ofDays(10)),
            observedTempC = 40.0,
            id = "obs-old"
        )

        val registry = registry(historyRepository = repository)
        val result = registry.executeJson(
            "query_memory",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "radius_km": 10.0,
              "include_forecast_verification": true,
              "verification_lookback_days": 7,
              "verification_max_pairs": 10
            }
            """.trimIndent()
        )
        assertTrue(result is ToolCallOutcome.Success)
        val verification = assertNotNull(result.jsonResult.getObject("forecast_verification"))

        assertEquals(7, verification.getInt("lookback_days"))
        assertEquals(2, verification.getInt("paired_count"))
        assertEquals(0, verification.getInt("unpaired_forecast_count"))
        assertEquals(1, verification.getInt("unpaired_observation_count"))
        assertEquals(60, verification.getInt("pairing_tolerance_minutes"))

        val leadTime = verification.getObject("lead_time_hours")!!
        assertEquals(3.0, leadTime.getDouble("min")!!, 0.001)
        assertEquals(4.5, leadTime.getDouble("mean")!!, 0.001)
        assertEquals(6.0, leadTime.getDouble("max")!!, 0.001)

        val temperature = verification.getArray("variables")!!.items
            .map { it as com.ferousco.hydro.core.json.JsonValue.ObjectValue }
            .single { it.getString("variable") == "air_temperature" }
        assertEquals(2, temperature.getInt("sample_count"))
        assertEquals(30.5, temperature.getDouble("mean_forecast")!!, 0.001)
        assertEquals(33.0, temperature.getDouble("mean_observed")!!, 0.001)
        // forecast - observed: 30-33 = -3 and 31-33 = -2.
        assertEquals(-2.5, temperature.getDouble("mean_error")!!, 0.001)
        assertEquals(2.5, temperature.getDouble("mean_absolute_error")!!, 0.001)
        assertEquals(2.55, temperature.getDouble("root_mean_squared_error")!!, 0.01)
        assertEquals(false, temperature.getBoolean("sufficient_for_calibration"))

        val caveats = verification.getArray("caveats")!!.items
            .map { (it as com.ferousco.hydro.core.json.JsonValue.StringValue).value }
        assertTrue(caveats.any { it.contains("not a calibration model") })
        assertTrue(caveats.any { it.contains("indicative") })
    }

    @Test
    fun `forecast verification with no stored forecasts explains the gap instead of guessing`() =
        runBlocking {
            val repository = historyRepository()
            repository.recordObservation(
                TestFixtures.buildObservation(observedAt = now, retrievedAt = now),
                recordId = "obs-only"
            )

            val result = registry(historyRepository = repository).executeJson(
                "query_memory",
                """
                {
                  "latitude": 7.3775,
                  "longitude": 3.9470,
                  "include_forecast_verification": true
                }
                """.trimIndent()
            )
            assertTrue(result is ToolCallOutcome.Success)
            val verification = assertNotNull(result.jsonResult.getObject("forecast_verification"))
            assertEquals(0, verification.getInt("paired_count"))
            assertEquals(0, verification.getArray("variables")!!.items.size)
            assertNull(verification.getObject("lead_time_hours")!!.getDouble("mean"))

            val caveats = verification.getArray("caveats")!!.items
                .map { (it as com.ferousco.hydro.core.json.JsonValue.StringValue).value }
            assertTrue(caveats.any { it.contains("No stored issued forecasts") })
        }

    @Test
    fun `forecast verification rejects out-of-range window and pair count`() = runBlocking {
        val registry = registry()

        val tooLong = registry.executeJson(
            "query_memory",
            """{"latitude": 7.3775, "longitude": 3.9470, "verification_lookback_days": 90}"""
        )
        assertTrue(tooLong is ToolCallOutcome.Failure)
        assertEquals("INVALID_ARGUMENTS", tooLong.errorCode)

        val tooManyPairs = registry.executeJson(
            "query_memory",
            """{"latitude": 7.3775, "longitude": 3.9470, "verification_max_pairs": 500}"""
        )
        assertTrue(tooManyPairs is ToolCallOutcome.Failure)
        assertEquals("INVALID_ARGUMENTS", tooManyPairs.errorCode)
    }

    // ------------------------------------------------------------------
    // 3b. query_memory: latest observation versus yesterday
    // ------------------------------------------------------------------

    @Test
    fun `query_memory compares the latest observation with yesterday when asked`() = runBlocking {
        val repository = historyRepository()
        seedObservation(
            repository,
            validAt = now.minus(Duration.ofDays(1)),
            observedTempC = 30.0,
            observedRh = 60.0,
            id = "obs-yesterday"
        )
        seedObservation(repository, validAt = now, observedTempC = 33.0, observedRh = 70.0, id = "obs-today")

        val result = registry(historyRepository = repository).executeJson(
            "query_memory",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "include_historical_comparison": true
            }
            """.trimIndent()
        )
        assertTrue(result is ToolCallOutcome.Success)
        val comparison = assertNotNull(result.jsonResult.getObject("historical_comparison"))
        assertEquals(true, comparison.getBoolean("available"))
        assertEquals("obs-today", comparison.getString("current_record_id"))
        assertEquals("obs-yesterday", comparison.getString("baseline_record_id"))
        assertEquals(now.toString(), comparison.getString("current_valid_at"))
        assertEquals(now.minus(Duration.ofDays(1)).toString(), comparison.getString("baseline_valid_at"))

        val deltas = comparison.getArray("deltas")!!.items
            .map { it as com.ferousco.hydro.core.json.JsonValue.ObjectValue }
        val temperature = deltas.single { it.getString("variable") == "air_temperature" }
        assertEquals("°C", temperature.getString("unit"))
        assertEquals(33.0, temperature.getDouble("current")!!, 0.001)
        assertEquals(30.0, temperature.getDouble("baseline")!!, 0.001)
        assertEquals(3.0, temperature.getDouble("delta")!!, 0.001)

        val humidity = deltas.single { it.getString("variable") == "relative_humidity" }
        assertEquals(10.0, humidity.getDouble("delta")!!, 0.001)

        val caveats = comparison.getArray("caveats")!!.items
            .map { (it as com.ferousco.hydro.core.json.JsonValue.StringValue).value }
        assertTrue(caveats.any { it.contains("not a climatological normal") })
        assertTrue(caveats.any { it.contains("descriptive") })
    }

    @Test
    fun `query_memory omits the yesterday comparison unless asked`() = runBlocking {
        val repository = historyRepository()
        seedObservation(repository, validAt = now.minus(Duration.ofDays(1)), observedTempC = 30.0, id = "obs-yesterday")
        seedObservation(repository, validAt = now, observedTempC = 33.0, id = "obs-today")

        val result = registry(historyRepository = repository).executeJson(
            "query_memory",
            """{"latitude": 7.3775, "longitude": 3.9470}"""
        )
        assertTrue(result is ToolCallOutcome.Success)
        assertNull(result.jsonResult.getObject("historical_comparison"))
    }

    @Test
    fun `query_memory refuses a stored forecast as yesterday's baseline`() = runBlocking {
        val repository = historyRepository()
        seedObservation(repository, validAt = now, observedTempC = 33.0, id = "obs-today")
        // A forecast issued for yesterday's clock time is not a measurement of yesterday.
        seedForecast(
            repository = repository,
            validAt = now.minus(Duration.ofDays(1)),
            issuedAt = now.minus(Duration.ofDays(1)).minus(Duration.ofHours(3)),
            forecastTempC = 30.0,
            id = "fcst-yesterday"
        )

        val result = registry(historyRepository = repository).executeJson(
            "query_memory",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "include_historical_comparison": true
            }
            """.trimIndent()
        )
        assertTrue(result is ToolCallOutcome.Success)
        val comparison = assertNotNull(result.jsonResult.getObject("historical_comparison"))
        assertEquals(false, comparison.getBoolean("available"))
        assertEquals("NO_BASELINE", comparison.getString("reason"))
        assertTrue(comparison.getString("explanation")!!.contains("tolerance"))
    }

    @Test
    fun `query_memory reports no recent observation instead of inventing a comparison`() = runBlocking {
        val repository = historyRepository()
        seedForecast(
            repository = repository,
            validAt = now,
            issuedAt = now.minus(Duration.ofHours(2)),
            forecastTempC = 30.0,
            id = "fcst-only"
        )

        val result = registry(historyRepository = repository).executeJson(
            "query_memory",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "include_historical_comparison": true
            }
            """.trimIndent()
        )
        assertTrue(result is ToolCallOutcome.Success)
        val comparison = assertNotNull(result.jsonResult.getObject("historical_comparison"))
        assertEquals(false, comparison.getBoolean("available"))
        assertEquals("NO_RECENT_OBSERVATION", comparison.getString("reason"))
    }

    // ------------------------------------------------------------------
    // 4. create_notification respects user preferences
    // ------------------------------------------------------------------

    @Test
    fun `create_notification honours a user who disabled alerts`() = runBlocking {
        val sink = InMemoryNotificationSink(clock)
        val store = InMemoryUserPreferencesStore(
            UserPreferences(alerts = AlertPreferences(enabled = false)),
            clock
        )
        val registry = registry(notificationSink = sink, preferencesStore = store)

        val result = registry.executeJson("create_notification", alertJson())
        assertTrue(result is ToolCallOutcome.Success)
        assertEquals("SUPPRESSED", result.jsonResult.getString("status"))
        assertEquals("USER_DISABLED", result.jsonResult.getString("reason"))
        assertTrue(sink.deliveredIntents().isEmpty())
    }

    @Test
    fun `create_notification suppresses alerts below the user's minimum severity`() = runBlocking {
        val sink = InMemoryNotificationSink(clock)
        val store = InMemoryUserPreferencesStore(
            UserPreferences(
                alerts = AlertPreferences(minimumSeverity = NotificationSeverity.WARNING)
            ),
            clock
        )
        val registry = registry(notificationSink = sink, preferencesStore = store)

        val watch = registry.executeJson("create_notification", alertJson(severity = "WATCH"))
        assertTrue(watch is ToolCallOutcome.Success)
        assertEquals("SUPPRESSED", watch.jsonResult.getString("status"))
        assertEquals("BELOW_MINIMUM_SEVERITY", watch.jsonResult.getString("reason"))

        val warning = registry.executeJson("create_notification", alertJson(severity = "WARNING"))
        assertTrue(warning is ToolCallOutcome.Success)
        assertEquals("SUBMITTED", warning.jsonResult.getString("status"))
        assertEquals(1, sink.deliveredIntents().size)
    }

    @Test
    fun `create_notification applies the user's quiet hours over the engine defaults`() = runBlocking {
        val sink = InMemoryNotificationSink(clock)
        val store = InMemoryUserPreferencesStore(
            UserPreferences(
                alerts = AlertPreferences(
                    quietHoursStart = LocalTime.of(11, 0),
                    quietHoursEnd = LocalTime.of(13, 0)
                )
            ),
            clock
        )
        // Engine default quiet hours are 22:00-07:00, so noon UTC would normally be delivered.
        val registry = registry(notificationSink = sink, preferencesStore = store)

        val result = registry.executeJson("create_notification", alertJson())
        assertTrue(result is ToolCallOutcome.Success)
        assertEquals("SUPPRESSED", result.jsonResult.getString("status"))
        assertEquals("QUIET_HOURS", result.jsonResult.getString("reason"))
        assertEquals("2026-10-26T13:00:00Z", result.jsonResult.getString("retry_after"))
        assertEquals("11:00", result.jsonResult.getObject("policy")!!.getString("quiet_hours_start"))
        assertEquals(true, result.jsonResult.getObject("policy")!!.getBoolean("user_preferences_applied"))
        assertTrue(sink.deliveredIntents().isEmpty())
    }

    @Test
    fun `user preferences can tighten but not loosen the rate limit and dedup floor`() = runBlocking {
        val sink = InMemoryNotificationSink(clock)
        val store = InMemoryUserPreferencesStore(
            UserPreferences(
                alerts = AlertPreferences(maxNotificationsPerHour = 10, dedupWindowHours = 1)
            ),
            clock
        )
        val registry = registry(
            notificationSink = sink,
            preferencesStore = store,
            notificationPolicy = NotificationPolicy(
                maxNotificationsPerHour = 2,
                dedupWindow = Duration.ofHours(6)
            )
        )

        val first = registry.executeJson("create_notification", alertJson(dedupKey = "heat:a"))
        assertTrue(first is ToolCallOutcome.Success)
        assertEquals("SUBMITTED", first.jsonResult.getString("status"))
        val policy = first.jsonResult.getObject("policy")!!
        assertEquals(2, policy.getInt("max_per_hour"))
        assertEquals(360, policy.getInt("dedup_window_minutes"))

        val second = registry.executeJson("create_notification", alertJson(dedupKey = "heat:b"))
        assertEquals("SUBMITTED", (second as ToolCallOutcome.Success).jsonResult.getString("status"))

        val third = registry.executeJson("create_notification", alertJson(dedupKey = "heat:c"))
        assertTrue(third is ToolCallOutcome.Success)
        assertEquals("SUPPRESSED", third.jsonResult.getString("status"))
        assertEquals("RATE_LIMITED", third.jsonResult.getString("reason"))

        // Two hours later the user's 1h dedup preference still cannot undercut the 6h floor.
        clock.advance(Duration.ofHours(2))
        val repeat = registry.executeJson("create_notification", alertJson(dedupKey = "heat:a"))
        assertTrue(repeat is ToolCallOutcome.Success)
        assertEquals("SUPPRESSED", repeat.jsonResult.getString("status"))
        assertEquals("DUPLICATE", repeat.jsonResult.getString("reason"))
    }

    @Test
    fun `create_notification reports when no user preferences were applied`() = runBlocking {
        val sink = InMemoryNotificationSink(clock)
        // A registry wired without a preferences store: the tool must still work, and must say so.
        val registry = HydroToolRegistry(
            listOf(
                com.ferousco.hydro.core.tools.CreateNotificationTool(
                    notificationSink = sink,
                    policy = NotificationPolicy(),
                    preferencesStore = null,
                    clock = clock
                )
            )
        )

        val result = registry.executeJson("create_notification", alertJson())
        assertTrue(result is ToolCallOutcome.Success)
        assertEquals("SUBMITTED", result.jsonResult.getString("status"))
        val policy = result.jsonResult.getObject("policy")!!
        assertEquals(false, policy.getBoolean("user_preferences_applied"))
        assertEquals("WATCH", policy.getString("minimum_severity"))
        assertEquals(3, policy.getInt("max_per_hour"))
    }

    // ------------------------------------------------------------------
    // 5. Engine preference helpers
    // ------------------------------------------------------------------

    @Test
    fun `engine exposes load and update helpers for preferences`() = runBlocking {
        val store = InMemoryUserPreferencesStore(clock = clock)
        val engine = HydroEngine.create(
            historyStore = InMemoryEnvironmentalHistoryStore(),
            preferencesStore = store,
            clock = clock
        )

        assertEquals(
            TemperatureUnitPreference.CELSIUS,
            engine.loadUserPreferences().getOrNull()?.temperatureUnit
        )

        val updated = engine.updateUserPreferences {
            it.copy(temperatureUnit = TemperatureUnitPreference.FAHRENHEIT)
        }.getOrNull()
        assertEquals(TemperatureUnitPreference.FAHRENHEIT, updated?.temperatureUnit)
        assertEquals(
            TemperatureUnitPreference.FAHRENHEIT,
            engine.loadUserPreferences().getOrNull()?.temperatureUnit
        )

        val invalid = engine.updateUserPreferences {
            it.copy(activity = it.activity.copy(bodyMassKg = 5_000.0))
        }
        assertTrue(invalid is EngineResult.Failure)
        assertTrue(invalid.error is EngineError.InvalidInput)
    }

    @Test
    fun `engine supplies a working preferences store by default`() = runBlocking {
        val engine = HydroEngine.create(clock = clock)
        assertNotNull(engine.loadUserPreferences().getOrNull())
        Unit
    }
}
