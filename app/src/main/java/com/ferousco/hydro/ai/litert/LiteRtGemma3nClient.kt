package com.ferousco.hydro.ai.litert

import android.content.Context
import com.ferousco.hydro.core.ai.Gemma3nModelSpec
import com.ferousco.hydro.core.ai.LlmActiveRuntimeInfo
import com.ferousco.hydro.core.ai.LlmConversationMessage
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
import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.NoOpDiagnosticLogger
import com.ferousco.hydro.core.tools.HydroToolRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin seam wrapping native `com.google.ai.edge.litertlm.Engine` operations so cascading
 * hardware-backend initialization and message assembly can be deterministically unit-tested.
 */
interface LiteRtEngineSession : AutoCloseable {
    fun sendTurn(
        systemInstruction: String,
        historyExceptLatest: List<LlmConversationMessage>,
        latestMessage: LlmConversationMessage,
        samplingTopK: Int,
        samplingTopP: Double,
        samplingTemperature: Double,
        samplingSeed: Int,
        maxOutputTokens: Int,
        toolProviders: List<ToolProvider>
    ): Pair<String, List<LlmNativeToolCall>>

    fun streamTurn(
        systemInstruction: String,
        historyExceptLatest: List<LlmConversationMessage>,
        latestMessage: LlmConversationMessage,
        samplingTopK: Int,
        samplingTopP: Double,
        samplingTemperature: Double,
        samplingSeed: Int,
        maxOutputTokens: Int,
        toolProviders: List<ToolProvider>
    ): Flow<String>
}

fun interface LiteRtEngineFactory {
    fun openAndInitialize(
        modelPath: String,
        backend: LlmHardwareBackend,
        enableMultimodal: Boolean,
        cpuThreads: Int,
        cacheDirPath: String?,
        nativeLibraryDirPath: String?
    ): LiteRtEngineSession
}

object DefaultNativeLiteRtEngineFactory : LiteRtEngineFactory {
    override fun openAndInitialize(
        modelPath: String,
        backend: LlmHardwareBackend,
        enableMultimodal: Boolean,
        cpuThreads: Int,
        cacheDirPath: String?,
        nativeLibraryDirPath: String?
    ): LiteRtEngineSession {
        val mainBackend = toLiteRtBackend(backend, cpuThreads, nativeLibraryDirPath)
        val visionBackend = if (enableMultimodal) {
            when (backend) {
                LlmHardwareBackend.GPU -> Backend.GPU()
                LlmHardwareBackend.NPU -> Backend.NPU(nativeLibraryDir = nativeLibraryDirPath.orEmpty())
                LlmHardwareBackend.CPU -> Backend.CPU(threadCount = cpuThreads)
            }
        } else {
            null
        }
        // Per LiteRT-LM Android inference guidelines, audioBackend defaults to Backend.CPU()
        val audioBackend = if (enableMultimodal) Backend.CPU(threadCount = cpuThreads) else null

        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = mainBackend,
            visionBackend = visionBackend,
            audioBackend = audioBackend,
            maxNumTokens = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM.liteRtMaxContextTokens,
            cacheDir = cacheDirPath
        )

        val engine = Engine(engineConfig)
        try {
            engine.initialize()
        } catch (t: Throwable) {
            runCatching { engine.close() }
            throw t
        }
        return NativeLiteRtEngineSession(engine)
    }

    private fun toLiteRtBackend(
        backend: LlmHardwareBackend,
        cpuThreads: Int,
        nativeLibraryDirPath: String?
    ): Backend = when (backend) {
        LlmHardwareBackend.CPU -> Backend.CPU(threadCount = cpuThreads)
        LlmHardwareBackend.GPU -> Backend.GPU()
        LlmHardwareBackend.NPU -> Backend.NPU(nativeLibraryDir = nativeLibraryDirPath.orEmpty())
    }
}

