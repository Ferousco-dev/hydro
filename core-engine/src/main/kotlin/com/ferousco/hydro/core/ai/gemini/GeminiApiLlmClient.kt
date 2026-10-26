package com.ferousco.hydro.core.ai.gemini

import com.ferousco.hydro.core.ai.HostedLlmModelSpec
import com.ferousco.hydro.core.ai.LlmActiveRuntimeInfo
import com.ferousco.hydro.core.ai.LlmClient
import com.ferousco.hydro.core.ai.LlmConversationMessage
import com.ferousco.hydro.core.ai.LlmExecutionBackend
import com.ferousco.hydro.core.ai.LlmGenerationRequest
import com.ferousco.hydro.core.ai.LlmGenerationResponse
import com.ferousco.hydro.core.ai.LlmInputContent
import com.ferousco.hydro.core.ai.LlmMessageRole
import com.ferousco.hydro.core.ai.LlmModelCatalog
import com.ferousco.hydro.core.ai.LlmModelSpec
import com.ferousco.hydro.core.ai.LlmModalityMode
import com.ferousco.hydro.core.ai.LlmNativeToolCall
import com.ferousco.hydro.core.ai.LlmReadiness
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.NoOpDiagnosticLogger
import com.ferousco.hydro.core.provider.HttpPostRequest
import com.ferousco.hydro.core.provider.HttpTransport
import com.ferousco.hydro.core.provider.RawHttpResponse
import com.ferousco.hydro.core.provider.UrlConnectionHttpTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URI
import java.time.Duration

/**
 * Configuration for [GeminiApiLlmClient].
 *
 * The API key is a secret. It is read from `local.properties`/the environment at build time by
 * `:app` and never persisted by the core engine. Anything embedded in an APK can be extracted by a
 * determined attacker, so a production build should proxy Gemini through a server that holds the
 * key instead of shipping it.
 */
data class GeminiApiConfig(
    val apiKey: String,
    val model: HostedLlmModelSpec = LlmModelCatalog.DEFAULT,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(60),
    /**
     * Whether Google may retain the interaction for later retrieval.
     *
     * Hydro keeps this `false`: every turn replays its own history, so nothing needs to be stored
     * server-side, and location-bearing prompts are not retained by default.
     */
    val storeInteractions: Boolean = false
) {
    init {
        require(model.providerId == PROVIDER_ID) {
            "GeminiApiConfig requires a '$PROVIDER_ID' model spec, got '${model.providerId}'."
        }
        require(!connectTimeout.isNegative && !connectTimeout.isZero) { "connectTimeout must be positive." }
        require(!readTimeout.isNegative && !readTimeout.isZero) { "readTimeout must be positive." }
    }

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val PROVIDER_ID: String = "gemini"
    }
}

/**
 * [LlmClient] backed by Google's Gemini Interactions API.
 *
 * Wire contract (verified against the Gemini API reference, October 2026):
 * - `POST https://generativelanguage.googleapis.com/v1beta/interactions`
 * - header `x-goog-api-key`
 * - `tools: [{ "type": "function", "name", "description", "parameters" }]`
 * - a turn that needs data returns `steps: [{ "type": "function_call", "name", "arguments", "id" }]`
 * - results are returned as `{ "type": "function_result", "name", "call_id", "result": [...] }`
 *
 * Hydro runs statelessly (`store: false`): the orchestrator owns conversation history, so every
 * request replays the full step list. That keeps the server from retaining location-bearing
 * prompts and makes the client trivially testable with a fake transport.
 *
 * Deliberate limitations:
 * - Only text and tool results are sent. Image/audio parts are rejected explicitly rather than
 *   guessed at; the client advertises [LlmModalityMode.TEXT_ONLY] so the orchestrator fails fast
 *   with a clear message.
 * - The Interactions API exposes `seed` and `max_output_tokens` but no temperature/top-k/top-p, so
 *   those [com.ferousco.hydro.core.ai.LlmSamplingParams] fields are not sent.
 * - Streaming is not implemented; [generateStream] emits the single final answer.
 */
