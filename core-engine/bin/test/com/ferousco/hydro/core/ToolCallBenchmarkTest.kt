package com.ferousco.hydro.core

import com.ferousco.hydro.core.ai.HydroOrchestratorConfig
import com.ferousco.hydro.core.ai.LlmActiveRuntimeInfo
import com.ferousco.hydro.core.ai.LlmGenerationRequest
import com.ferousco.hydro.core.ai.LlmGenerationResponse
import com.ferousco.hydro.core.ai.LlmExecutionBackend
import com.ferousco.hydro.core.ai.LlmInputContent
import com.ferousco.hydro.core.ai.LlmMessageRole
import com.ferousco.hydro.core.ai.LlmModelCatalog
import com.ferousco.hydro.core.ai.LlmModelSpec
import com.ferousco.hydro.core.ai.OnDeviceLlmModelSpec
import com.ferousco.hydro.core.ai.LlmModalityMode
import com.ferousco.hydro.core.ai.LocalModelArtifactValidator
import com.ferousco.hydro.core.ai.LlmReadiness
import com.ferousco.hydro.core.ai.LlmClient
import com.ferousco.hydro.core.ai.benchmark.BenchmarkCaseOutcome
import com.ferousco.hydro.core.ai.benchmark.BenchmarkPrompt
import com.ferousco.hydro.core.ai.benchmark.ToolCallBenchmarkHarness
import com.ferousco.hydro.core.ai.benchmark.ToolCallBenchmarkSuite
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.history.InMemoryEnvironmentalHistoryStore
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.HistoricalWeatherSeries
import com.ferousco.hydro.core.model.ObservationKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the model-selection harness itself. These tests are deliberately model-free: a scripted
 * client stands in for any on-device model, so the measurement logic is validated without a
 * multi-gigabyte artifact or an inference runtime.
 */
class ToolCallBenchmarkTest {

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = MutableClock(now)

    private fun buildEngine(): HydroEngine {
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
        val historicalObservation = TestFixtures.buildObservation(
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
                hourlyObservations = listOf(historicalObservation),
                source = TestFixtures.TEST_HISTORICAL_SOURCE,
                quality = historicalObservation.quality
            )
        )

