package com.ferousco.hydro.core

import com.ferousco.hydro.core.ai.FallbackLlmClient
import com.ferousco.hydro.core.ai.LlmConversationMessage
import com.ferousco.hydro.core.ai.LlmGenerationRequest
import com.ferousco.hydro.core.ai.LlmModelCatalog
import com.ferousco.hydro.core.ai.LlmNativeToolCall
import com.ferousco.hydro.core.ai.gemini.GeminiApiConfig
import com.ferousco.hydro.core.ai.gemini.GeminiApiLlmClient
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The assistant must keep answering when the main model is out of quota, and must never hand one
 * model another model's signed tool calls (Gemini rejects those with a 400).
 */
class FallbackLlmClientTest {

    private fun gemini(transport: FakeHttpTransport, model: com.ferousco.hydro.core.ai.HostedLlmModelSpec) =
        GeminiApiLlmClient(config = GeminiApiConfig(apiKey = "test-key", model = model), transport = transport)

    private fun text(answer: String) = """
        { "status": "completed", "steps": [ { "type": "model_output", "content": [ { "type": "text", "text": "$answer" } ] } ] }
    """.trimIndent()

    private val quota429 = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Rate limit exceeded"}}"""

    private fun request(messages: List<LlmConversationMessage>) =
        LlmGenerationRequest(systemInstruction = "You are Hydro.", messages = messages)

    private val toolCall = LlmNativeToolCall(
        toolName = "get_hourly_forecast",
        arguments = JsonValue.ObjectValue(linkedMapOf("latitude" to SafeJsonCodec.number(6.5))),
        callId = "call_1",
        thoughtSignature = "SIGNATURE_FROM_PRIMARY"
    )

    private val withToolExchange = listOf(
        LlmConversationMessage.userText("When is it coolest?"),
        LlmConversationMessage.modelText("", listOf(toolCall)),
        LlmConversationMessage.toolResponse(
            toolName = "get_hourly_forecast",
            resultJson = JsonValue.ObjectValue(linkedMapOf("coolest" to JsonValue.StringValue("05:00"))),
            callId = "call_1"
        )
    )

    @Test
    fun `a quota error on the main model is answered by the fallback`() = runBlocking {
        val primary = FakeHttpTransport().apply { enqueuePostSuccess(statusCode = 429, body = quota429) }
        val fallback = FakeHttpTransport().apply { enqueuePostSuccess(body = text("From the lighter model.")) }
        val client = FallbackLlmClient(
            gemini(primary, LlmModelCatalog.DEFAULT),
            gemini(fallback, LlmModelCatalog.GEMINI_3_5_FLASH_LITE)
        )

        val result = client.generate(request(listOf(LlmConversationMessage.userText("Hi"))))

        assertEquals("From the lighter model.", (result as EngineResult.Success).value.text)
    }

    @Test
    fun `the fallback never receives the main model's signed tool calls`() = runBlocking {
        val primary = FakeHttpTransport().apply { enqueuePostSuccess(statusCode = 429, body = quota429) }
        val fallback = FakeHttpTransport().apply { enqueuePostSuccess(body = text("Coolest is 05:00.")) }
        val client = FallbackLlmClient(
            gemini(primary, LlmModelCatalog.DEFAULT),
            gemini(fallback, LlmModelCatalog.GEMINI_3_5_FLASH_LITE)
        )

        client.generate(request(withToolExchange))

        val body = fallback.recordedPostRequests.single().body
        assertTrue("function_call" !in body && "SIGNATURE_FROM_PRIMARY" !in body, "signed calls must not be replayed: $body")
        assertTrue("05:00" in body, "the tool result should still reach the fallback as text")
    }

    @Test
    fun `after a quota error the main model is skipped for the rest of the turn`() = runBlocking {
        val primary = FakeHttpTransport().apply { enqueuePostSuccess(statusCode = 429, body = quota429) }
        val fallback = FakeHttpTransport().apply {
            enqueuePostSuccess(body = text("one"))
            enqueuePostSuccess(body = text("two"))
        }
        val client = FallbackLlmClient(
            gemini(primary, LlmModelCatalog.DEFAULT),
            gemini(fallback, LlmModelCatalog.GEMINI_3_5_FLASH_LITE)
        )

        client.generate(request(listOf(LlmConversationMessage.userText("first"))))
        client.generate(request(listOf(LlmConversationMessage.userText("second"))))

        assertEquals(1, primary.recordedPostRequests.size)
        assertEquals(2, fallback.recordedPostRequests.size)
    }

    @Test
    fun `a bad request is not retried on the fallback`() = runBlocking {
        val primary = FakeHttpTransport().apply {
            enqueuePostSuccess(statusCode = 400, body = """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"bad"}}""")
        }
        val fallback = FakeHttpTransport()
        val client = FallbackLlmClient(
            gemini(primary, LlmModelCatalog.DEFAULT),
            gemini(fallback, LlmModelCatalog.GEMINI_3_5_FLASH_LITE)
        )

        val result = client.generate(request(listOf(LlmConversationMessage.userText("Hi"))))

        assertTrue(result is EngineResult.Failure)
        assertEquals(0, fallback.recordedPostRequests.size)
    }

    @Test
    fun `a thought signature on a tool call is read and sent back before the call`() = runBlocking {
        val transport = FakeHttpTransport().apply {
            enqueuePostSuccess(
                body = """
                    { "status": "requires_action", "steps": [
                      { "type": "thought", "signature": "SIG_123" },
                      { "type": "function_call", "name": "get_weather", "arguments": {"latitude": 6.5}, "id": "call_9" }
                    ] }
                """.trimIndent()
            )
            enqueuePostSuccess(body = text("Done."))
        }
        val client = gemini(transport, LlmModelCatalog.DEFAULT)

        val first = (client.generate(request(listOf(LlmConversationMessage.userText("Weather?")))) as EngineResult.Success).value
        assertEquals("SIG_123", first.nativeToolCalls.single().thoughtSignature)

        client.generate(
            request(
                listOf(
                    LlmConversationMessage.userText("Weather?"),
                    LlmConversationMessage.modelText("", first.nativeToolCalls),
                    LlmConversationMessage.toolResponse(
                        toolName = "get_weather",
                        resultJson = JsonValue.ObjectValue(linkedMapOf("ok" to JsonValue.BooleanValue(true))),
                        callId = "call_9"
                    )
                )
            )
        )

        val body = SafeJsonCodec.parseObject(transport.recordedPostRequests.last().body).getOrNull()!!
        val steps = body.getArray("input")!!.items.map { it as JsonValue.ObjectValue }
        assertEquals(listOf("user_input", "thought", "function_call", "function_result"), steps.map { it.getString("type") })
        assertEquals("SIG_123", steps[1].getString("signature"))
    }
}
