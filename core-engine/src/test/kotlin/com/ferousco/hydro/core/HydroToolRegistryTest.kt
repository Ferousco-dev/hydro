package com.ferousco.hydro.core

import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.history.InMemoryEnvironmentalHistoryStore
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.model.HistoricalWeatherSeries
import com.ferousco.hydro.core.model.ObservationKind
import com.ferousco.hydro.core.tools.HydroTool
import com.ferousco.hydro.core.tools.HydroToolRegistry
import com.ferousco.hydro.core.tools.SetBasedToolPermissionGate
import com.ferousco.hydro.core.tools.ToolCallOutcome
import com.ferousco.hydro.core.tools.ToolCapability
import com.ferousco.hydro.core.tools.ToolDescriptor
import com.ferousco.hydro.core.tools.ToolFailureSpec
import com.ferousco.hydro.core.tools.ToolObjectSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HydroToolRegistryTest {

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = MutableClock(now)

    private fun buildTestEngine(): Pair<HydroEngine, FakeWeatherProvider> {
        val fakeProvider = FakeWeatherProvider()
        val obs = TestFixtures.buildObservation(
            observedAt = now.minusSeconds(300),
            retrievedAt = now,
            airTempC = 33.0,
            rhPercent = 66.0
        )
        val forecast = TestFixtures.buildHourlyForecast(
            startHour = now,
            hourlySpecs = listOf(
                Triple(34.0, 65.0, 9.0),
                Triple(33.0, 64.0, 7.0),
                Triple(30.0, 68.0, 3.0),
                Triple(28.0, 72.0, 0.5)
            ),
            retrievedAt = now
        )
        val histObs = TestFixtures.buildObservation(
            observedAt = Instant.parse("2026-10-25T12:00:00Z"),
            retrievedAt = now,
            airTempC = 30.5,
            rhPercent = 70.0,
            uvIndex = null,
            kind = ObservationKind.HISTORICAL_REANALYSIS_OBSERVATION
        )
        fakeProvider.currentResult = EngineResult.Success(obs)
        fakeProvider.forecastResult = EngineResult.Success(forecast)
        fakeProvider.historicalResult = EngineResult.Success(
            HistoricalWeatherSeries(
                requestedLocation = TestFixtures.IBADAN,
                effectiveLocation = TestFixtures.IBADAN,
                timeZone = TestFixtures.IBADAN.timeZone!!,
                startDate = LocalDate.of(2026, 10, 25),
                endDate = LocalDate.of(2026, 10, 25),
                retrievedAt = now,
                hourlyObservations = listOf(histObs),
                source = TestFixtures.TEST_OBSERVATION_SOURCE,
                quality = histObs.quality
            )
        )

        val engine = HydroEngine.create(
            weatherProvider = fakeProvider,
            historyStore = InMemoryEnvironmentalHistoryStore(),
            clock = clock
        )
        return engine to fakeProvider
    }

    @Test
    fun `all default AI tools execute cleanly with validated JSON arguments and zero network or LLM`() = runBlocking {
        val (engine, _) = buildTestEngine()
        val registry = engine.toolRegistry

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

        // 1. get_weather
        val r1 = registry.executeJson(
            "get_weather",
            """{"latitude": 7.3775, "longitude": 3.9470, "timezone": "Africa/Lagos"}"""
        )
        assertTrue(r1 is ToolCallOutcome.Success)
        assertEquals("OBSERVED", r1.jsonResult.getString("data_nature"))

        // 2. get_hourly_forecast
        val r2 = registry.executeJson(
            "get_hourly_forecast",
            """{"latitude": 7.3775, "longitude": 3.9470, "forecast_hours": 12}"""
        )
        assertTrue(r2 is ToolCallOutcome.Success)
        assertEquals("FORECAST", r2.jsonResult.getString("data_nature"))

        // 3. get_historical_weather
        val r3 = registry.executeJson(
            "get_historical_weather",
            """{"latitude": 7.3775, "longitude": 3.9470, "start_date": "2026-10-25", "end_date": "2026-10-25"}"""
        )
        assertTrue(r3 is ToolCallOutcome.Success)
        assertEquals("HISTORICAL_REANALYSIS_OBSERVATION", r3.jsonResult.getString("observation_kind"))

        // 4. calculate_heat_risk
        val r4 = registry.executeJson(
            "calculate_heat_risk",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "valid_at": "2026-10-26T12:00:00Z",
              "data_nature": "OBSERVED",
              "air_temperature_celsius": 34.0,
              "relative_humidity_percent": 70.0,
              "uv_index": 8.5
            }
            """.trimIndent()
        )
        assertTrue(r4 is ToolCallOutcome.Success)
        assertEquals("SUPPORTED", r4.jsonResult.getString("status"))
        assertEquals("DANGER", r4.jsonResult.getString("category"))

        // 5. compare_forecast_periods
        val r5 = registry.executeJson(
            "compare_forecast_periods",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "activity_category": "WALKING",
              "duration_minutes": 60,
              "intensity": "MODERATE",
              "earliest_departure": "2026-10-26T12:00:00Z",
              "latest_departure": "2026-10-26T15:00:00Z",
              "step_minutes": 60
            }
            """.trimIndent()
        )
        assertTrue(r5 is ToolCallOutcome.Success)
        assertEquals("EVALUATED", r5.jsonResult.getString("status"))

        // Seed history and invoke 6. query_memory with comparison
        val yest = TestFixtures.buildObservation(
            observedAt = Instant.parse("2026-10-25T12:00:00Z"),
            retrievedAt = Instant.parse("2026-10-25T12:05:00Z"),
            airTempC = 30.0
        )
        val tod = TestFixtures.buildObservation(
            observedAt = Instant.parse("2026-10-26T12:00:00Z"),
            retrievedAt = Instant.parse("2026-10-26T12:05:00Z"),
            airTempC = 33.0
        )
        engine.historyRepository.recordObservation(yest, recordId = "rec-yest")
        engine.historyRepository.recordObservation(tod, recordId = "rec-tod")

        val r6 = registry.executeJson(
            "query_memory",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "radius_km": 15.0,
              "compare_baseline_id": "rec-yest",
              "compare_target_id": "rec-tod",
              "require_observed_baseline": true
            }
            """.trimIndent()
        )
        assertTrue(r6 is ToolCallOutcome.Success)
        assertEquals(2, r6.jsonResult.getInt("record_count"))
        assertEquals(true, r6.jsonResult.getObject("comparison")!!.getBoolean("compatible"))
    }

    @Test
    fun `tool registry rejects unregistered tools, extra parameters, invalid types, policy denials, and timeouts`() = runBlocking {
        val (engine, _) = buildTestEngine()
        val registry = engine.toolRegistry

        // 1. Unregistered / dangerous tool name
        val unreg = registry.executeJson("execute_shell", """{"cmd": "id"}""")
        assertTrue(unreg is ToolCallOutcome.Failure)
        assertEquals("TOOL_NOT_REGISTERED", unreg.errorCode)

        // 2. Extra / smuggled parameter rejected (additionalProperties = false)
        val extraParam = registry.executeJson(
            "get_weather",
            """{"latitude": 7.37, "longitude": 3.94, "override_url": "https://evil.example"}"""
        )
        assertTrue(extraParam is ToolCallOutcome.Failure)
        assertEquals("INVALID_ARGUMENTS", extraParam.errorCode)

        // 3. Out-of-bounds latitude
        val badLat = registry.executeJson(
            "get_weather",
            """{"latitude": 145.0, "longitude": 3.94}"""
        )
        assertTrue(badLat is ToolCallOutcome.Failure)
        assertEquals("INVALID_ARGUMENTS", badLat.errorCode)

        // 4. Wrong JSON type (string instead of number)
        val wrongType = registry.executeJson(
            "get_weather",
            """{"latitude": "7.37", "longitude": 3.94}"""
        )
        assertTrue(wrongType is ToolCallOutcome.Failure)
        assertEquals("INVALID_ARGUMENTS", wrongType.errorCode)

        // 5. Missing required parameter
        val missingReq = registry.executeJson(
            "get_weather",
            """{"latitude": 7.37}"""
        )
        assertTrue(missingReq is ToolCallOutcome.Failure)
        assertEquals("MISSING_REQUIRED_INPUTS", missingReq.errorCode)

        // 6. Permission gate denial (e.g., disallow LOCAL_HISTORY_READ)
        val restrictedGate = SetBasedToolPermissionGate(setOf(ToolCapability.DETERMINISTIC_ANALYSIS))
        val denied = registry.executeJson(
            "query_memory",
            """{"latitude": 7.37, "longitude": 3.94}""",
            permissionGate = restrictedGate
        )
        assertTrue(denied is ToolCallOutcome.Failure)
        assertEquals("PERMISSION_DENIED", denied.errorCode)
        assertFalse(denied.retryable)

        // 7. Bounded tool timeout enforcement
        val slowTool = object : HydroTool<Unit, Unit> {
            override val descriptor = ToolDescriptor(
                name = "slow_test_tool",
                description = "Deliberately slow test tool",
                requiredCapabilities = emptySet(),
                parameterSchema = ToolObjectSchema(emptyList()),
                resultSchema = ToolObjectSchema(emptyList()),
                failureSpec = ToolFailureSpec(
                    executionTimeout = Duration.ofMillis(40),
                    documentedErrorCodes = listOf("TOOL_TIMEOUT")
                )
            )
            override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue) = EngineResult.Success(Unit)
            override suspend fun executeTyped(params: Unit): EngineResult<Unit> {
                delay(500)
                return EngineResult.Success(Unit)
            }
            override fun encodeResult(result: Unit) = JsonValue.ObjectValue(emptyMap())
        }
        val slowRegistry = HydroToolRegistry(listOf(slowTool))
        val timedOut = slowRegistry.executeJson("slow_test_tool", "{}")
        assertTrue(timedOut is ToolCallOutcome.Failure)
        assertEquals("TOOL_TIMEOUT", timedOut.errorCode)
        assertTrue(timedOut.retryable)

        // 8. Exported JSON tool declarations are valid RFC-8259 JSON with strict additionalProperties=false
        val exportedJson = registry.exportToolDeclarationsJson()
        val parsedExport = com.ferousco.hydro.core.json.SafeJsonCodec.parse(exportedJson).getOrNull()
        assertTrue(parsedExport is JsonValue.ArrayValue)
        assertEquals(24, parsedExport.items.size)
        for (item in parsedExport.items) {
            val decl = item as JsonValue.ObjectValue
            assertEquals(false, decl.getObject("parameters")!!.getBoolean("additionalProperties"))
        }

        // 9. calculate_heat_risk with omitted required humidity returns INCOMPLETE assessment cleanly
        val incompleteAssessmentCall = registry.executeJson(
            "calculate_heat_risk",
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "valid_at": "2026-10-26T12:00:00Z",
              "data_nature": "OBSERVED",
              "air_temperature_celsius": 34.0
            }
            """.trimIndent()
        )
        assertTrue(incompleteAssessmentCall is ToolCallOutcome.Success)
        assertEquals("INCOMPLETE", incompleteAssessmentCall.jsonResult.getString("status"))
    }
}
