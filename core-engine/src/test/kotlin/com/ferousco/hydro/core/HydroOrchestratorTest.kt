package com.ferousco.hydro.core

import com.ferousco.hydro.core.ai.LlmModelCatalog
import com.ferousco.hydro.core.ai.LlmModelSpec
import com.ferousco.hydro.core.ai.HydroAssistantOutcome
import com.ferousco.hydro.core.ai.HydroOrchestrator
import com.ferousco.hydro.core.ai.HydroOrchestratorConfig
import com.ferousco.hydro.core.ai.LlmActiveRuntimeInfo
import com.ferousco.hydro.core.ai.LlmGenerationRequest
import com.ferousco.hydro.core.ai.LlmGenerationResponse
import com.ferousco.hydro.core.ai.LlmExecutionBackend
import com.ferousco.hydro.core.ai.LlmInputContent
import com.ferousco.hydro.core.ai.LlmMessageRole
import com.ferousco.hydro.core.ai.LlmModalityMode
import com.ferousco.hydro.core.ai.LlmNativeToolCall
import com.ferousco.hydro.core.ai.LocalModelArtifactValidator
import com.ferousco.hydro.core.ai.LlmReadiness
import com.ferousco.hydro.core.ai.LlmClient
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

class HydroOrchestratorTest {