        return HydroEngine.create(
            weatherProvider = fakeProvider,
            historyStore = InMemoryEnvironmentalHistoryStore(),
            clock = clock
        )
    }

    private fun harness(engine: HydroEngine, suite: ToolCallBenchmarkSuite = ToolCallBenchmarkSuite.HYDRO_CORE_V1) =
        ToolCallBenchmarkHarness(
            engine = engine,
            suite = suite,
            orchestratorConfig = HydroOrchestratorConfig(maxToolRounds = 4, maxToolCallsPerRound = 1),
            userLocation = TestFixtures.IBADAN
        )

    @Test
    fun `catalog exposes the three product candidates as unverified and keeps Gemma metadata verified`() {
        val candidates = LlmModelCatalog.BENCHMARK_CANDIDATES
        assertEquals(3, candidates.size)
        assertEquals(
            setOf("Qwen2.5-1.5B-Instruct", "Llama-3.2-1B-Instruct", "FunctionGemma-270M"),
            candidates.map { it.modelId }.toSet()
        )
        // Candidate metadata must not masquerade as verified publisher facts.
        assertTrue(candidates.all { !it.metadataVerified })
        assertTrue(candidates.all { it.expectedSizeBytes == null })

        // Strict size validation must refuse an unverified spec rather than silently passing it.
        val tempFile = kotlin.io.path.createTempFile("hydro-benchmark", ".gguf").toFile()
        try {
            tempFile.writeBytes(ByteArray(2 * 1024 * 1024))
            val readiness = LocalModelArtifactValidator.validate(
                candidateFiles = listOf(tempFile),
                modelSpec = candidates.first(),
                strictOfficialSize = true
            )
            assertTrue(readiness is LlmReadiness.InvalidArtifact)
        } finally {
            tempFile.delete()
        }

        assertTrue(LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM.metadataVerified)
    }

    @Test
    fun `suite rejects duplicate prompt ids`() {
        assertFailsWith<IllegalArgumentException> {
            ToolCallBenchmarkSuite(
                name = "duplicate-suite",
                prompts = listOf(
                    BenchmarkPrompt("same-id", "first", setOf("get_weather")),
                    BenchmarkPrompt("same-id", "second", setOf("get_weather"))
                )
            )
        }
    }

    @Test
    fun `well-behaved scripted model passes every benchmark case`() = runBlocking {
        val report = harness(buildEngine()).run(
            ScriptedBenchmarkClient(LlmModelCatalog.QWEN2_5_1_5B_INSTRUCT, now)
        )

        assertEquals(ToolCallBenchmarkSuite.HYDRO_CORE_V1.prompts.size, report.totalCases)
        assertEquals(report.totalCases, report.passedCases, report.summary())
        assertEquals(1.0, report.toolSelectionAccuracy)
        assertEquals(0, report.malformedToolCallCount)
        assertEquals(0, report.failedCases)
        assertEquals(1.0, report.schemaValidToolCallRate)
        assertTrue(report.totalToolCalls >= report.totalCases)
    }

    @Test
    fun `malformed tool calls are counted and drop tool-selection accuracy`() = runBlocking {
        val report = harness(buildEngine()).run(
            ScriptedBenchmarkClient(
                modelSpec = LlmModelCatalog.LLAMA_3_2_1B_INSTRUCT,
                now = now,
                alwaysMalformed = true
            )
        )

        assertEquals(0, report.passedCases)
        assertEquals(0.0, report.toolSelectionAccuracy)
        assertTrue(report.malformedToolCallCount > 0, report.summary())
        assertTrue(report.failedCases > 0, report.summary())
        assertEquals(
            report.malformedToolCallCount,
            report.toolErrorCodeCounts["INVALID_TOOL_CALL_SYNTAX"]
        )
    }

    @Test
    fun `missing model artifact reports every case as model-unavailable`() = runBlocking {
        val report = harness(buildEngine()).run(
            ScriptedBenchmarkClient(
                modelSpec = LlmModelCatalog.FUNCTIONGEMMA_270M,
                now = now,
                readiness = LlmReadiness.NotInstalled(
                    modelSpec = LlmModelCatalog.FUNCTIONGEMMA_270M,
                    searchedPaths = listOf("/models/functiongemma-270m.task"),
                    provisioningHint = "Provision the model artifact first."
                )
            )
        )

        assertEquals(report.totalCases, report.modelUnavailableCases)
        assertEquals(0, report.passedCases)
        assertEquals(0, report.totalToolCalls)
        assertTrue(report.results.all { it.outcome == BenchmarkCaseOutcome.MODEL_UNAVAILABLE })
    }

    @Test
    fun `report serializes to round-trippable JSON`() = runBlocking {
        val report = harness(buildEngine()).run(
            ScriptedBenchmarkClient(LlmModelCatalog.QWEN2_5_1_5B_INSTRUCT, now)
        )

        val parsed = SafeJsonCodec.parseObject(report.toJsonString()).getOrNull()!!
        assertEquals("hydro-core-v1", parsed.getString("suite"))
        assertEquals("Qwen2.5-1.5B-Instruct", parsed.getString("model_id"))
        assertEquals(report.totalCases.toLong(), parsed.getLong("total_cases"))
        assertEquals(1.0, parsed.getDouble("tool_selection_accuracy"))
        assertEquals(report.totalCases, parsed.getArray("cases")!!.items.size)
        assertFalse(parsed.getString("model_id")!!.contains("gemma", ignoreCase = true))
    }

    /**
     * Deterministic stand-in for any on-device model.
     *
     * It derives the next required tool from the user prompt and the tools already executed, which
     * is exactly the behaviour the harness needs to score. No inference runtime is involved.
     */
    private class ScriptedBenchmarkClient(
        override val modelSpec: OnDeviceLlmModelSpec,
        private val now: Instant,
        private val alwaysMalformed: Boolean = false,
        private val readiness: LlmReadiness = LlmReadiness.Ready(
            modelSpec = modelSpec,
            resolvedFilePath = "/tmp/${modelSpec.expectedFileName}",
            actualFileSizeBytes = 2L * 1024L * 1024L,
            matchesOfficialReleaseSize = false
        )
    ) : LlmClient {

        private var initialized = false
        private val runtimeInfo = LlmActiveRuntimeInfo(
            modelSpec = modelSpec,
            activeBackend = LlmExecutionBackend.CPU,
            modalityMode = LlmModalityMode.TEXT_ONLY
        )

        override fun checkReadiness(): LlmReadiness = readiness

        override fun activeRuntimeInfoOrNull(): LlmActiveRuntimeInfo? =
            if (initialized) runtimeInfo else null

        override suspend fun initialize(): EngineResult<LlmActiveRuntimeInfo> {
            initialized = true
            return EngineResult.Success(runtimeInfo)
        }

        override suspend fun generate(request: LlmGenerationRequest): EngineResult<LlmGenerationResponse> {
            val userText = request.messages
                .first()
                .contents
                .filterIsInstance<LlmInputContent.Text>()
                .joinToString(" ") { it.text }
                .lowercase()

            if (alwaysMalformed) {
                return EngineResult.Success(
                    LlmGenerationResponse(
                        text = """{"tool_call":{"name":"get_weather","arguments":{"latitude":7.3""",
                        runtimeInfo = runtimeInfo
                    )
                )
            }

            val alreadyCalled = request.messages
                .filter { it.role == LlmMessageRole.TOOL }
                .flatMap { message -> message.contents.filterIsInstance<LlmInputContent.ToolResult>() }
                .map { it.toolName }
                .toSet()

            val nextTool = requiredToolsFor(userText).firstOrNull { it !in alreadyCalled }
            val text = if (nextTool == null) {
                "Deterministic tool results processed. See the reported values above."
            } else {
                toolCallJson(nextTool)
            }

            return EngineResult.Success(LlmGenerationResponse(text = text, runtimeInfo = runtimeInfo))
        }

        override fun generateStream(request: LlmGenerationRequest): Flow<String> = flowOf("")

        override fun close() {
            initialized = false
        }

        private fun requiredToolsFor(userText: String): List<String> = buildList {
            if ("capital of france" in userText) return@buildList
            if ("yesterday afternoon" in userText) {
                add("get_historical_weather")
            } else if ("compare with yesterday" in userText) {
                add("query_memory")
            }
            if ("walk" in userText && "window" in userText) add("compare_forecast_periods")
            if ("heat index" in userText || "risky" in userText) add("calculate_heat_risk")
            if ("current conditions" in userText || "right now" in userText) add("get_weather")
        }

        private fun toolCallJson(toolName: String): String {
            val arguments = when (toolName) {
                "get_weather" -> """{"latitude":7.3775,"longitude":3.9470,"timezone":"Africa/Lagos"}"""
                "get_hourly_forecast" -> """{"latitude":7.3775,"longitude":3.9470,"forecast_hours":24}"""
                "get_historical_weather" ->
                    """{"latitude":7.3775,"longitude":3.9470,"start_date":"2026-10-25","end_date":"2026-10-25"}"""
                "calculate_heat_risk" ->
                    """{"latitude":7.3775,"longitude":3.9470,"valid_at":"$now","data_nature":"OBSERVED",""" +
                        """"air_temperature_celsius":33.0,"relative_humidity_percent":66.0}"""
                "compare_forecast_periods" ->
                    """{"latitude":7.3775,"longitude":3.9470,"activity_category":"WALKING",""" +
                        """"duration_minutes":120,"earliest_departure":"$now",""" +
                        """"latest_departure":"${now.plusSeconds(3 * 3600)}"}"""
                "query_memory" -> """{"latitude":7.3775,"longitude":3.9470}"""
                else -> "{}"
            }
            return """{"tool_call":{"name":"$toolName","arguments":$arguments}}"""
        }
    }
}
