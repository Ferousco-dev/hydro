package com.ferousco.hydro.core

import com.ferousco.hydro.core.ai.HydroAssistantOutcome
import com.ferousco.hydro.core.ai.LlmGenerationRequest
import com.ferousco.hydro.core.ai.LlmInputContent
import com.ferousco.hydro.core.ai.LlmModelCatalog
import com.ferousco.hydro.core.ai.LlmReadiness
import com.ferousco.hydro.core.ai.gemini.GeminiApiConfig
import com.ferousco.hydro.core.ai.gemini.GeminiApiLlmClient
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.history.InMemoryEnvironmentalHistoryStore
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Offline verification of the Gemini Interactions API adapter.
 *
 * Every test runs against [FakeHttpTransport]: no API key, no network, no cost. The fixtures below
 * mirror the response shapes documented in the Gemini API reference (`steps[].type ==
 * "function_call"` / `"model_output"`), so a contract change shows up as a failing test rather than
 * a runtime surprise on a phone.
 */
class GeminiApiLlmClientTest {

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = MutableClock(now)

    private fun client(
        transport: FakeHttpTransport,
        apiKey: String = "test-key",
        model: com.ferousco.hydro.core.ai.HostedLlmModelSpec = LlmModelCatalog.DEFAULT
    ) = GeminiApiLlmClient(
        config = GeminiApiConfig(apiKey = apiKey, model = model),
        transport = transport
    )

    private fun functionCallResponse(
        name: String = "get_weather",
        callId: String = "call_abc123",
        arguments: String = """{"latitude":7.3775,"longitude":3.9470,"timezone":"Africa/Lagos"}"""
    ) = """
        {
          "id": "v1_interaction_1",
          "model": "gemini-3.8-flash",
          "object": "interaction",
          "status": "requires_action",
          "steps": [
            { "type": "function_call", "name": "$name", "arguments": $arguments, "id": "$callId" }
          ]
        }
    """.trimIndent()

    private fun textResponse(text: String) = """
        {
          "id": "v1_interaction_2",
          "model": "gemini-3.8-flash",
          "object": "interaction",
          "status": "completed",
          "steps": [
            { "type": "model_output", "content": [ { "type": "text", "text": "$text" } ] }
          ]
        }
    """.trimIndent()

    private fun request(messages: List<com.ferousco.hydro.core.ai.LlmConversationMessage>) =
        LlmGenerationRequest(
            systemInstruction = "You are Hydro.",
            messages = messages,
            toolDeclarations = listOf(
                JsonValue.ObjectValue(
                    linkedMapOf(
                        "name" to JsonValue.StringValue("get_weather"),
                        "description" to JsonValue.StringValue("Current conditions."),
                        "parameters" to JsonValue.ObjectValue(
                            linkedMapOf(
                                "type" to JsonValue.StringValue("object"),
                                "properties" to JsonValue.ObjectValue(
                                    linkedMapOf(
                                        "latitude" to JsonValue.ObjectValue(
                                            linkedMapOf(
                                                "type" to JsonValue.StringValue("number"),
                                                "minimum" to SafeJsonCodec.number(-90.0),
                                                "maximum" to SafeJsonCodec.number(90.0)
                                            )
                                        )
                                    )
                                ),
                                "required" to JsonValue.ArrayValue(
                                    listOf(JsonValue.StringValue("latitude"))
                                ),
                                // Not part of Gemini's Schema subset; must be stripped before sending.
                                "additionalProperties" to JsonValue.BooleanValue(false)
                            )
                        )
                    )
                )
            )
        )

    @Test
    fun `missing api key surfaces as credentials-missing readiness and blocks requests`() = runBlocking {
        val transport = FakeHttpTransport()
        val unconfigured = client(transport, apiKey = "   ")

        val readiness = unconfigured.checkReadiness()
        assertTrue(readiness is LlmReadiness.CredentialsMissing)
        assertTrue((readiness as LlmReadiness.CredentialsMissing).setupHint.contains("GEMINI_API_KEY"))

        val result = unconfigured.generate(
            request(listOf(com.ferousco.hydro.core.ai.LlmConversationMessage.userText("Hi")))
        )
        assertTrue(result is EngineResult.Failure)
        assertTrue(result.error is EngineError.MissingRequiredInputs)
        assertTrue(transport.recordedPostRequests.isEmpty(), "No request may be sent without a key.")
    }

