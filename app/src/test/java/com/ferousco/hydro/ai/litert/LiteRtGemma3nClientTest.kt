package com.ferousco.hydro.ai.litert

import com.ferousco.hydro.core.HydroEngine
import com.ferousco.hydro.core.ai.Gemma3nModelSpec
import com.ferousco.hydro.core.ai.LlmConversationMessage
import com.ferousco.hydro.core.ai.LlmGenerationRequest
import com.ferousco.hydro.core.ai.LlmHardwareBackend
import com.ferousco.hydro.core.ai.LlmModalityMode
import com.ferousco.hydro.core.ai.LlmNativeToolCall
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiteRtGemma3nClientTest {

    private class FakeLiteRtSession(
        private val replyText: String = "Grounded Gemma 3n reply"
    ) : LiteRtEngineSession {
        var closed = false
        var lastToolProviderCount = 0

        override fun sendTurn(
            systemInstruction: String,
            historyExceptLatest: List<LlmConversationMessage>,
            latestMessage: LlmConversationMessage,
            samplingTopK: Int,
            samplingTopP: Double,
            samplingTemperature: Double,
            samplingSeed: Int,
            maxOutputTokens: Int,
            toolProviders: List<ToolProvider>
        ): Pair<String, List<LlmNativeToolCall>> {
            lastToolProviderCount = toolProviders.size
            return replyText to emptyList()
        }

        override fun streamTurn(
            systemInstruction: String,
            historyExceptLatest: List<LlmConversationMessage>,
            latestMessage: LlmConversationMessage,
            samplingTopK: Int,
            samplingTopP: Double,
            samplingTemperature: Double,
            samplingSeed: Int,
            maxOutputTokens: Int,
            toolProviders: List<ToolProvider>
        ): Flow<String> = flowOf(replyText)

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `LiteRtGemma3nClient enforces 3-stage cascading fallback from GPU multimodal to CPU multimodal to CPU text-only`() = runBlocking {
        val tempDir = Files.createTempDirectory("litert-client-test").toFile()
        try {
            val modelFile = File(tempDir, Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM.expectedFileName).apply {
                // Write 2 MiB dummy payload (>= 1 MiB minimum artifact threshold)
                writeBytes(ByteArray(2 * 1024 * 1024))
            }
            val engine = HydroEngine.create()

            // Case A: Stage 1 GPU multimodal fails (e.g., missing OpenCL driver), Stage 2 CPU multimodal succeeds
            val attemptedStagesCaseA = mutableListOf<Pair<LlmHardwareBackend, Boolean>>()
            val sessionA = FakeLiteRtSession()
            val clientA = LiteRtGemma3nClient(
                candidateModelFiles = listOf(modelFile),
                preferredBackend = LlmHardwareBackend.GPU,
                detectedDeviceRamBytes = 8L * 1024L * 1024L * 1024L,
                toolRegistry = engine.toolRegistry,
                engineFactory = { _, backend, multimodal, _, _, _ ->
                    attemptedStagesCaseA.add(backend to multimodal)
                    if (backend == LlmHardwareBackend.GPU) {
                        throw RuntimeException("Adreno OpenCL delegate unavailable")
                    }
                    sessionA
                }
            )
            val initA = clientA.initialize().getOrNull()!!
            assertEquals(listOf(LlmHardwareBackend.GPU to true, LlmHardwareBackend.CPU to true), attemptedStagesCaseA)
            assertEquals(LlmHardwareBackend.CPU, initA.activeBackend)
            assertEquals(LlmModalityMode.MULTIMODAL_TEXT_VISION_AUDIO, initA.modalityMode)
            assertTrue(initA.fallbackApplied)

            val genA = clientA.generate(
                LlmGenerationRequest(
                    systemInstruction = "System",
                    messages = listOf(LlmConversationMessage.userText("Hello"))
                )
            ).getOrNull()!!
            assertEquals("Grounded Gemma 3n reply", genA.text)
            assertEquals(6, sessionA.lastToolProviderCount)
            clientA.close()
            assertTrue(sessionA.closed)

            // Case B: Both Stage 1 (GPU multimodal) and Stage 2 (CPU multimodal) fail -> Stage 3 CPU text-only succeeds
            val attemptedStagesCaseB = mutableListOf<Pair<LlmHardwareBackend, Boolean>>()
            val clientB = LiteRtGemma3nClient(
                candidateModelFiles = listOf(modelFile),
                preferredBackend = LlmHardwareBackend.GPU,
                detectedDeviceRamBytes = 12L * 1024L * 1024L * 1024L,
                engineFactory = { _, backend, multimodal, _, _, _ ->
                    attemptedStagesCaseB.add(backend to multimodal)
                    if (multimodal) {
                        throw RuntimeException("Vision submodel delegate init failed")
                    }
                    FakeLiteRtSession("Text-only fallback reply")
                }
            )
            val initB = clientB.initialize().getOrNull()!!
            assertEquals(
                listOf(
                    LlmHardwareBackend.GPU to true,
                    LlmHardwareBackend.CPU to true,
                    LlmHardwareBackend.CPU to false
                ),
                attemptedStagesCaseB
            )
            assertEquals(LlmHardwareBackend.CPU, initB.activeBackend)
            assertEquals(LlmModalityMode.TEXT_ONLY, initB.modalityMode)
            assertTrue(initB.fallbackApplied)
            clientB.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `HydroLiteRtOpenApiTool exposes valid OpenAPI declarations and routes execution through HydroToolRegistry`() {
        val engine = HydroEngine.create()
        val calcDescriptor = engine.toolRegistry.descriptorFor("calculate_heat_assessment")!!
        val openApiTool = HydroLiteRtOpenApiTool(calcDescriptor, engine.toolRegistry)

        val specJson = SafeJsonCodec.parseObject(openApiTool.getToolDescriptionJsonString()).getOrNull()!!
        assertEquals("calculate_heat_assessment", specJson.getString("name"))
        assertFalse(specJson.entries.containsKey("result_schema"))
        assertTrue(specJson.getObject("parameters") != null)

        val resultRaw = openApiTool.execute(
            """
            {
              "latitude": 7.3775,
              "longitude": 3.9470,
              "valid_at": "2026-10-26T12:00:00Z",
              "data_nature": "OBSERVED",
              "air_temperature_celsius": 34.0,
              "relative_humidity_percent": 65.0
            }
            """.trimIndent()
        )
        val resultObj = SafeJsonCodec.parseObject(resultRaw).getOrNull()!!
        assertEquals("SUPPORTED", resultObj.getString("status"))
        assertEquals("DANGER", resultObj.getString("category"))
    }
}
