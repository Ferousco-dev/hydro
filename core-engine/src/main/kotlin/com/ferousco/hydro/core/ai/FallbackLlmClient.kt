package com.ferousco.hydro.core.ai

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.SafeJsonCodec
import kotlinx.coroutines.flow.Flow

/**
 * Tries [primary] and, when it is out of quota (429), missing (404) or down (5xx), answers with
 * [fallback] instead. A free-tier key runs out of its strongest model quickly; this keeps the
 * assistant answering from a lighter model rather than failing.
 *
 * Once the primary has failed it is skipped for a while, so one question is not split across two
 * models. When a question is already mid-way through a tool exchange, that exchange is replayed to
 * the fallback as plain text, because tool calls carry signatures that only their own model accepts.
 *
 * Everything other than [generate] and [generateStream] follows the primary client, so the model
 * the app reports stays the one it was configured for.
 */
class FallbackLlmClient(
    private val primary: LlmClient,
    private val fallback: LlmClient,
    private val clock: () -> Long = System::currentTimeMillis
) : LlmClient {
    @Volatile
    private var primarySkippedUntil = 0L

    override val modelSpec: LlmModelSpec get() = primary.modelSpec

    override fun checkReadiness(): LlmReadiness = primary.checkReadiness()

    override fun activeRuntimeInfoOrNull(): LlmActiveRuntimeInfo? = primary.activeRuntimeInfoOrNull()

    override suspend fun initialize(): EngineResult<LlmActiveRuntimeInfo> = primary.initialize()

    override suspend fun generate(request: LlmGenerationRequest): EngineResult<LlmGenerationResponse> {
        if (clock() < primarySkippedUntil) return fallback.generate(request.withoutProviderState())
        val first = primary.generate(request)
        if (first is EngineResult.Failure) {
            val status = (first.error as? EngineError.ProviderError)?.statusCode
            if (status != null && (status == 429 || status == 404 || status >= 500)) {
                // Quota and missing models stay broken for a long time; an overload usually clears fast.
                primarySkippedUntil = clock() + if (status >= 500) SHORT_SKIP_MS else LONG_SKIP_MS
                return fallback.generate(request.withoutProviderState())
            }
        }
        return first
    }

    override fun generateStream(request: LlmGenerationRequest): Flow<String> = primary.generateStream(request)

    override fun close() {
        primary.close()
        fallback.close()
    }

    /** Replays earlier tool calls and results as plain text so any model can read them. */
    private fun LlmGenerationRequest.withoutProviderState(): LlmGenerationRequest {
        if (messages.none { it.nativeToolCalls.isNotEmpty() }) return this
        val flattened = messages.mapNotNull { message ->
            when (message.role) {
                LlmMessageRole.USER -> message
                LlmMessageRole.MODEL -> if (message.nativeToolCalls.isEmpty()) message else null
                LlmMessageRole.TOOL -> LlmConversationMessage.userText(
                    "Tool results so far:\n" + message.contents
                        .filterIsInstance<LlmInputContent.ToolResult>()
                        .joinToString("\n") { "${it.toolName}: ${SafeJsonCodec.stringify(it.resultJson)}" }
                )
            }
        }
        return copy(messages = flattened)
    }

    private companion object {
        const val LONG_SKIP_MS = 10 * 60_000L
        const val SHORT_SKIP_MS = 60_000L
    }
}