class GeminiApiLlmClient(
    private val config: GeminiApiConfig,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) : LlmClient {

    override val modelSpec: LlmModelSpec get() = config.model

    /**
     * Reported to the orchestrator. Text-only until media parts are implemented, which keeps the
     * failure explicit instead of sending bytes the API would reject.
     */
    private val runtimeInfo: LlmActiveRuntimeInfo = LlmActiveRuntimeInfo(
        modelSpec = config.model,
        activeBackend = LlmExecutionBackend.REMOTE_API,
        modalityMode = LlmModalityMode.TEXT_ONLY,
        fallbackApplied = true,
        fallbackReason = "media_input_not_implemented"
    )

    override fun checkReadiness(): LlmReadiness =
        if (config.isConfigured) {
            LlmReadiness.Ready(modelSpec = modelSpec)
        } else {
            LlmReadiness.CredentialsMissing(
                modelSpec = modelSpec,
                reason = "No Gemini API key is configured, so ${modelSpec.displayName} is unavailable.",
                setupHint = "Set GEMINI_API_KEY in local.properties (or the environment) and rebuild. " +
                    "The deterministic Hydro engine keeps working without it."
            )
        }

    override fun activeRuntimeInfoOrNull(): LlmActiveRuntimeInfo? =
        if (config.isConfigured) runtimeInfo else null

    override suspend fun initialize(): EngineResult<LlmActiveRuntimeInfo> =
        if (config.isConfigured) {
            EngineResult.Success(runtimeInfo)
        } else {
            EngineResult.Failure(
                EngineError.MissingRequiredInputs(
                    operation = "GeminiApiLlmClient.initialize",
                    missingFields = listOf("GEMINI_API_KEY"),
                    message = "Gemini API key is not configured; hosted inference cannot start."
                )
            )
        }

    override suspend fun generate(request: LlmGenerationRequest): EngineResult<LlmGenerationResponse> {
        if (!config.isConfigured) {
            return EngineResult.Failure(
                EngineError.MissingRequiredInputs(
                    operation = "GeminiApiLlmClient.generate",
                    missingFields = listOf("GEMINI_API_KEY"),
                    message = "Gemini API key is not configured; refusing to send a request."
                )
            )
        }

        val unsupported = request.messages
            .flatMap { it.contents }
            .firstOrNull { it !is LlmInputContent.Text && it !is LlmInputContent.ToolResult }
        if (unsupported != null) {
            return EngineResult.Failure(
                EngineError.UnsupportedCalculation(
                    methodId = "gemini_media_input",
                    reason = "MEDIA_INPUT_NOT_IMPLEMENTED",
                    message = "Gemini media input is not implemented; only text and tool results can be sent."
                )
            )
        }

        val body = SafeJsonCodec.stringify(buildRequestBody(request))
        val response = when (
            val http = transport.post(
                request = HttpPostRequest(
                    uri = URI.create(config.model.apiEndpoint),
                    headers = buildMap {
                        put("Content-Type", "application/json")
                        put("Accept", "application/json")
                        // Never logged anywhere: the key is only placed in this header.
                        put("x-goog-api-key", config.apiKey)
                    },
                    body = body,
                    connectTimeout = config.connectTimeout,
                    readTimeout = config.readTimeout
                ),
                providerId = GeminiApiConfig.PROVIDER_ID
            )
        ) {
            is EngineResult.Success -> http.value
            is EngineResult.Failure -> return EngineResult.Failure(http.error)
        }

        if (response.statusCode !in 200..299) {
            return EngineResult.Failure(toProviderError(response))
        }

        return parseInteraction(response.body)
    }

    /**
     * Emits the single final answer. Gemini's SSE streaming is not implemented yet; callers that
     * need token-by-token output should use [generate] and render once.
     */
    override fun generateStream(request: LlmGenerationRequest): Flow<String> = flow {
        when (val result = generate(request)) {
            is EngineResult.Success -> emit(result.value.text)
            is EngineResult.Failure -> throw IllegalStateException(result.error.message)
        }
    }

    override fun close() = Unit

    // ---------------------------------------------------------------- request

    private fun buildRequestBody(request: LlmGenerationRequest): JsonValue.ObjectValue {
        val entries = linkedMapOf<String, JsonValue>(
            "model" to JsonValue.StringValue(config.model.modelId),
            "system_instruction" to JsonValue.StringValue(request.systemInstruction),
            "store" to JsonValue.BooleanValue(config.storeInteractions),
            "input" to JsonValue.ArrayValue(buildInputSteps(request.messages))
        )

        if (request.enableNativeToolDeclarations && request.toolDeclarations.isNotEmpty()) {
            entries["tools"] = JsonValue.ArrayValue(request.toolDeclarations.map(::toGeminiTool))
        }

        entries["generation_config"] = JsonValue.ObjectValue(
            linkedMapOf(
                "max_output_tokens" to SafeJsonCodec.number(request.maxOutputTokens),
                "seed" to SafeJsonCodec.number(request.sampling.seed)
            )
        )

        return JsonValue.ObjectValue(entries)
    }

    private fun toGeminiTool(declaration: JsonValue.ObjectValue): JsonValue.ObjectValue {
        val name = declaration.getString("name")
            ?: throw IllegalArgumentException("Tool declaration is missing a 'name'.")
        return JsonValue.ObjectValue(
            linkedMapOf(
                "type" to JsonValue.StringValue("function"),
                "name" to JsonValue.StringValue(name),
                "description" to JsonValue.StringValue(declaration.getString("description").orEmpty()),
                "parameters" to sanitizeSchema(
                    declaration["parameters"]
                        ?: JsonValue.ObjectValue(
                            linkedMapOf("type" to JsonValue.StringValue("object"))
                        )
                )
            )
        )
    }

    /**
     * Reduces a JSON Schema to the subset the Gemini `Schema` object accepts.
     *
     * Hydro's tool schemas carry `additionalProperties`, which is a JSON-Schema keyword but not part
     * of Gemini's OpenAPI-derived schema. Sending it risks a 400 for the whole request, and dropping
     * it costs nothing: `HydroToolRegistry` still rejects unexpected arguments before execution.
     */
    private fun sanitizeSchema(value: JsonValue): JsonValue = when (value) {
        is JsonValue.ObjectValue -> {
            val sanitized = LinkedHashMap<String, JsonValue>()
            for ((key, child) in value.entries) {
                when (key) {
                    "properties" -> {
                        val properties = child as? JsonValue.ObjectValue ?: continue
                        sanitized[key] = JsonValue.ObjectValue(
                            properties.entries.entries.associateTo(LinkedHashMap()) { (propertyName, schema) ->
                                propertyName to sanitizeSchema(schema)
                            }
                        )
                    }
                    "items" -> sanitized[key] = sanitizeSchema(child)
                    else -> if (key in GEMINI_SCHEMA_KEYS) sanitized[key] = child
                }
            }
            JsonValue.ObjectValue(sanitized)
        }
        is JsonValue.ArrayValue -> JsonValue.ArrayValue(value.items.map(::sanitizeSchema))
        else -> value
    }

    private fun buildInputSteps(messages: List<LlmConversationMessage>): List<JsonValue> =
        messages.flatMap { message ->
            when (message.role) {
                LlmMessageRole.USER -> listOf(userInputStep(message))
                LlmMessageRole.MODEL -> modelSteps(message)
                LlmMessageRole.TOOL -> message.contents
                    .filterIsInstance<LlmInputContent.ToolResult>()
                    .map(::functionResultStep)
            }
        }

    private fun userInputStep(message: LlmConversationMessage): JsonValue.ObjectValue {
        val content = message.contents.mapNotNull { part ->
            (part as? LlmInputContent.Text)?.let { text ->
                JsonValue.ObjectValue(
                    linkedMapOf(
                        "type" to JsonValue.StringValue("text"),
                        "text" to JsonValue.StringValue(text.text)
                    )
                )
            }
        }
        return JsonValue.ObjectValue(
            linkedMapOf(
                "type" to JsonValue.StringValue("user_input"),
                "content" to JsonValue.ArrayValue(content)
            )
        )
    }

    private fun modelSteps(message: LlmConversationMessage): List<JsonValue> = buildList {
        // Function calls first: they are what the following function_result steps refer to.
        for (call in message.nativeToolCalls) {
            add(
                JsonValue.ObjectValue(
                    linkedMapOf(
                        "type" to JsonValue.StringValue("function_call"),
                        "name" to JsonValue.StringValue(call.toolName),
                        "arguments" to call.arguments
                    ).apply {
                        call.callId?.let { put("id", JsonValue.StringValue(it)) }
                    }
                )
            )
        }

        val text = message.contents
            .filterIsInstance<LlmInputContent.Text>()
            .joinToString(separator = "") { it.text }
        if (text.isNotBlank()) {
            add(
                JsonValue.ObjectValue(
                    linkedMapOf(
                        "type" to JsonValue.StringValue("model_output"),
                        "content" to JsonValue.ArrayValue(
                            listOf(
                                JsonValue.ObjectValue(
                                    linkedMapOf(
                                        "type" to JsonValue.StringValue("text"),
                                        "text" to JsonValue.StringValue(text)
                                    )
                                )
                            )
                        )
                    )
                )
            )
        }
    }

    private fun functionResultStep(result: LlmInputContent.ToolResult): JsonValue.ObjectValue =
        JsonValue.ObjectValue(
            linkedMapOf<String, JsonValue>(
                "type" to JsonValue.StringValue("function_result"),
                "name" to JsonValue.StringValue(result.toolName),
                "result" to JsonValue.ArrayValue(
                    listOf(
                        JsonValue.ObjectValue(
                            linkedMapOf(
                                "type" to JsonValue.StringValue("text"),
                                "text" to JsonValue.StringValue(SafeJsonCodec.stringify(result.resultJson))
                            )
                        )
                    )
                )
            ).apply {
                result.callId?.let { put("call_id", JsonValue.StringValue(it)) }
            }
        )

    // --------------------------------------------------------------- response

    private fun parseInteraction(body: String): EngineResult<LlmGenerationResponse> {
        val parsed = when (
            val json = SafeJsonCodec.parse(
                input = body,
                sourceName = "gemini_interaction",
                maxChars = 4 * SafeJsonCodec.DEFAULT_MAX_CHARS,
                maxDepth = 32
            )
        ) {
            is EngineResult.Success -> json.value
            is EngineResult.Failure -> return EngineResult.Failure(json.error)
        }

        val root = parsed as? JsonValue.ObjectValue
            ?: return EngineResult.Failure(invalidResponse("interaction_root", "Expected a JSON object."))

        val status = root.getString("status")
        if (status == "failed" || status == "cancelled" || status == "incomplete") {
            val reason = root.getObject("error")?.getString("message")
                ?: "Gemini interaction finished with status '$status'."
            return EngineResult.Failure(
                EngineError.ProviderError(
                    providerId = GeminiApiConfig.PROVIDER_ID,
                    statusCode = null,
                    providerReason = status,
                    isTransient = false,
                    message = reason
                )
            )
        }

        val steps = root.getArray("steps")?.items.orEmpty()
        val text = StringBuilder()
        val toolCalls = mutableListOf<LlmNativeToolCall>()

        for (step in steps) {
            val stepObject = step as? JsonValue.ObjectValue ?: continue
            when (stepObject.getString("type")) {
                "function_call" -> {
                    val name = stepObject.getString("name")
                        ?: return EngineResult.Failure(
                            invalidResponse("function_call.name", "Function call step is missing a name.")
                        )
                    toolCalls.add(
                        LlmNativeToolCall(
                            toolName = name,
                            arguments = stepObject.getObject("arguments")
                                ?: JsonValue.ObjectValue(emptyMap()),
                            callId = stepObject.getString("id")
                        )
                    )
                }
                "model_output" -> {
                    val parts = stepObject.getArray("content")?.items.orEmpty()
                    for (part in parts) {
                        val partObject = part as? JsonValue.ObjectValue ?: continue
                        if (partObject.getString("type") == "text") {
                            partObject.getString("text")?.let { text.append(it) }
                        }
                    }
                }
                // "thought" steps and any future step type are intentionally ignored: only text
                // and executable tool calls may reach the user or the registry.
                else -> Unit
            }
        }

        return EngineResult.Success(
            LlmGenerationResponse(
                text = text.toString(),
                nativeToolCalls = toolCalls,
                runtimeInfo = runtimeInfo
            )
        )
    }

    private fun toProviderError(response: RawHttpResponse): EngineError {
        val parsedError = runCatching {
            val parsed = SafeJsonCodec.parse(
                input = response.body,
                sourceName = "gemini_error",
                maxChars = 64 * 1024,
                maxDepth = 12
            )
            val root = (parsed as? EngineResult.Success)?.value as? JsonValue.ObjectValue
            root?.getObject("error")
        }.getOrNull()

        val apiMessage = parsedError?.getString("message")
        val apiStatus = parsedError?.getString("status")
        val isTransient = response.statusCode == 429 || response.statusCode in 500..599
        val retryAfter = response.firstHeader("Retry-After")?.toLongOrNull()

        val message = when (response.statusCode) {
            400 -> "Gemini rejected the request (400${apiStatus?.let { " $it" } ?: ""}): " +
                (apiMessage ?: "malformed request.")
            401, 403 -> "Gemini rejected the API key (${response.statusCode}). Check GEMINI_API_KEY."
            404 -> "Gemini model '${config.model.modelId}' was not found (404)."
            429 -> "Gemini rate limit or quota exceeded (429)."
            in 500..599 -> "Gemini is temporarily unavailable (${response.statusCode})."
            else -> "Gemini request failed (${response.statusCode}): ${apiMessage ?: "no details provided."}"
        }

        logger.warn(
            COMPONENT,
            "Gemini interaction failed",
            mapOf(
                "status" to response.statusCode.toString(),
                "reason" to (apiStatus ?: "unknown"),
                "model" to config.model.modelId
            )
        )

        return EngineError.ProviderError(
            providerId = GeminiApiConfig.PROVIDER_ID,
            statusCode = response.statusCode,
            providerReason = apiStatus ?: "http_${response.statusCode}",
            isTransient = isTransient,
            retryAfterSeconds = retryAfter,
            message = message
        )
    }

    private fun invalidResponse(stage: String, message: String): EngineError =
        EngineError.InvalidResponse(
            providerId = GeminiApiConfig.PROVIDER_ID,
            fieldOrStage = stage,
            message = message
        )

    private companion object {
        const val COMPONENT = "GeminiApiLlmClient"

        /**
         * Fields accepted by the Gemini `Schema` object (OpenAPI-derived subset).
         *
         * `additionalProperties` is deliberately absent; see [sanitizeSchema].
         */
        val GEMINI_SCHEMA_KEYS: Set<String> = setOf(
            "type",
            "format",
            "description",
            "nullable",
            "enum",
            "minItems",
            "maxItems",
            "minProperties",
            "maxProperties",
            "minimum",
            "maximum",
            "minLength",
            "maxLength",
            "pattern",
            "example",
            "propertyOrdering",
            "default",
            "required"
        )
    }
}