    private class ScriptedGemma3nClient(
        override val modelSpec: LlmModelSpec = LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM,
        private val readiness: LlmReadiness,
        private val runtimeInfo: LlmActiveRuntimeInfo = LlmActiveRuntimeInfo(
            modelSpec = modelSpec,
            activeBackend = LlmExecutionBackend.GPU,
            modalityMode = LlmModalityMode.MULTIMODAL_TEXT_VISION_AUDIO
        ),
        private val scriptedTurns: (roundIndex: Int, request: LlmGenerationRequest) -> LlmGenerationResponse
    ) : LlmClient {
        val capturedRequests = mutableListOf<LlmGenerationRequest>()
        private var initialized = false

        override fun checkReadiness(): LlmReadiness = readiness

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
        val spec = LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM
        assertEquals("google/gemma-3n-E2B-it-litert-lm", spec.modelId)
        assertEquals("gemma-3n-E2B-it-int4.litertlm", spec.expectedFileName)
        assertEquals(3_655_827_456L, spec.expectedSizeBytes)
        assertEquals(8, spec.minDeviceRamGb)
        assertEquals(4096, spec.runtimeMaxContextTokens)
        assertTrue(spec.supportsVision && spec.supportsAudio)

        val tempDir = Files.createTempDirectory("hydro-gemma-test").toFile()
        try {
            val missingFile = java.io.File(tempDir, spec.expectedFileName)
            val notInstalled = LocalModelArtifactValidator.validate(listOf(missingFile), spec)
            assertTrue(notInstalled is LlmReadiness.NotInstalled)

            // Insufficient RAM (4 GB device < 8 GB required for E2B)
            val lowRam = LocalModelArtifactValidator.validate(
                candidateFiles = listOf(missingFile),
                modelSpec = spec,
                detectedDeviceRamBytes = 4L * 1024L * 1024L * 1024L
            )
            assertTrue(lowRam is LlmReadiness.InsufficientDeviceMemory)

            // Wrong file extension
            val badExtFile = java.io.File(tempDir, "model.bin").apply { writeBytes(ByteArray(2048)) }
            val invalidExt = LocalModelArtifactValidator.validate(
                candidateFiles = listOf(badExtFile),
                modelSpec = spec,
                minimumBytes = 1024L
            )
            assertTrue(invalidExt is LlmReadiness.InvalidArtifact)

            // Valid custom-sized .litertlm test artifact with 8 GB RAM
            missingFile.writeBytes(ByteArray(4096))
            val ready = LocalModelArtifactValidator.validate(
                candidateFiles = listOf(missingFile),
                modelSpec = spec,
                detectedDeviceRamBytes = 8L * 1024L * 1024L * 1024L,
                minimumBytes = 1024L
            )
            assertTrue(ready is LlmReadiness.Ready)
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
    fun `HydroOrchestrator executes multi-step deterministic tools and records provenance`() = runBlocking {
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

        val readyState = LlmReadiness.Ready(
            modelSpec = LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM,
            resolvedFilePath = "/data/user/0/com.ferousco.hydro/files/models/gemma-3n-E2B-it-int4.litertlm",
            actualFileSizeBytes = requireNotNull(LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM.expectedSizeBytes),
            matchesOfficialReleaseSize = true
        )

        val scriptedClient = ScriptedGemma3nClient(readiness = readyState) { round, req ->
            val runtime = LlmActiveRuntimeInfo(
                modelSpec = LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM,
                activeBackend = LlmExecutionBackend.GPU,
                modalityMode = LlmModalityMode.MULTIMODAL_TEXT_VISION_AUDIO
            )
            when (round) {
                // Round 0: Gemma 3n emits prompt-based JSON tool_call for current weather
                0 -> LlmGenerationResponse(
                    text = """```tool_call
{"tool_call":{"name":"get_weather","arguments":{"latitude":7.3775,"longitude":3.9470,"timezone":"Africa/Lagos"}}}
```""",
                    runtimeInfo = runtime
                )
                // Round 1: Gemma 3n inspects weather tool result and invokes deterministic calculate_heat_risk via native ToolCall
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
                                toolName = "calculate_heat_risk",
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

        val orchestrator = engine.createOrchestrator(scriptedClient)
        val outcome = orchestrator.respond(
            userPrompt = "How hot is it right now in Ibadan?",
            userLocationContext = TestFixtures.IBADAN
        )

        assertTrue(outcome is HydroAssistantOutcome.Responded)
        assertTrue(outcome.responseText.contains("DANGER"))
        // Automatic Open-Meteo attribution appended because model response omitted it
        assertTrue(outcome.responseText.contains("Open-Meteo", ignoreCase = true))
        assertEquals(2, outcome.grounding.executedTools.size)
        assertEquals("get_weather", outcome.grounding.executedTools[0].toolName)
        assertEquals("calculate_heat_risk", outcome.grounding.executedTools[1].toolName)
        assertTrue(outcome.grounding.executedTools.all { it.succeeded })
        assertTrue(outcome.grounding.usedModelAssimilatedCurrent)
        assertFalse(outcome.grounding.usedCachedWeather)
    }

    @Test
    fun `progress callback reports each tool as it starts and plain-language mode omits source attribution`() = runBlocking {
        val fixedNow = Instant.parse("2026-10-26T12:10:00Z")
        val fakeProvider = FakeWeatherProvider().apply {
            currentResult = EngineResult.Success(
                TestFixtures.buildObservation(
                    airTempC = 30.0, rhPercent = 60.0, uvIndex = 5.0,
                    observedAt = Instant.parse("2026-10-26T12:00:00Z"), retrievedAt = fixedNow
                )
            )
        }
        val engine = HydroEngine.create(weatherProvider = fakeProvider, clock = MutableClock(fixedNow))
        val ready = LlmReadiness.Ready(
            modelSpec = LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM,
            resolvedFilePath = "/models/m.litertlm",
            actualFileSizeBytes = requireNotNull(LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM.expectedSizeBytes),
            matchesOfficialReleaseSize = true
        )
        val client = ScriptedGemma3nClient(readiness = ready) { round, _ ->
            val runtime = LlmActiveRuntimeInfo(
                modelSpec = LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM,
                activeBackend = LlmExecutionBackend.GPU,
                modalityMode = LlmModalityMode.MULTIMODAL_TEXT_VISION_AUDIO
            )
            if (round == 0) {
                LlmGenerationResponse(
                    text = "```tool_call\n{\"tool_call\":{\"name\":\"get_weather\",\"arguments\":{\"latitude\":7.3775,\"longitude\":3.9470}}}\n```",
                    runtimeInfo = runtime
                )
            } else {
                LlmGenerationResponse(text = "It is warm and comfortable.", runtimeInfo = runtime)
            }
        }
        val started = mutableListOf<String>()
        val orchestrator = engine.createOrchestrator(client, HydroOrchestratorConfig(showSourceAttribution = false))
        val outcome = orchestrator.respond(
            userPrompt = "How is it outside?",
            userLocationContext = TestFixtures.IBADAN,
            onToolStarted = { started += it }
        )

        assertEquals(listOf("get_weather"), started)
        assertTrue(outcome is HydroAssistantOutcome.Responded)
        assertFalse(outcome.responseText.contains("Open-Meteo", ignoreCase = true))
        assertFalse(outcome.responseText.contains("Source:"))
        // The prompt also tells the model to keep replies free of technical terms.
        assertTrue(orchestrator.buildGroundedSystemPrompt().contains("plain, short sentences", ignoreCase = true) ||
            orchestrator.buildGroundedSystemPrompt().contains("plain sentences", ignoreCase = true))
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
            readiness = LlmReadiness.NotInstalled(
                modelSpec = LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM,
                searchedPaths = listOf("/files/models/gemma-3n-E2B-it-int4.litertlm"),
                provisioningHint = "Download gemma-3n-E2B-it-int4.litertlm"
            )
        ) { _, _ -> error("Should not invoke generate when model is not installed") }

        val unavailableOut = engine.createOrchestrator(missingClient).respond("Check weather")
        assertTrue(unavailableOut is HydroAssistantOutcome.ModelUnavailable)

        // 2. Prime cache, switch provider offline, and verify mandatory cache disclosure
        assertTrue(engine.weatherRepository.getCurrentObservation(TestFixtures.IBADAN, CachePolicy.NETWORK_ONLY).isSuccess)
        fakeProvider.currentResult = EngineResult.Failure(EngineError.NetworkUnavailable("Offline"))
        clock.advance(Duration.ofMinutes(15))

        val ready = LlmReadiness.Ready(
            modelSpec = LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM,
            resolvedFilePath = "/models/gemma-3n-E2B-it-int4.litertlm",
            actualFileSizeBytes = 3_655_827_456L,
            matchesOfficialReleaseSize = true
        )
        val textOnlyRuntime = LlmActiveRuntimeInfo(
            LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM,
            LlmExecutionBackend.CPU,
            LlmModalityMode.TEXT_ONLY
        )
        val cacheTestClient = ScriptedGemma3nClient(
            readiness = ready,
            runtimeInfo = textOnlyRuntime
        ) { round, _ ->
            if (round == 0) {
                LlmGenerationResponse(
                    text = """{"tool_call":{"name":"get_weather","arguments":{"latitude":7.3775,"longitude":3.9470,"timezone":"Africa/Lagos"}}}""",
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
        val cachedResp = engine.createOrchestrator(cacheTestClient).respond("Weather now?")
        assertTrue(cachedResp is HydroAssistantOutcome.Responded)
        assertTrue(cachedResp.grounding.usedCachedWeather)
        assertTrue(cachedResp.responseText.contains("local cache", ignoreCase = true))

        // 3. Text-only fallback rejects multimodal image attachment explicitly
        val multimodalRejected = engine.createOrchestrator(cacheTestClient).respond(
            userPrompt = "Analyze this sky photo",
            additionalMedia = listOf(LlmInputContent.ImageFile("/tmp/sky.jpg"))
        )
        assertTrue(multimodalRejected is HydroAssistantOutcome.Failed)
        assertTrue(multimodalRejected.error is EngineError.UnsupportedCalculation)

        // 4. Hallucinated unregistered tool & permission gate denial & maxToolRounds loop protection
        val loopingHallucinatingClient = ScriptedGemma3nClient(readiness = ready) { round, _ ->
            val info = LlmActiveRuntimeInfo(
                LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM,
                LlmExecutionBackend.CPU,
                LlmModalityMode.TEXT_ONLY
            )
            if (round == 0) {
                LlmGenerationResponse(
                    text = """{"tool_call":{"name":"execute_shell","arguments":{"cmd":"whoami"}}}""",
                    runtimeInfo = info
                )
            } else {
                LlmGenerationResponse(
                    text = """{"tool_call":{"name":"query_memory","arguments":{"latitude":7.3775,"longitude":3.9470}}}""",
                    runtimeInfo = info
                )
            }
        }
        val restrictedGate = SetBasedToolPermissionGate(setOf(ToolCapability.DETERMINISTIC_ANALYSIS))
        val boundedOrchestrator = engine.createOrchestrator(
            llmClient = loopingHallucinatingClient,
            config = HydroOrchestratorConfig(maxToolRounds = 2)
        )
        val loopFailure = boundedOrchestrator.respond("Test loop", permissionGate = restrictedGate)
        assertTrue(loopFailure is HydroAssistantOutcome.Failed)
        assertEquals(2, loopFailure.partialToolTraces.size)
        assertEquals("TOOL_NOT_REGISTERED", loopFailure.partialToolTraces[0].errorCode)
        assertEquals("PERMISSION_DENIED", loopFailure.partialToolTraces[1].errorCode)
    }

    @Test
    fun `tool calls embedded in prose are executed and raw tool-call JSON never reaches the user`() = runBlocking {
        val fixedNow = Instant.parse("2026-10-26T12:00:00Z")
        val clock = MutableClock(fixedNow)
        val fakeProvider = FakeWeatherProvider().apply {
            currentResult = EngineResult.Success(
                TestFixtures.buildObservation(retrievedAt = fixedNow)
            )
        }
        val engine = HydroEngine.create(weatherProvider = fakeProvider, clock = clock)
        val ready = LlmReadiness.Ready(
            modelSpec = LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM,
            resolvedFilePath = "/models/gemma-3n-E2B-it-int4.litertlm",
            actualFileSizeBytes = requireNotNull(LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM.expectedSizeBytes),
            matchesOfficialReleaseSize = true
        )
        val runtime = LlmActiveRuntimeInfo(
            LlmModelCatalog.GEMMA_3N_E2B_IT_LITERT_LM,
            LlmExecutionBackend.CPU,
            LlmModalityMode.TEXT_ONLY
        )

        // Prose followed by an un-fenced tool_call object: previously treated as a final answer.
        val inlineClient = ScriptedGemma3nClient(readiness = ready, runtimeInfo = runtime) { round, _ ->
            if (round == 0) {
                LlmGenerationResponse(
                    text = "Let me check the latest conditions. " +
                        """{"tool_call":{"name":"get_weather","arguments":{"latitude":7.3775,"longitude":3.9470,"timezone":"Africa/Lagos"}}}""",
                    runtimeInfo = runtime
                )
            } else {
                LlmGenerationResponse(
                    text = "Current conditions are loaded (Weather data by Open-Meteo.com).",
                    runtimeInfo = runtime
                )
            }
        }
        val inlineOutcome = engine.createOrchestrator(inlineClient)
            .respond("How is the weather right now?")
        assertTrue(inlineOutcome is HydroAssistantOutcome.Responded)
        assertEquals("get_weather", inlineOutcome.grounding.executedTools.single().toolName)
        assertFalse(inlineOutcome.responseText.contains("tool_call"))

        // Same behaviour for prose followed by a fenced JSON block.
        val fencedClient = ScriptedGemma3nClient(readiness = ready, runtimeInfo = runtime) { round, _ ->
            if (round == 0) {
                LlmGenerationResponse(
                    text = "Checking now.\n\n```json\n" +
                        """{"tool_call":{"name":"get_weather","arguments":{"latitude":7.3775,"longitude":3.9470,"timezone":"Africa/Lagos"}}}""" +
                        "\n```",
                    runtimeInfo = runtime
                )
            } else {
                LlmGenerationResponse(
                    text = "Conditions loaded (Weather data by Open-Meteo.com).",
                    runtimeInfo = runtime
                )
            }
        }
        val fencedOutcome = engine.createOrchestrator(fencedClient)
            .respond("Weather now?")
        assertTrue(fencedOutcome is HydroAssistantOutcome.Responded)
        assertEquals("get_weather", fencedOutcome.grounding.executedTools.single().toolName)
        assertFalse(fencedOutcome.responseText.contains("tool_call"))
    }
}
