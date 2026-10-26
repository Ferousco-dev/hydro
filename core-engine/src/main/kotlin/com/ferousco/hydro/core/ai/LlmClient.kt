package com.ferousco.hydro.core.ai

import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import kotlinx.coroutines.flow.Flow

enum class LlmMessageRole {
    USER,
    MODEL,
    TOOL
}

/**
 * Multimodal input content. Models that expose only text simply ignore the media variants.
 */
sealed interface LlmInputContent {
    data class Text(val text: String) : LlmInputContent
    data class ImageFile(val absolutePath: String) : LlmInputContent
    data class ImageBytes(val bytes: ByteArray) : LlmInputContent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is ImageBytes && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    data class AudioFile(val absolutePath: String) : LlmInputContent
    data class AudioBytes(val bytes: ByteArray) : LlmInputContent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is AudioBytes && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * A deterministic tool result being returned to the model.
     *
     * [callId] is the provider-assigned id of the originating tool call. Hosted APIs that correlate
     * results by id (Gemini's `function_result.call_id`) require it; local runtimes ignore it.
     */
    data class ToolResult(
        val toolName: String,
        val resultJson: JsonValue.ObjectValue,
        val callId: String? = null
    ) : LlmInputContent
}

/**
 * Orders multimodal content parts so [LlmInputContent.Text] precedes image/audio media parts,
 * as required by LiteRT-LM's multimodal input specification.
 */
fun List<LlmInputContent>.orderedTextFirstForLiteRt(): List<LlmInputContent> {
    val textParts = filterIsInstance<LlmInputContent.Text>()
    val nonTextParts = filterNot { it is LlmInputContent.Text }
    return textParts + nonTextParts
}

data class LlmNativeToolCall(
    val toolName: String,
    val arguments: JsonValue.ObjectValue,
    /**
     * Provider-assigned call id, when the runtime exposes one. Used to correlate the matching
     * tool result on the next request.
     */
    val callId: String? = null
)

data class LlmConversationMessage(
    val role: LlmMessageRole,
    val contents: List<LlmInputContent>,
    val nativeToolCalls: List<LlmNativeToolCall> = emptyList()
) {
    companion object {
        fun userText(text: String): LlmConversationMessage =
            LlmConversationMessage(LlmMessageRole.USER, listOf(LlmInputContent.Text(text)))

        fun userMultimodal(contents: List<LlmInputContent>): LlmConversationMessage =
            LlmConversationMessage(LlmMessageRole.USER, contents.orderedTextFirstForLiteRt())

        fun modelText(text: String, toolCalls: List<LlmNativeToolCall> = emptyList()): LlmConversationMessage =
            LlmConversationMessage(LlmMessageRole.MODEL, listOf(LlmInputContent.Text(text)), toolCalls)

        fun toolResponse(
            toolName: String,
            resultJson: JsonValue.ObjectValue,
            callId: String? = null
        ): LlmConversationMessage =
            LlmConversationMessage(
                LlmMessageRole.TOOL,
                listOf(LlmInputContent.ToolResult(toolName, resultJson, callId))
            )
    }
}

data class LlmActiveRuntimeInfo(
    val modelSpec: LlmModelSpec,
    val activeBackend: LlmExecutionBackend,
    val modalityMode: LlmModalityMode,
    val fallbackApplied: Boolean = false,
    val fallbackReason: String? = null
)

data class LlmGenerationRequest(
    val systemInstruction: String,
    val messages: List<LlmConversationMessage>,
    val sampling: LlmSamplingParams = LlmSamplingParams.GROUNDED_HYDRO,
    val maxOutputTokens: Int = 768,
    val enableNativeToolDeclarations: Boolean = true,
    /**
     * Tool declarations the runtime may expose natively, in OpenAPI function-declaration form.
     *
     * Clients that support native tool calling send these to the provider; clients that do not
     * ignore them and rely on the system-prompt JSON protocol instead.
     */
    val toolDeclarations: List<JsonValue.ObjectValue> = emptyList()
) {
    init {
        require(messages.isNotEmpty()) { "LlmGenerationRequest requires at least one message." }
        require(maxOutputTokens in 1..8192) { "maxOutputTokens must be in 1..8192." }
    }
}

data class LlmGenerationResponse(
    val text: String,
    val nativeToolCalls: List<LlmNativeToolCall> = emptyList(),
    val runtimeInfo: LlmActiveRuntimeInfo
)

/**
 * Runtime-agnostic contract for language-model inference.
 *
 * Hydro's orchestrator depends only on this interface, so both the model and the deployment shape
 * are swappable:
 * - on-device: `LiteRtGemma3nClient` (LiteRT-LM) in `:app`;
 * - hosted: [GeminiApiLlmClient] in `:core-engine`;
 * - tests: deterministic scripted fakes.
 *
 * Adding another runtime (llama.cpp, MediaPipe, OpenAI-compatible endpoint) means writing one
 * adapter; the engine, tools, and provenance logic do not change.
 */
interface LlmClient : AutoCloseable {
    val modelSpec: LlmModelSpec

    /** Whether this client can serve a request right now (artifact present, API key configured). */
    fun checkReadiness(): LlmReadiness

    fun activeRuntimeInfoOrNull(): LlmActiveRuntimeInfo?

    suspend fun initialize(): EngineResult<LlmActiveRuntimeInfo>

    suspend fun generate(request: LlmGenerationRequest): EngineResult<LlmGenerationResponse>

    fun generateStream(request: LlmGenerationRequest): Flow<String>
}
