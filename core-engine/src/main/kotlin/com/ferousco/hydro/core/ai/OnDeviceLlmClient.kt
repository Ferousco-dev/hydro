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
 * Multimodal input content matching Gemma 3n's text, vision (256/512/768px), and audio capabilities.
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
    data class ToolResult(
        val toolName: String,
        val resultJson: JsonValue.ObjectValue
    ) : LlmInputContent
}

/**
 * Orders multimodal content parts so [LlmInputContent.Text] precedes image/audio media parts,
 * as required by LiteRT-LM's Gemma 3n multimodal input specification.
 */
fun List<LlmInputContent>.orderedTextFirstForLiteRt(): List<LlmInputContent> {
    val textParts = filterIsInstance<LlmInputContent.Text>()
    val nonTextParts = filterNot { it is LlmInputContent.Text }
    return textParts + nonTextParts
}

data class LlmNativeToolCall(
    val toolName: String,
    val arguments: JsonValue.ObjectValue
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

        fun toolResponse(toolName: String, resultJson: JsonValue.ObjectValue): LlmConversationMessage =
            LlmConversationMessage(
                LlmMessageRole.TOOL,
                listOf(LlmInputContent.ToolResult(toolName, resultJson))
            )
    }
}

data class LlmActiveRuntimeInfo(
    val modelSpec: Gemma3nModelSpec,
    val activeBackend: LlmHardwareBackend,
    val modalityMode: LlmModalityMode,
    val fallbackApplied: Boolean = false,
    val fallbackReason: String? = null
)

data class LlmGenerationRequest(
    val systemInstruction: String,
    val messages: List<LlmConversationMessage>,
    val sampling: LlmSamplingParams = LlmSamplingParams.GROUNDED_HYDRO,
    val maxOutputTokens: Int = 768,
    val enableNativeToolDeclarations: Boolean = true
) {
    init {
        require(messages.isNotEmpty()) { "LlmGenerationRequest requires at least one message." }
        require(maxOutputTokens in 1..4096) { "maxOutputTokens must be in 1..4096." }
    }
}

data class LlmGenerationResponse(
    val text: String,
    val nativeToolCalls: List<LlmNativeToolCall> = emptyList(),
    val runtimeInfo: LlmActiveRuntimeInfo
)

/**
 * Runtime-agnostic contract for on-device open-weight LLM inference.
 *
 * Implemented on Android by `LiteRtGemma3nClient` (backed by `com.google.ai.edge.litertlm`
 * and `google/gemma-3n-E2B-it-litert-lm`) and in JVM unit tests by deterministic fakes.
 */
interface OnDeviceLlmClient : AutoCloseable {
    val modelSpec: Gemma3nModelSpec

    fun checkArtifactReadiness(): ModelArtifactReadiness

    fun activeRuntimeInfoOrNull(): LlmActiveRuntimeInfo?

    suspend fun initialize(): EngineResult<LlmActiveRuntimeInfo>

    suspend fun generate(request: LlmGenerationRequest): EngineResult<LlmGenerationResponse>

    fun generateStream(request: LlmGenerationRequest): Flow<String>
}