private class NativeLiteRtEngineSession(
    private val engine: Engine
) : LiteRtEngineSession {

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
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(systemInstruction),
            initialMessages = historyExceptLatest.map(::toLiteRtMessage),
            tools = toolProviders,
            samplerConfig = SamplerConfig(
                topK = samplingTopK,
                topP = samplingTopP,
                temperature = samplingTemperature,
                seed = samplingSeed
            ),
            automaticToolCalling = false,
            maxOutputToken = maxOutputTokens
        )

        engine.createConversation(conversationConfig).use { conversation ->
            val response: Message = conversation.sendMessage(
                message = toLiteRtMessage(latestMessage),
                maxOutputToken = maxOutputTokens
            )
            val parsedNativeCalls = response.toolCalls.map { tc ->
                LlmNativeToolCall(
                    toolName = tc.name,
                    arguments = anyMapToJsonObject(tc.arguments)
                )
            }
            return response.toString() to parsedNativeCalls
        }
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
    ): Flow<String> = flow {
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(systemInstruction),
            initialMessages = historyExceptLatest.map(::toLiteRtMessage),
            tools = toolProviders,
            samplerConfig = SamplerConfig(
                topK = samplingTopK,
                topP = samplingTopP,
                temperature = samplingTemperature,
                seed = samplingSeed
            ),
            automaticToolCalling = false,
            maxOutputToken = maxOutputTokens
        )
        val conversation = engine.createConversation(conversationConfig)
        try {
            conversation.sendMessageAsync(toLiteRtContents(latestMessage.contents))
                .map { it.toString() }
                .collect { chunk -> emit(chunk) }
        } finally {
            runCatching { conversation.close() }
        }
    }

    override fun close() {
        runCatching { engine.close() }
    }

    companion object {
        private fun toLiteRtMessage(msg: LlmConversationMessage): Message = when (msg.role) {
            LlmMessageRole.USER -> Message.user(toLiteRtContents(msg.contents))
            LlmMessageRole.MODEL -> Message.model(toLiteRtContents(msg.contents))
            LlmMessageRole.TOOL -> {
                val toolResults = msg.contents.filterIsInstance<LlmInputContent.ToolResult>()
                if (toolResults.isNotEmpty()) {
                    val parts = toolResults.map { tr ->
                        Content.ToolResponse(
                            name = tr.toolName,
                            response = jsonObjectToRawMap(tr.resultJson)
                        )
                    }
                    Message.tool(Contents.of(parts))
                } else {
                    Message.user(toLiteRtContents(msg.contents))
                }
            }
        }

        private fun toLiteRtContents(contents: List<LlmInputContent>): Contents {
            val ordered = contents.orderedTextFirstForLiteRt()
            val mapped = ordered.map { part ->
                when (part) {
                    is LlmInputContent.Text -> Content.Text(part.text)
                    is LlmInputContent.ImageFile -> Content.ImageFile(part.absolutePath)
                    is LlmInputContent.ImageBytes -> Content.ImageBytes(part.bytes)
                    is LlmInputContent.AudioFile -> Content.AudioFile(part.absolutePath)
                    is LlmInputContent.AudioBytes -> Content.AudioBytes(part.bytes)
                    is LlmInputContent.ToolResult -> Content.Text(SafeJsonCodec.stringify(part.resultJson))
                }
            }
            return Contents.of(mapped)
        }

        private fun anyMapToJsonObject(map: Map<String, Any?>): JsonValue.ObjectValue {
            val entries = LinkedHashMap<String, JsonValue>()
            for ((k, v) in map) {
                entries[k] = anyToJsonValue(v)
            }
            return JsonValue.ObjectValue(entries)
        }

        private fun anyToJsonValue(v: Any?): JsonValue = when (v) {
            null -> JsonValue.NullValue
            is String -> JsonValue.StringValue(v)
            is Boolean -> JsonValue.BooleanValue(v)
            is Number -> SafeJsonCodec.number(v.toDouble())
            is Map<*, *> -> {
                val obj = LinkedHashMap<String, JsonValue>()
                for ((mk, mv) in v) {
                    if (mk is String) obj[mk] = anyToJsonValue(mv)
                }
                JsonValue.ObjectValue(obj)
            }
            is Iterable<*> -> JsonValue.ArrayValue(v.map(::anyToJsonValue))
            else -> JsonValue.StringValue(v.toString())
        }

        private fun jsonObjectToRawMap(obj: JsonValue.ObjectValue): Map<String, Any?> =
            obj.entries.mapValues { (_, v) -> jsonValueToRawAny(v) }

        private fun jsonValueToRawAny(v: JsonValue): Any? = when (v) {
            JsonValue.NullValue -> null
            is JsonValue.BooleanValue -> v.value
            is JsonValue.StringValue -> v.value
            is JsonValue.NumberValue -> v.toLongExactOrNull() ?: v.toDoubleOrNull()
            is JsonValue.ArrayValue -> v.items.map(::jsonValueToRawAny)
            is JsonValue.ObjectValue -> jsonObjectToRawMap(v)
        }
    }
}

