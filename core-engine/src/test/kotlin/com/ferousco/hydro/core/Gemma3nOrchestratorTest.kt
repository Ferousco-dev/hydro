package com.ferousco.hydro.core

import com.ferousco.hydro.core.ai.Gemma3nModelSpec
import com.ferousco.hydro.core.ai.HydroAssistantOutcome
import com.ferousco.hydro.core.ai.HydroGemmaOrchestrator
import com.ferousco.hydro.core.ai.HydroGemmaOrchestratorConfig
import com.ferousco.hydro.core.ai.LlmActiveRuntimeInfo
import com.ferousco.hydro.core.ai.LlmGenerationRequest
import com.ferousco.hydro.core.ai.LlmGenerationResponse
import com.ferousco.hydro.core.ai.LlmHardwareBackend
import com.ferousco.hydro.core.ai.LlmInputContent
import com.ferousco.hydro.core.ai.LlmMessageRole
import com.ferousco.hydro.core.ai.LlmModalityMode
import com.ferousco.hydro.core.ai.LlmNativeToolCall
import com.ferousco.hydro.core.ai.LocalModelArtifactValidator
import com.ferousco.hydro.core.ai.ModelArtifactReadiness
import com.ferousco.hydro.core.ai.OnDeviceLlmClient
import com.ferousco.hydro.core.ai.orderedTextFirstForLiteRt
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.provider.CachePolicy
import com.ferousco.hydro.core.tools.SetBasedToolPermissionGate
import com.ferousco.hydro.core.tools.ToolCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Gemma3nOrchestratorTest {

    private class ScriptedGemma3nClient(
        override val modelSpec: Gemma3nModelSpec = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM,
        private val readiness: ModelArtifactReadiness,
        private val runtimeInfo: LlmActiveRuntimeInfo = LlmActiveRuntimeInfo(
            modelSpec = modelSpec,
            activeBackend = LlmHardwareBackend.GPU,
            modalityMode = LlmModalityMode.MULTIMODAL_TEXT_VISION_AUDIO
        ),
        private val scriptedTurns: (roundIndex: Int, request: LlmGenerationRequest) -> LlmGenerationResponse
    ) : OnDeviceLlmClient {
        val capturedRequests = mutableListOf<LlmGenerationRequest>()
        private var initialized = false

        override fun checkArtifactReadiness(): ModelArtifactReadiness = readiness

        override fun activeRuntimeInfoOrNull(): LlmActiveRuntimeInfo? =
            if (initialized) runtimeInfo else null

        override suspend fun initialize(): EngineResult<LlmActiveRuntimeInfo> {
            initialized = true
            return EngineResult.Success(runtimeInfo)
        }

        override suspend fun generate(request: LlmGenerationRequest): EngineResult<LlmGenerationResponse> {
            capturedRequests.add(request)
            val turnIdx = capturedRequests.size - 1
            return EngineResult.Success(scriptedTurns(turnIdx, request))
        }

        override fun generateStream(request: LlmGenerationRequest): Flow<String> = flowOf("stream")

        override fun close() {
            initialized = false
        }
    }

    @Test
    fun `Gemma 3n E2B-IT model spec and local artifact validator enforce RAM, extension, and size guards`() {
        val spec = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM
        assertEquals("google/gemma-3n-E2B-it-litert-lm", spec.huggingFaceRepoId)
        assertEquals("gemma-3n-E2B-it-int4.litertlm", spec.expectedFileName)
        assertEquals(3_655_827_456L, spec.expectedSizeBytes)
        assertEquals(8, spec.minDeviceRamGb)
        assertEquals(4096, spec.liteRtMaxContextTokens)
        assertTrue(spec.supportsVision && spec.supportsAudio)

        val tempDir = Files.createTempDirectory("hydro-gemma-test").toFile()
        try {
            val missingFile = java.io.File(tempDir, spec.expectedFileName)
            val notInstalled = LocalModelArtifactValidator.validate(listOf(missingFile), spec)
            assertTrue(notInstalled is ModelArtifactReadiness.NotInstalled)

            // Insufficient RAM (4 GB device < 8 GB required for E2B)
            val lowRam = LocalModelArtifactValidator.validate(
                candidateFiles = listOf(missingFile),
                modelSpec = spec,
                detectedDeviceRamBytes = 4L * 1024L * 1024L * 1024L
            )
            assertTrue(lowRam is ModelArtifactReadiness.InsufficientDeviceMemory)

            // Wrong file extension
            val badExtFile = java.io.File(tempDir, "model.bin").apply { writeBytes(ByteArray(2048)) }
            val invalidExt = LocalModelArtifactValidator.validate(
                candidateFiles = listOf(badExtFile),
                modelSpec = spec,
                minimumBytes = 1024L
            )
            assertTrue(invalidExt is ModelArtifactReadiness.InvalidArtifact)

            // Valid custom-sized .litertlm test artifact with 8 GB RAM
            missingFile.writeBytes(ByteArray(4096))
            val ready = LocalModelArtifactValidator.validate(
                candidateFiles = listOf(missingFile),
                modelSpec = spec,
                detectedDeviceRamBytes = 8L * 1024L * 1024L * 1024L,
                minimumBytes = 1024L
            )
            assertTrue(ready is ModelArtifactReadiness.Ready)
            assertFalse(ready.matchesOfficialReleaseSize)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `multimodal inputs place Text content strictly before Image and Audio parts for LiteRT-LM`() {
        val mixed = listOf(
            LlmInputContent.ImageFile("/storage/sky.jpg"),
            LlmInputContent.AudioBytes(byteArrayOf(1, 2, 3)),
            LlmInputContent.Text("Assess outdoor heat and sky glare")
        )
        val ordered = mixed.orderedTextFirstForLiteRt()
        assertTrue(ordered[0] is LlmInputContent.Text)
        assertTrue(ordered[1] is LlmInputContent.ImageFile)
        assertTrue(ordered[2] is LlmInputContent.AudioBytes)
    }

    @Test
    fun `HydroGemmaOrchestrator executes multi-step deterministic tools and records provenance`() = runBlocking {
        val fixedNow = Instant.parse("2026-10-26T12:10:00Z")
        val clock = MutableClock(fixedNow)
        val fakeProvider = FakeWeatherProvider().apply {
            currentResult = EngineResult.Success(
                TestFixtures.buildObservation(
                    airTempC = 35.0,
                    rhPercent = 68.0,
                    uvIndex = 8.5,
                    observedAt = Instant.parse("2026-10-26T12:00:00Z"),
                    retrievedAt = fixedNow
                )
            )
        }
        val engine = HydroEngine.create(weatherProvider = fakeProvider, clock = clock)

        val readyState = ModelArtifactReadiness.Ready(
            modelSpec = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM,
            resolvedFilePath = "/data/user/0/com.ferousco.hydro/files/models/gemma-3n-E2B-it-int4.litertlm",
            actualFileSizeBytes = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM.expectedSizeBytes,
            matchesOfficialReleaseSize = true
        )

        val scriptedClient = ScriptedGemma3nClient(readiness = readyState) { round, req ->
            val runtime = LlmActiveRuntimeInfo(
                modelSpec = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM,
                activeBackend = LlmHardwareBackend.GPU,
                modalityMode = LlmModalityMode.MULTIMODAL_TEXT_VISION_AUDIO
            )
            when (round) {
                // Round 0: Gemma 3n emits prompt-based JSON tool_call for current weather
                0 -> LlmGenerationResponse(
                    text = """```tool_call
{"tool_call":{"name":"get_current_weather","arguments":{"latitude":7.3775,"longitude":3.9470,"timezone":"Africa/Lagos"}}}
```""",
                    runtimeInfo = runtime
                )
                // Round 1: Gemma 3n inspects weather tool result and invokes deterministic calculate_heat_assessment via native ToolCall
                1 -> {
                    val lastMsg = req.messages.last()
                    assertEquals(LlmMessageRole.TOOL, lastMsg.role)
                    val toolJson = (lastMsg.contents.single() as LlmInputContent.ToolResult).resultJson
                    val tempC = toolJson.getObject("variables")!!
                        .getObject("air_temperature")!!.getDouble("value")!!
                    val rh = toolJson.getObject("variables")!!
                        .getObject("relative_humidity")!!.getDouble("value")!!

                    LlmGenerationResponse(
                        text = "",
                        nativeToolCalls = listOf(
                            LlmNativeToolCall(
                                toolName = "calculate_heat_assessment",
                                arguments = JsonValue.ObjectValue(
                                    linkedMapOf(
                                        "latitude" to SafeJsonCodec.number(7.3775),
                                        "longitude" to SafeJsonCodec.number(3.9470),
                                        "valid_at" to JsonValue.StringValue("2026-10-26T12:00:00Z"),
                                        "data_nature" to JsonValue.StringValue("OBSERVED"),
                                        "air_temperature_celsius" to SafeJsonCodec.number(tempC),
                                        "relative_humidity_percent" to SafeJsonCodec.number(rh),
                                        "uv_index" to SafeJsonCodec.number(8.5)
                                    )
                                )
                            )
                        ),
                        runtimeInfo = runtime
                    )
                }
                // Round 2: Gemma 3n produces grounded summary citing deterministic tool outputs
                else -> {
                    val assessmentToolJson = (req.messages.last().contents.single() as LlmInputContent.ToolResult).resultJson
                    val category = assessmentToolJson.getString("category")
                    val hiC = assessmentToolJson.getDouble("heat_index_celsius")!!
                    LlmGenerationResponse(
                        text = "Model-assimilated current conditions in Ibadan indicate NWS Heat Index category $category " +
                            "(${String.format(java.util.Locale.US, "%.1f", hiC)} °C) with VERY_HIGH UV (8.5).",
                        runtimeInfo = runtime
                    )
                }
            }
        }

        val orchestrator = engine.createGemmaOrchestrator(scriptedClient)
        val outcome = orchestrator.respond(
            userPrompt = "How hot is it right now in Ibadan?",
            userLocationContext = TestFixtures.IBADAN
        )

        assertTrue(outcome is HydroAssistantOutcome.Responded)
        assertTrue(outcome.responseText.contains("DANGER"))
        // Automatic Open-Meteo attribution appended because model response omitted it
        assertTrue(outcome.responseText.contains("Open-Meteo", ignoreCase = true))
        assertEquals(2, outcome.grounding.executedTools.size)
        assertEquals("get_current_weather", outcome.grounding.executedTools[0].toolName)
        assertEquals("calculate_heat_assessment", outcome.grounding.executedTools[1].toolName)
        assertTrue(outcome.grounding.executedTools.all { it.succeeded })
        assertTrue(outcome.grounding.usedModelAssimilatedCurrent)
        assertFalse(outcome.grounding.usedCachedWeather)
    }

    @Test
    fun `orchestrator handles missing model file, cached fallback disclosure, disallowed tools, and round limits`() = runBlocking {
        val fixedNow = Instant.parse("2026-10-26T12:10:00Z")
        val clock = MutableClock(fixedNow)
        val fakeProvider = FakeWeatherProvider().apply {
            currentResult = EngineResult.Success(
                TestFixtures.buildObservation(retrievedAt = fixedNow)
            )
        }
        val engine = HydroEngine.create(weatherProvider = fakeProvider, clock = clock)

        // 1. Missing .litertlm model artifact degrades cleanly to ModelUnavailable
        val missingClient = ScriptedGemma3nClient(
            readiness = ModelArtifactReadiness.NotInstalled(
                modelSpec = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM,
                searchedPaths = listOf("/files/models/gemma-3n-E2B-it-int4.litertlm"),
                provisioningHint = "Download gemma-3n-E2B-it-int4.litertlm"
            )
        ) { _, _ -> error("Should not invoke generate when model is not installed") }

        val unavailableOut = engine.createGemmaOrchestrator(missingClient).respond("Check weather")
        assertTrue(unavailableOut is HydroAssistantOutcome.ModelUnavailable)

        // 2. Prime cache, switch provider offline, and verify mandatory cache disclosure
        assertTrue(engine.weatherRepository.getCurrentObservation(TestFixtures.IBADAN, CachePolicy.NETWORK_ONLY).isSuccess)
        fakeProvider.currentResult = EngineResult.Failure(EngineError.NetworkUnavailable("Offline"))
        clock.advance(Duration.ofMinutes(15))

        val ready = ModelArtifactReadiness.Ready(
            modelSpec = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM,
            resolvedFilePath = "/models/gemma-3n-E2B-it-int4.litertlm",
            actualFileSizeBytes = 3_655_827_456L,
            matchesOfficialReleaseSize = true
        )
        val textOnlyRuntime = LlmActiveRuntimeInfo(
            Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM,
            LlmHardwareBackend.CPU,
            LlmModalityMode.TEXT_ONLY
        )
        val cacheTestClient = ScriptedGemma3nClient(
            readiness = ready,
            runtimeInfo = textOnlyRuntime
        ) { round, _ ->
            if (round == 0) {
                LlmGenerationResponse(
                    text = """{"tool_call":{"name":"get_current_weather","arguments":{"latitude":7.3775,"longitude":3.9470,"timezone":"Africa/Lagos"}}}""",
                    runtimeInfo = textOnlyRuntime
                )
            } else {
                // Intentionally omit mentioning cache in model prose to test mandatory disclosure guard
                LlmGenerationResponse(
                    text = "Temperature in Ibadan is 31.0 °C (Weather data by Open-Meteo.com).",
                    runtimeInfo = textOnlyRuntime
                )
            }
        }
        val cachedResp = engine.createGemmaOrchestrator(cacheTestClient).respond("Weather now?")
        assertTrue(cachedResp is HydroAssistantOutcome.Responded)
        assertTrue(cachedResp.grounding.usedCachedWeather)
        assertTrue(cachedResp.responseText.contains("local cache", ignoreCase = true))

        // 3. Text-only fallback rejects multimodal image attachment explicitly
        val multimodalRejected = engine.createGemmaOrchestrator(cacheTestClient).respond(
            userPrompt = "Analyze this sky photo",
            additionalMedia = listOf(LlmInputContent.ImageFile("/tmp/sky.jpg"))
        )
        assertTrue(multimodalRejected is HydroAssistantOutcome.Failed)
        assertTrue(multimodalRejected.error is EngineError.UnsupportedCalculation)

        // 4. Hallucinated unregistered tool & permission gate denial & maxToolRounds loop protection
        val loopingHallucinatingClient = ScriptedGemma3nClient(readiness = ready) { round, _ ->
            val info = LlmActiveRuntimeInfo(
                Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM,
                LlmHardwareBackend.CPU,
                LlmModalityMode.TEXT_ONLY
            )
            if (round == 0) {
                LlmGenerationResponse(
                    text = """{"tool_call":{"name":"execute_shell","arguments":{"cmd":"whoami"}}}""",
                    runtimeInfo = info
                )
            } else {
                LlmGenerationResponse(
                    text = """{"tool_call":{"name":"query_local_history","arguments":{"latitude":7.3775,"longitude":3.9470}}}""",
                    runtimeInfo = info
                )
            }
        }
        val restrictedGate = SetBasedToolPermissionGate(setOf(ToolCapability.DETERMINISTIC_ANALYSIS))
        val boundedOrchestrator = engine.createGemmaOrchestrator(
            llmClient = loopingHallucinatingClient,
            config = HydroGemmaOrchestratorConfig(maxToolRounds = 2)
        )
        val loopFailure = boundedOrchestrator.respond("Test loop", permissionGate = restrictedGate)
        assertTrue(loopFailure is HydroAssistantOutcome.Failed)
        assertEquals(2, loopFailure.partialToolTraces.size)
        assertEquals("TOOL_NOT_REGISTERED", loopFailure.partialToolTraces[0].errorCode)
        assertEquals("PERMISSION_DENIED", loopFailure.partialToolTraces[1].errorCode)
    }
}