    @Test
    fun `request body uses the interactions contract with native tool declarations`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueuePostSuccess(body = textResponse("It is hot.")) }

        val response = client(transport).generate(
            request(listOf(com.ferousco.hydro.core.ai.LlmConversationMessage.userText("How hot is it?")))
        )
        assertTrue(response is EngineResult.Success, response.toString())

        val sent = transport.recordedPostRequests.single()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/interactions",
            sent.uri.toString()
        )
        assertEquals("test-key", sent.headers["x-goog-api-key"])
        assertEquals("application/json", sent.headers["Content-Type"])

        val body = SafeJsonCodec.parseObject(sent.body).getOrNull()!!
        assertEquals("gemini-3.8-flash", body.getString("model"))
        assertEquals("You are Hydro.", body.getString("system_instruction"))
        assertEquals(false, body.getBoolean("store"))

        val input = body.getArray("input")!!.items
        val userStep = input.single() as JsonValue.ObjectValue
        assertEquals("user_input", userStep.getString("type"))
        val textPart = userStep.getArray("content")!!.items.single() as JsonValue.ObjectValue
        assertEquals("text", textPart.getString("type"))
        assertEquals("How hot is it?", textPart.getString("text"))

        val tool = body.getArray("tools")!!.items.single() as JsonValue.ObjectValue
        assertEquals("function", tool.getString("type"))
        assertEquals("get_weather", tool.getString("name"))

        val parameters = tool.getObject("parameters")!!
        assertEquals("object", parameters.getString("type"))
        assertTrue(
            parameters["additionalProperties"] == null,
            "Gemini's Schema subset does not accept additionalProperties."
        )
        val latitude = parameters.getObject("properties")!!.getObject("latitude")!!
        assertEquals(-90.0, latitude.getDouble("minimum"))
        assertEquals(90.0, latitude.getDouble("maximum"))

        val generationConfig = body.getObject("generation_config")!!
        assertEquals(768L, generationConfig.getLong("max_output_tokens"))
        assertEquals(0L, generationConfig.getLong("seed"))
    }

    @Test
    fun `function_call steps become native tool calls with their call id`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueuePostSuccess(body = functionCallResponse()) }

        val result = client(transport).generate(
            request(listOf(com.ferousco.hydro.core.ai.LlmConversationMessage.userText("Weather?")))
        )
        val generation = (result as EngineResult.Success).value

        assertEquals("", generation.text)
        val call = generation.nativeToolCalls.single()
        assertEquals("get_weather", call.toolName)
        assertEquals("call_abc123", call.callId)
        assertEquals(7.3775, call.arguments.getDouble("latitude"))
        assertEquals("Africa/Lagos", call.arguments.getString("timezone"))
    }

    @Test
    fun `model_output steps become the answer text`() = runBlocking {
        val transport = FakeHttpTransport().apply {
            enqueuePostSuccess(body = textResponse("The heat index is 38.2 C. Drink water."))
        }

        val result = client(transport).generate(
            request(listOf(com.ferousco.hydro.core.ai.LlmConversationMessage.userText("How hot?")))
        )
        val generation = (result as EngineResult.Success).value

        assertEquals("The heat index is 38.2 C. Drink water.", generation.text)
        assertTrue(generation.nativeToolCalls.isEmpty())
    }

    @Test
    fun `replayed history sends function_call ids and matching function_result call ids`() = runBlocking {
        val transport = FakeHttpTransport().apply {
            enqueuePostSuccess(body = textResponse("Done."))
        }

        val toolCall = com.ferousco.hydro.core.ai.LlmNativeToolCall(
            toolName = "get_weather",
            arguments = JsonValue.ObjectValue(
                linkedMapOf(
                    "latitude" to SafeJsonCodec.number(7.3775),
                    "longitude" to SafeJsonCodec.number(3.9470)
                )
            ),
            callId = "call_abc123"
        )
        val messages = listOf(
            com.ferousco.hydro.core.ai.LlmConversationMessage.userText("Weather?"),
            com.ferousco.hydro.core.ai.LlmConversationMessage.modelText("", listOf(toolCall)),
            com.ferousco.hydro.core.ai.LlmConversationMessage.toolResponse(
                toolName = "get_weather",
                resultJson = JsonValue.ObjectValue(
                    linkedMapOf("ok" to JsonValue.BooleanValue(true))
                ),
                callId = "call_abc123"
            )
        )

        client(transport).generate(request(messages))

        val body = SafeJsonCodec.parseObject(transport.recordedPostRequests.single().body).getOrNull()!!
        val steps = body.getArray("input")!!.items.map { it as JsonValue.ObjectValue }

        assertEquals(listOf("user_input", "function_call", "function_result"), steps.map { it.getString("type") })

        val replayedCall = steps[1]
        assertEquals("get_weather", replayedCall.getString("name"))
        assertEquals("call_abc123", replayedCall.getString("id"))

        val resultStep = steps[2]
        assertEquals("get_weather", resultStep.getString("name"))
        assertEquals("call_abc123", resultStep.getString("call_id"))
        val resultPart = resultStep.getArray("result")!!.items.single() as JsonValue.ObjectValue
        assertEquals("text", resultPart.getString("type"))
        assertTrue(resultPart.getString("text")!!.contains("\"ok\":true"))
    }

    @Test
    fun `http 401 maps to a non-transient provider error naming the api key`() = runBlocking {
        val transport = FakeHttpTransport().apply {
            enqueuePostSuccess(
                statusCode = 401,
                body = """{"error":{"code":401,"status":"UNAUTHENTICATED","message":"API key not valid."}}"""
            )
        }

        val result = client(transport).generate(
            request(listOf(com.ferousco.hydro.core.ai.LlmConversationMessage.userText("Hi")))
        )
        val error = (result as EngineResult.Failure).error as EngineError.ProviderError

        assertEquals(401, error.statusCode)
        assertEquals("UNAUTHENTICATED", error.providerReason)
        assertEquals(false, error.isTransient)
        assertTrue(error.message.contains("GEMINI_API_KEY"))
    }

    @Test
    fun `http 429 maps to a transient provider error`() = runBlocking {
        val transport = FakeHttpTransport().apply {
            enqueuePostSuccess(
                statusCode = 429,
                body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded."}}"""
            )
        }

        val result = client(transport).generate(
            request(listOf(com.ferousco.hydro.core.ai.LlmConversationMessage.userText("Hi")))
        )
        val error = (result as EngineResult.Failure).error as EngineError.ProviderError

        assertEquals(true, error.isTransient)
        assertEquals(429, error.statusCode)
    }

    @Test
    fun `media input is rejected instead of being sent unencoded`() = runBlocking {
        val transport = FakeHttpTransport()
        val result = client(transport).generate(
            request(
                listOf(
                    com.ferousco.hydro.core.ai.LlmConversationMessage.userMultimodal(
                        listOf(
                            LlmInputContent.Text("Describe this"),
                            LlmInputContent.ImageBytes(byteArrayOf(1, 2, 3))
                        )
                    )
                )
            )
        )

        assertTrue(result is EngineResult.Failure)
        assertTrue(result.error is EngineError.UnsupportedCalculation)
        assertTrue(transport.recordedPostRequests.isEmpty())
    }

    @Test
    fun `orchestrator completes a gemini tool round trip end to end`() = runBlocking {
        val transport = FakeHttpTransport().apply {
            enqueuePostSuccess(body = functionCallResponse())
            enqueuePostSuccess(
                body = textResponse("Ibadan is 33 C with 66% humidity. Heat index is in the caution band.")
            )
        }

        val fakeProvider = FakeWeatherProvider()
        fakeProvider.currentResult = EngineResult.Success(
            TestFixtures.buildObservation(
                observedAt = now.minusSeconds(300),
                retrievedAt = now,
                airTempC = 33.0,
                rhPercent = 66.0
            )
        )

        val engine = HydroEngine.create(
            weatherProvider = fakeProvider,
            historyStore = InMemoryEnvironmentalHistoryStore(),
            clock = clock
        )

        val outcome = engine.createOrchestrator(client(transport))
            .respond(userPrompt = "What is the weather where I am?", userLocationContext = TestFixtures.IBADAN)

        val responded = outcome as HydroAssistantOutcome.Responded
        assertEquals("get_weather", responded.grounding.executedTools.single().toolName)
        assertTrue(responded.responseText.contains("Ibadan is 33 C"))

        assertEquals(2, transport.recordedPostRequests.size)

        // The first request offers tools natively and must not also embed the JSON protocol.
        val firstBody = SafeJsonCodec.parseObject(transport.recordedPostRequests[0].body).getOrNull()!!
        assertNotNull(firstBody.getArray("tools"))
        val systemInstruction = firstBody.getString("system_instruction")!!
        assertTrue(
            !systemInstruction.contains("TOOL CALL PROTOCOL"),
            "Native tool calling must not be mixed with the prompt-JSON protocol."
        )

        // The second request carries the deterministic tool result keyed by the provider call id.
        val secondBody = SafeJsonCodec.parseObject(transport.recordedPostRequests[1].body).getOrNull()!!
        val steps = secondBody.getArray("input")!!.items.map { it as JsonValue.ObjectValue }
        val resultStep = steps.single { it.getString("type") == "function_result" }
        assertEquals("call_abc123", resultStep.getString("call_id"))
        assertEquals("get_weather", resultStep.getString("name"))
    }
}