/**
 * Android on-device client for `google/gemma-3n-E2B-it-litert-lm` (`gemma-3n-E2B-it-int4.litertlm`)
 * powered by `com.google.ai.edge.litertlm`.
 *
 * Implements LiteRT-LM's 3-stage cascading initialization on `Dispatchers.IO`:
 * 1. Preferred accelerator (`GPU`, `NPU`, or `CPU`) with multimodal (`vision` + `audio` executors),
 * 2. Fallback to multimodal `CPU` (`4` XNNPACK threads),
 * 3. Fallback to text-only `CPU` (`4` XNNPACK threads).
 */
class LiteRtGemma3nClient(
    private val candidateModelFiles: List<File>,
    override val modelSpec: Gemma3nModelSpec = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM,
    private val preferredBackend: LlmHardwareBackend = LlmHardwareBackend.GPU,
    private val cpuThreadCount: Int = 4,
    private val cacheDirectory: File? = null,
    private val nativeLibraryDirPath: String? = null,
    private val detectedDeviceRamBytes: Long? = null,
    private val toolRegistry: HydroToolRegistry? = null,
    private val engineFactory: LiteRtEngineFactory = DefaultNativeLiteRtEngineFactory,
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) : OnDeviceLlmClient {

    private val mutex = Mutex()

    @Volatile
    private var session: LiteRtEngineSession? = null

    @Volatile
    private var runtimeInfo: LlmActiveRuntimeInfo? = null

    override fun checkArtifactReadiness(): ModelArtifactReadiness =
        LocalModelArtifactValidator.validate(
            candidateFiles = candidateModelFiles,
            modelSpec = modelSpec,
            detectedDeviceRamBytes = detectedDeviceRamBytes
        )

    override fun activeRuntimeInfoOrNull(): LlmActiveRuntimeInfo? = runtimeInfo

    override suspend fun initialize(): EngineResult<LlmActiveRuntimeInfo> = mutex.withLock {
        runtimeInfo?.let { return EngineResult.Success(it) }

        val ready = when (val r = checkArtifactReadiness()) {
            is ModelArtifactReadiness.Ready -> r
            is ModelArtifactReadiness.NotInstalled -> return EngineResult.Failure(
                EngineError.MissingRequiredInputs(
                    operation = "LiteRtGemma3nClient.initialize",
                    missingFields = listOf(modelSpec.expectedFileName),
                    message = r.provisioningHint
                )
            )
            is ModelArtifactReadiness.InvalidArtifact -> return EngineResult.Failure(
                EngineError.InvalidInput(
                    field = "modelPath",
                    rejectedValueSummary = r.filePath,
                    message = r.reason
                )
            )
            is ModelArtifactReadiness.InsufficientDeviceMemory -> return EngineResult.Failure(
                EngineError.UnsupportedCalculation(
                    methodId = modelSpec.huggingFaceRepoId,
                    reason = "INSUFFICIENT_DEVICE_MEMORY",
                    message = "${modelSpec.displayName} requires at least ${r.requiredRamGb} GB RAM."
                )
            )
        }

        withContext(Dispatchers.IO) {
            val cachePath = cacheDirectory?.apply { mkdirs() }?.absolutePath
            val enableMultimodalBySpec = modelSpec.supportsVision || modelSpec.supportsAudio

            // Stage 1: Preferred backend + Multimodal
            if (enableMultimodalBySpec) {
                try {
                    val s1 = engineFactory.openAndInitialize(
                        modelPath = ready.resolvedFilePath,
                        backend = preferredBackend,
                        enableMultimodal = true,
                        cpuThreads = cpuThreadCount,
                        cacheDirPath = cachePath,
                        nativeLibraryDirPath = nativeLibraryDirPath
                    )
                    val info = LlmActiveRuntimeInfo(
                        modelSpec = modelSpec,
                        activeBackend = preferredBackend,
                        modalityMode = LlmModalityMode.MULTIMODAL_TEXT_VISION_AUDIO,
                        fallbackApplied = false
                    )
                    session = s1
                    runtimeInfo = info
                    logger.info(
                        COMPONENT,
                        "Initialized Gemma 3n LiteRT-LM engine (Stage 1 multimodal)",
                        mapOf("backend" to preferredBackend.name, "model" to modelSpec.huggingFaceRepoId)
                    )
                    return@withContext EngineResult.Success(info)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (stage1Ex: Throwable) {
                    logger.warn(
                        COMPONENT,
                        "Stage 1 multimodal initialization failed; cascading to CPU multimodal",
                        mapOf(
                            "preferredBackend" to preferredBackend.name,
                            "exception" to (stage1Ex::class.simpleName ?: "Throwable")
                        )
                    )
                }

                // Stage 2: CPU backend + Multimodal (skip duplicate if preferredBackend was already CPU)
                if (preferredBackend != LlmHardwareBackend.CPU) {
                    try {
                        val s2 = engineFactory.openAndInitialize(
                            modelPath = ready.resolvedFilePath,
                            backend = LlmHardwareBackend.CPU,
                            enableMultimodal = true,
                            cpuThreads = cpuThreadCount,
                            cacheDirPath = cachePath,
                            nativeLibraryDirPath = nativeLibraryDirPath
                        )
                        val info = LlmActiveRuntimeInfo(
                            modelSpec = modelSpec,
                            activeBackend = LlmHardwareBackend.CPU,
                            modalityMode = LlmModalityMode.MULTIMODAL_TEXT_VISION_AUDIO,
                            fallbackApplied = true,
                            fallbackReason = "Fell back from ${preferredBackend.name} to CPU multimodal delegate."
                        )
                        session = s2
                        runtimeInfo = info
                        logger.info(
                            COMPONENT,
                            "Initialized Gemma 3n LiteRT-LM engine (Stage 2 CPU multimodal fallback)",
                            mapOf("backend" to "CPU")
                        )
                        return@withContext EngineResult.Success(info)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (stage2Ex: Throwable) {
                        logger.warn(
                            COMPONENT,
                            "Stage 2 CPU multimodal initialization failed; cascading to CPU text-only",
                            mapOf("exception" to (stage2Ex::class.simpleName ?: "Throwable"))
                        )
                    }
                }
            }

            // Stage 3: CPU backend + Text-only
            try {
                val s3 = engineFactory.openAndInitialize(
                    modelPath = ready.resolvedFilePath,
                    backend = LlmHardwareBackend.CPU,
                    enableMultimodal = false,
                    cpuThreads = cpuThreadCount,
                    cacheDirPath = cachePath,
                    nativeLibraryDirPath = nativeLibraryDirPath
                )
                val fellBack = enableMultimodalBySpec || preferredBackend != LlmHardwareBackend.CPU
                val info = LlmActiveRuntimeInfo(
                    modelSpec = modelSpec,
                    activeBackend = LlmHardwareBackend.CPU,
                    modalityMode = LlmModalityMode.TEXT_ONLY,
                    fallbackApplied = fellBack,
                    fallbackReason = if (fellBack) {
                        "Fell back to text-only CPU LiteRT-LM execution."
                    } else {
                        null
                    }
                )
                session = s3
                runtimeInfo = info
                logger.info(
                    COMPONENT,
                    "Initialized Gemma 3n LiteRT-LM engine (Stage 3 CPU text-only)",
                    mapOf("backend" to "CPU")
                )
                EngineResult.Success(info)
            } catch (ce: CancellationException) {
                throw ce
            } catch (stage3Ex: Throwable) {
                logger.error(
                    COMPONENT,
                    "All LiteRT-LM initialization stages failed for Gemma 3n",
                    mapOf("exception" to (stage3Ex::class.simpleName ?: "Throwable"))
                )
                EngineResult.Failure(
                    EngineError.ProviderError(
                        providerId = "litert-lm",
                        statusCode = null,
                        providerReason = stage3Ex::class.simpleName,
                        isTransient = false,
                        message = "Failed to initialize ${modelSpec.displayName} via LiteRT-LM: ${stage3Ex.message ?: "native error"}"
                    )
                )
            }
        }
    }

    override suspend fun generate(request: LlmGenerationRequest): EngineResult<LlmGenerationResponse> {
        val activeInfo = runtimeInfo ?: when (val init = initialize()) {
            is EngineResult.Success -> init.value
            is EngineResult.Failure -> return init
        }
        val activeSession = session ?: return EngineResult.Failure(
            EngineError.ProviderError(
                providerId = "litert-lm",
                statusCode = null,
                providerReason = "session_closed",
                isTransient = false,
                message = "LiteRT-LM engine session is not initialized."
            )
        )

        return withContext(Dispatchers.IO) {
            try {
                val providers = if (request.enableNativeToolDeclarations && toolRegistry != null) {
                    HydroLiteRtToolBindings.buildToolProviders(toolRegistry)
                } else {
                    emptyList()
                }
                val historyPrefix = request.messages.dropLast(1)
                val latest = request.messages.last()
                val (text, nativeToolCalls) = activeSession.sendTurn(
                    systemInstruction = request.systemInstruction,
                    historyExceptLatest = historyPrefix,
                    latestMessage = latest,
                    samplingTopK = request.sampling.topK,
                    samplingTopP = request.sampling.topP,
                    samplingTemperature = request.sampling.temperature,
                    samplingSeed = request.sampling.seed,
                    maxOutputTokens = request.maxOutputTokens,
                    toolProviders = providers
                )
                EngineResult.Success(
                    LlmGenerationResponse(
                        text = text,
                        nativeToolCalls = nativeToolCalls,
                        runtimeInfo = activeInfo
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Throwable) {
                logger.error(
                    COMPONENT,
                    "LiteRT-LM generation failed",
                    mapOf("exception" to (ex::class.simpleName ?: "Throwable"))
                )
                EngineResult.Failure(
                    EngineError.ProviderError(
                        providerId = "litert-lm",
                        statusCode = null,
                        providerReason = ex::class.simpleName,
                        isTransient = false,
                        message = "LiteRT-LM generation failed: ${ex.message ?: "native error"}"
                    )
                )
            }
        }
    }

    override fun generateStream(request: LlmGenerationRequest): Flow<String> = flow {
        val activeSession = session ?: run {
            when (val init = initialize()) {
                is EngineResult.Success -> session
                is EngineResult.Failure -> throw IllegalStateException(init.error.message)
            }
        } ?: throw IllegalStateException("LiteRT-LM session unavailable.")

        val providers = if (request.enableNativeToolDeclarations && toolRegistry != null) {
            HydroLiteRtToolBindings.buildToolProviders(toolRegistry)
        } else {
            emptyList()
        }
        activeSession.streamTurn(
            systemInstruction = request.systemInstruction,
            historyExceptLatest = request.messages.dropLast(1),
            latestMessage = request.messages.last(),
            samplingTopK = request.sampling.topK,
            samplingTopP = request.sampling.topP,
            samplingTemperature = request.sampling.temperature,
            samplingSeed = request.sampling.seed,
            maxOutputTokens = request.maxOutputTokens,
            toolProviders = providers
        ).collect { emit(it) }
    }

    override fun close() {
        val current = session
        session = null
        runtimeInfo = null
        current?.close()
    }

    companion object {
        private const val COMPONENT = "LiteRtGemma3nClient"
        private const val MODELS_SUBDIR = "models"

        /**
         * Resolves standard app-private storage locations where `gemma-3n-E2B-it-int4.litertlm`
         * may be downloaded or sideloaded (`adb push`) without requiring broad storage permissions.
         */
        fun defaultAndroidModelCandidates(
            context: Context,
            modelSpec: Gemma3nModelSpec = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM
        ): List<File> = buildList {
            context.getExternalFilesDir(MODELS_SUBDIR)?.let { extDir ->
                add(File(extDir, modelSpec.expectedFileName))
            }
            add(File(File(context.filesDir, MODELS_SUBDIR), modelSpec.expectedFileName))
        }

        fun createForAndroid(
            context: Context,
            toolRegistry: HydroToolRegistry,
            modelSpec: Gemma3nModelSpec = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM,
            preferredBackend: LlmHardwareBackend = LlmHardwareBackend.GPU,
            detectedDeviceRamBytes: Long? = null,
            logger: DiagnosticLogger = NoOpDiagnosticLogger
        ): LiteRtGemma3nClient {
            val appContext = context.applicationContext
            return LiteRtGemma3nClient(
                candidateModelFiles = defaultAndroidModelCandidates(appContext, modelSpec),
                modelSpec = modelSpec,
                preferredBackend = preferredBackend,
                cacheDirectory = File(appContext.cacheDir, "litert_lm_cache"),
                nativeLibraryDirPath = appContext.applicationInfo?.nativeLibraryDir,
                detectedDeviceRamBytes = detectedDeviceRamBytes,
                toolRegistry = toolRegistry,
                logger = logger
            )
        }
    }
}
