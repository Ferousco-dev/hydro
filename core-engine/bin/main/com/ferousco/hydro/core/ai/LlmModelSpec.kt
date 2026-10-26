package com.ferousco.hydro.core.ai

import java.io.File

/**
 * Where a model executes.
 *
 * Hydro supports two deployment shapes behind the same [LlmClient] contract:
 * - [ON_DEVICE]: open-weight weights provisioned into app-private storage and executed locally.
 * - [HOSTED_API]: a vendor inference API reached over HTTPS with a project API key.
 *
 * The orchestrator does not care which is active; only provenance and readiness differ.
 */
enum class LlmDeployment {
    ON_DEVICE,
    HOSTED_API
}

/**
 * Where inference physically runs for an active session.
 *
 * [CPU], [GPU] and [NPU] are local accelerators; [REMOTE_API] means the model is served by a
 * hosted inference endpoint and no local compute is involved.
 */
enum class LlmExecutionBackend {
    CPU,
    GPU,
    NPU,
    REMOTE_API
}

/**
 * Active modality capabilities negotiated during client initialization.
 */
enum class LlmModalityMode {
    MULTIMODAL_TEXT_VISION_AUDIO,
    TEXT_ONLY
}

/**
 * On-disk packaging format of a local model artifact.
 *
 * Hydro never bundles weights in the APK; artifacts are provisioned into app-private storage.
 */
enum class LlmArtifactFormat(val fileExtension: String) {
    /** Google LiteRT-LM bundle (`.litertlm`). */
    LITERT_LM(".litertlm"),

    /** llama.cpp GGUF container (`.gguf`). */
    GGUF(".gguf"),

    /** TensorFlow Lite / LiteRT task bundle (`.task`). */
    TASK(".task")
}

/**
 * How a given model exposes tool calling to the runtime.
 *
 * The orchestrator supports all three, but the distinction matters for benchmarking: a model that
 * only emits prompt-structured JSON needs the JSON tool protocol in its system prompt, whereas a
 * native-function-calling model can rely on runtime tool declarations.
 */
enum class LlmToolCallingStyle {
    /** Runtime-native tool/function declarations (e.g. Gemini API, LiteRT-LM `ToolCall`). */
    NATIVE_FUNCTION_CALLING,

    /** The model emits a `{"tool_call": {...}}` JSON object in its text output. */
    PROMPT_JSON,

    /** Either path has been observed; benchmark the actual artifact before choosing one. */
    HYBRID
}

/**
 * Sampling configuration presets for model inference.
 *
 * Not every deployment honours every field; clients map what the provider exposes and document
 * the rest (the Gemini Interactions API, for example, accepts a seed and an output-token cap but
 * no temperature).
 */
data class LlmSamplingParams(
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val seed: Int = 0
) {
    init {
        require(topK > 0) { "topK must be > 0." }
        require(topP in 0.0..1.0) { "topP must be in [0.0, 1.0]." }
        require(temperature >= 0.0 && temperature.isFinite()) { "temperature must be finite and >= 0.0." }
    }

    companion object {
        /**
         * Low-temperature preset for Hydro intent parsing, JSON tool calls, and grounded summaries
         * so the model stays faithful to deterministic tool outputs without inventing numbers.
         */
        val GROUNDED_HYDRO = LlmSamplingParams(
            topK = 40,
            topP = 0.90,
            temperature = 0.15,
            seed = 0
        )

        /**
         * Reference default sampling config published for `google/gemma-3n-E2B-it-litert-lm`
         * in Google AI Edge Gallery (`topK=64, topP=0.95, temperature=1.0`).
         */
        val GEMMA_3N_REFERENCE_DEFAULT = LlmSamplingParams(
            topK = 64,
            topP = 0.95,
            temperature = 1.0,
            seed = 0
        )
    }
}

/**
 * Runtime-agnostic identity of a language model Hydro can orchestrate.
 *
 * Hydro is deliberately model-agnostic: the orchestrator depends on [LlmClient] and reads only the
 * identity fields below. Deployment-specific detail lives on the two subtypes:
 * [OnDeviceLlmModelSpec] (local artifact) and [HostedLlmModelSpec] (vendor API).
 */
sealed interface LlmModelSpec {
    val displayName: String

    /** Stable identifier used in traces and provenance (repository id or vendor model id). */
    val modelId: String

    /** Context window Hydro budgets against when assembling prompts. */
    val runtimeMaxContextTokens: Int

    val toolCallingStyle: LlmToolCallingStyle

    val deployment: LlmDeployment

    val supportsVision: Boolean

    val supportsAudio: Boolean

    val licenseName: String

    val licenseUrl: String
}

/**
 * An open-weight model provisioned as a local artifact and executed on the device.
 *
 * `metadataVerified = false` marks a candidate whose repository id, artifact size, context window,
 * license and quantization have **not** been verified against the publisher. Such candidates must
 * not be shipped or used for provisioning until they are.
 */
data class OnDeviceLlmModelSpec(
    override val displayName: String,
    override val modelId: String,
    val expectedFileName: String,
    val artifactFormat: LlmArtifactFormat,
    val pinnedCommitHash: String? = null,
    /**
     * Exact artifact size in bytes when verified, or `null` when unknown. `null` disables
     * exact-size validation, so provisioning must fall back to a minimum-size sanity check.
     */
    val expectedSizeBytes: Long? = null,
    val effectiveParametersBillions: Double? = null,
    val minDeviceRamGb: Int,
    override val runtimeMaxContextTokens: Int,
    val architectureMaxContextTokens: Int? = null,
    val quantizationSummary: String,
    override val supportsVision: Boolean,
    override val supportsAudio: Boolean,
    override val toolCallingStyle: LlmToolCallingStyle,
    val supportedBackends: Set<LlmExecutionBackend>,
    override val licenseName: String,
    override val licenseUrl: String,
    val requiresUserLicenseAcceptance: Boolean,
    /** `true` only when every field above was checked against the publisher's own metadata. */
    val metadataVerified: Boolean
) : LlmModelSpec {

    override val deployment: LlmDeployment get() = LlmDeployment.ON_DEVICE

    init {
        require(modelId.isNotBlank()) { "modelId must not be blank." }
        require(expectedFileName.endsWith(artifactFormat.fileExtension)) {
            "Model artifact for ${artifactFormat.name} must use the '${artifactFormat.fileExtension}' extension, got '$expectedFileName'."
        }
        require(expectedSizeBytes == null || expectedSizeBytes > 0) {
            "expectedSizeBytes must be positive when provided."
        }
        require(minDeviceRamGb > 0) { "minDeviceRamGb must be positive." }
        require(runtimeMaxContextTokens > 0) { "runtimeMaxContextTokens must be positive." }
        require(supportedBackends.isNotEmpty()) { "supportedBackends must not be empty." }
        require(LlmExecutionBackend.REMOTE_API !in supportedBackends) {
            "A local artifact cannot advertise the REMOTE_API backend."
        }
        require(quantizationSummary.isNotBlank()) { "quantizationSummary must not be blank." }
    }
}

/**
 * A model served by a vendor inference API.
 *
 * No weights are stored locally, so there is no artifact to validate, no device RAM floor, and no
 * per-device quantization. The trade-offs move elsewhere: the device needs network connectivity,
 * the request leaves the device, and the project API key is a secret.
 */
data class HostedLlmModelSpec(
    override val displayName: String,
    override val modelId: String,
    override val runtimeMaxContextTokens: Int,
    override val toolCallingStyle: LlmToolCallingStyle,
    /** Short provider identifier used in traces and error messages (e.g. `gemini`). */
    val providerId: String,
    /** Fully qualified HTTPS endpoint that accepts an interaction/chat request. */
    val apiEndpoint: String,
    override val supportsVision: Boolean,
    override val supportsAudio: Boolean,
    override val licenseName: String,
    override val licenseUrl: String,
    /** Human-readable note about where prompts and tool results are processed. */
    val dataHandlingNote: String
) : LlmModelSpec {

    override val deployment: LlmDeployment get() = LlmDeployment.HOSTED_API

    init {
        require(modelId.isNotBlank()) { "modelId must not be blank." }
        require(providerId.isNotBlank()) { "providerId must not be blank." }
        require(runtimeMaxContextTokens > 0) { "runtimeMaxContextTokens must be positive." }
        require(apiEndpoint.startsWith("https://")) {
            "Hosted model endpoints must use HTTPS, got '$apiEndpoint'."
        }
        require(dataHandlingNote.isNotBlank()) { "dataHandlingNote must not be blank." }
    }
}

/**
 * Verified local models, benchmark candidates, and the hosted Gemini models Hydro targets.
 *
 * The three `BENCHMARK_CANDIDATES` come from the Hydro product document §7. They are **unverified
 * candidates to benchmark**, not a selection: licensing, artifact availability, runtime
 * compatibility and quantization must be checked before any is shipped.
 */
object LlmModelCatalog {

    private const val GEMINI_INTERACTIONS_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/interactions"

    /** Verified LiteRT-LM packaging of `google/gemma-3n-E2B-it-litert-lm` (8 GB+ devices). */
    val GEMMA_3N_E2B_IT_LITERT_LM = OnDeviceLlmModelSpec(
        displayName = "Gemma 3n E2B-IT (LiteRT-LM int4)",
        modelId = "google/gemma-3n-E2B-it-litert-lm",
        expectedFileName = "gemma-3n-E2B-it-int4.litertlm",
        artifactFormat = LlmArtifactFormat.LITERT_LM,
        pinnedCommitHash = "ba9ca88da013b537b6ed38108be609b8db1c3a16",
        expectedSizeBytes = 3_655_827_456L,
        effectiveParametersBillions = 2.0,
        minDeviceRamGb = 8,
        runtimeMaxContextTokens = 4096,
        architectureMaxContextTokens = 32_768,
        quantizationSummary = "int4 weights, float activations",
        supportsVision = true,
        supportsAudio = true,
        toolCallingStyle = LlmToolCallingStyle.HYBRID,
        supportedBackends = setOf(
            LlmExecutionBackend.CPU,
            LlmExecutionBackend.GPU,
            LlmExecutionBackend.NPU
        ),
        licenseName = "Gemma Terms of Use",
        licenseUrl = "https://ai.google.dev/gemma/terms",
        requiresUserLicenseAcceptance = true,
        metadataVerified = true
    )

    /** Verified 4B effective-parameter sibling for 12 GB+ devices. */
    val GEMMA_3N_E4B_IT_LITERT_LM = OnDeviceLlmModelSpec(
        displayName = "Gemma 3n E4B-IT (LiteRT-LM int4)",
        modelId = "google/gemma-3n-E4B-it-litert-lm",
        expectedFileName = "gemma-3n-E4B-it-int4.litertlm",
        artifactFormat = LlmArtifactFormat.LITERT_LM,
        pinnedCommitHash = "297ed75955702dec3503e00c2c2ecbbf475300bc",
        expectedSizeBytes = 4_919_541_760L,
        effectiveParametersBillions = 4.0,
        minDeviceRamGb = 12,
        runtimeMaxContextTokens = 4096,
        architectureMaxContextTokens = 32_768,
        quantizationSummary = "int4 weights, float activations",
        supportsVision = true,
        supportsAudio = true,
        toolCallingStyle = LlmToolCallingStyle.HYBRID,
        supportedBackends = setOf(
            LlmExecutionBackend.CPU,
            LlmExecutionBackend.GPU,
            LlmExecutionBackend.NPU
        ),
        licenseName = "Gemma Terms of Use",
        licenseUrl = "https://ai.google.dev/gemma/terms",
        requiresUserLicenseAcceptance = true,
        metadataVerified = true
    )

    /**
     * Hydro's default hosted model: the current recommended Flash tier.
     *
     * Chosen for reliable native function calling at low latency and cost. Swap to
     * [GEMINI_4_ARGON] for hard multi-step reasoning or [GEMINI_3_5_FLASH_LITE] to minimise spend;
     * nothing else in the engine changes.
     */
    val GEMINI_3_8_FLASH = HostedLlmModelSpec(
        displayName = "Gemini 3.8 Flash",
        modelId = "gemini-3.8-flash",
        runtimeMaxContextTokens = 1_000_000,
        toolCallingStyle = LlmToolCallingStyle.NATIVE_FUNCTION_CALLING,
        providerId = "gemini",
        apiEndpoint = GEMINI_INTERACTIONS_ENDPOINT,
        supportsVision = true,
        supportsAudio = false,
        licenseName = "Gemini API Additional Terms of Service",
        licenseUrl = "https://ai.google.dev/gemini-api/terms",
        dataHandlingNote = "Prompts, tool results and coordinates are sent to Google's Gemini API " +
            "for inference. Hydro sends no history beyond the current turn."
    )

    /** Cheapest/fastest hosted tier for high-volume, simple turns. */
    val GEMINI_3_5_FLASH_LITE = HostedLlmModelSpec(
        displayName = "Gemini 3.5 Flash-Lite",
        modelId = "gemini-3.5-flash-lite",
        runtimeMaxContextTokens = 1_000_000,
        toolCallingStyle = LlmToolCallingStyle.NATIVE_FUNCTION_CALLING,
        providerId = "gemini",
        apiEndpoint = GEMINI_INTERACTIONS_ENDPOINT,
        supportsVision = true,
        supportsAudio = false,
        licenseName = "Gemini API Additional Terms of Service",
        licenseUrl = "https://ai.google.dev/gemini-api/terms",
        dataHandlingNote = "Prompts, tool results and coordinates are sent to Google's Gemini API " +
            "for inference. Hydro sends no history beyond the current turn."
    )

    /** Flagship hosted tier for hard reasoning; slower and more expensive. */
    val GEMINI_4_ARGON = HostedLlmModelSpec(
        displayName = "Gemini 4 Argon",
        modelId = "gemini-4-argon",
        runtimeMaxContextTokens = 1_000_000,
        toolCallingStyle = LlmToolCallingStyle.NATIVE_FUNCTION_CALLING,
        providerId = "gemini",
        apiEndpoint = GEMINI_INTERACTIONS_ENDPOINT,
        supportsVision = true,
        supportsAudio = false,
        licenseName = "Gemini API Additional Terms of Service",
        licenseUrl = "https://ai.google.dev/gemini-api/terms",
        dataHandlingNote = "Prompts, tool results and coordinates are sent to Google's Gemini API " +
            "for inference. Hydro sends no history beyond the current turn."
    )

    /** Every hosted model Hydro knows how to drive. */
    val HOSTED: List<HostedLlmModelSpec> = listOf(
        GEMINI_3_8_FLASH,
        GEMINI_3_5_FLASH_LITE,
        GEMINI_4_ARGON
    )

    /** The model [com.ferousco.hydro.core.HydroEngine] wires by default. */
    val DEFAULT: HostedLlmModelSpec = GEMINI_3_8_FLASH

    /** Candidate: small instruction-following baseline for intent parsing and tool selection. */
    val QWEN2_5_1_5B_INSTRUCT = OnDeviceLlmModelSpec(
        displayName = "Qwen2.5-1.5B-Instruct",
        modelId = "Qwen2.5-1.5B-Instruct",
        expectedFileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        artifactFormat = LlmArtifactFormat.GGUF,
        minDeviceRamGb = 3,
        runtimeMaxContextTokens = 4096,
        quantizationSummary = "Q4_K_M (candidate; quantization not finalized)",
        supportsVision = false,
        supportsAudio = false,
        toolCallingStyle = LlmToolCallingStyle.HYBRID,
        supportedBackends = setOf(LlmExecutionBackend.CPU, LlmExecutionBackend.GPU),
        licenseName = "Apache-2.0 (verify against the exact release)",
        licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct",
        requiresUserLicenseAcceptance = false,
        metadataVerified = false
    )

    /** Candidate: compact family intended for on-device/edge use. */
    val LLAMA_3_2_1B_INSTRUCT = OnDeviceLlmModelSpec(
        displayName = "Llama 3.2 1B Instruct",
        modelId = "Llama-3.2-1B-Instruct",
        expectedFileName = "llama-3.2-1b-instruct-q4_k_m.gguf",
        artifactFormat = LlmArtifactFormat.GGUF,
        minDeviceRamGb = 3,
        runtimeMaxContextTokens = 4096,
        quantizationSummary = "Q4_K_M (candidate; quantization not finalized)",
        supportsVision = false,
        supportsAudio = false,
        toolCallingStyle = LlmToolCallingStyle.HYBRID,
        supportedBackends = setOf(LlmExecutionBackend.CPU, LlmExecutionBackend.GPU),
        licenseName = "Llama 3.2 Community License (verify against the exact release)",
        licenseUrl = "https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct",
        requiresUserLicenseAcceptance = true,
        metadataVerified = false
    )

    /**
     * Candidate: very small function-calling model. Not a general assistant on its own, so it may
     * need deterministic response templates for the explanation half of the turn.
     */
    val FUNCTIONGEMMA_270M = OnDeviceLlmModelSpec(
        displayName = "FunctionGemma 270M",
        modelId = "FunctionGemma-270M",
        expectedFileName = "functiongemma-270m.task",
        artifactFormat = LlmArtifactFormat.TASK,
        minDeviceRamGb = 2,
        runtimeMaxContextTokens = 2048,
        quantizationSummary = "Candidate; runtime format and quantization not verified",
        supportsVision = false,
        supportsAudio = false,
        toolCallingStyle = LlmToolCallingStyle.NATIVE_FUNCTION_CALLING,
        supportedBackends = setOf(LlmExecutionBackend.CPU),
        licenseName = "Verify against the exact release",
        licenseUrl = "https://ai.google.dev/edge/litert",
        requiresUserLicenseAcceptance = false,
        metadataVerified = false
    )

    /** The three local candidates from the product document, to be benchmarked against one suite. */
    val BENCHMARK_CANDIDATES: List<OnDeviceLlmModelSpec> = listOf(
        QWEN2_5_1_5B_INSTRUCT,
        LLAMA_3_2_1B_INSTRUCT,
        FUNCTIONGEMMA_270M
    )

    /** Every local spec known to the engine, verified or not. */
    val ON_DEVICE: List<OnDeviceLlmModelSpec> = BENCHMARK_CANDIDATES + listOf(
        GEMMA_3N_E2B_IT_LITERT_LM,
        GEMMA_3N_E4B_IT_LITERT_LM
    )

    /** Every spec known to the engine, local or hosted. */
    val ALL: List<LlmModelSpec> = ON_DEVICE + HOSTED
}

/**
 * Whether a model can actually serve a request right now.
 *
 * Local models fail on missing/truncated artifacts or insufficient RAM; hosted models fail on a
 * missing API key. Both are surfaced as [HydroAssistantOutcome.ModelUnavailable] so the
 * deterministic engine keeps working without the language layer.
 */
sealed interface LlmReadiness {
    val modelSpec: LlmModelSpec

    /**
     * The model can serve requests.
     *
     * `resolvedFilePath`/`actualFileSizeBytes` are `null` for hosted models, which have no local
     * artifact.
     */
    data class Ready(
        override val modelSpec: LlmModelSpec,
        val resolvedFilePath: String? = null,
        val actualFileSizeBytes: Long? = null,
        val matchesOfficialReleaseSize: Boolean = false
    ) : LlmReadiness

    data class NotInstalled(
        override val modelSpec: LlmModelSpec,
        val searchedPaths: List<String>,
        val provisioningHint: String
    ) : LlmReadiness

    data class InvalidArtifact(
        override val modelSpec: LlmModelSpec,
        val filePath: String,
        val reason: String
    ) : LlmReadiness

    data class InsufficientDeviceMemory(
        override val modelSpec: LlmModelSpec,
        val detectedRamGb: Double,
        val requiredRamGb: Int
    ) : LlmReadiness

    /** A hosted model is selected but the client has no usable API key. */
    data class CredentialsMissing(
        override val modelSpec: LlmModelSpec,
        val reason: String,
        val setupHint: String
    ) : LlmReadiness
}

/**
 * Format-agnostic local artifact validation.
 *
 * Validation is intentionally shallow: it confirms the file exists, is readable, has the expected
 * extension, is not truncated, and (optionally) matches the exact published size. It does not
 * deserialize the model.
 */
object LocalModelArtifactValidator {
    /**
     * Minimum plausible artifact size (1 MiB) to catch truncated zero-byte downloads while still
     * permitting custom re-quantized test artifacts when `strictOfficialSize` is false.
     */
    const val MIN_VALID_ARTIFACT_BYTES: Long = 1_048_576L

    fun validate(
        candidateFiles: List<File>,
        modelSpec: OnDeviceLlmModelSpec,
        detectedDeviceRamBytes: Long? = null,
        strictOfficialSize: Boolean = false,
        minimumBytes: Long = MIN_VALID_ARTIFACT_BYTES
    ): LlmReadiness {
        if (detectedDeviceRamBytes != null && detectedDeviceRamBytes > 0L) {
            val ramGb = detectedDeviceRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            // Allow 0.5 GB kernel carve-out tolerance (e.g. an "8 GB" phone reports ~7.55 GiB totalMem)
            if (ramGb + 0.55 < modelSpec.minDeviceRamGb.toDouble()) {
                return LlmReadiness.InsufficientDeviceMemory(
                    modelSpec = modelSpec,
                    detectedRamGb = ramGb,
                    requiredRamGb = modelSpec.minDeviceRamGb
                )
            }
        }

        val existing = candidateFiles.firstOrNull { it.exists() }
            ?: return LlmReadiness.NotInstalled(
                modelSpec = modelSpec,
                searchedPaths = candidateFiles.map { it.absolutePath },
                provisioningHint = "Provision '${modelSpec.expectedFileName}' (${modelSpec.displayName}) " +
                    "into the app's models directory. Source and license: ${modelSpec.licenseUrl}"
            )

        if (!existing.isFile || !existing.canRead()) {
            return LlmReadiness.InvalidArtifact(
                modelSpec = modelSpec,
                filePath = existing.absolutePath,
                reason = "Artifact exists but is not a readable regular file."
            )
        }

        val expectedExtension = modelSpec.artifactFormat.fileExtension
        if (!existing.name.endsWith(expectedExtension, ignoreCase = true)) {
            return LlmReadiness.InvalidArtifact(
                modelSpec = modelSpec,
                filePath = existing.absolutePath,
                reason = "Expected a '$expectedExtension' package for ${modelSpec.artifactFormat.name}, " +
                    "got '${existing.name}'."
            )
        }

        val length = existing.length()
        if (length < minimumBytes) {
            return LlmReadiness.InvalidArtifact(
                modelSpec = modelSpec,
                filePath = existing.absolutePath,
                reason = "Model file is incomplete or truncated ($length bytes < minimum $minimumBytes bytes)."
            )
        }

        val officialSize = modelSpec.expectedSizeBytes
        val matchesOfficial = officialSize != null && length == officialSize
        if (strictOfficialSize) {
            if (officialSize == null) {
                return LlmReadiness.InvalidArtifact(
                    modelSpec = modelSpec,
                    filePath = existing.absolutePath,
                    reason = "Exact artifact size for '${modelSpec.expectedFileName}' is unverified, " +
                        "so strict size validation cannot be applied."
                )
            }
            if (!matchesOfficial) {
                return LlmReadiness.InvalidArtifact(
                    modelSpec = modelSpec,
                    filePath = existing.absolutePath,
                    reason = "Model file size ($length bytes) does not match the published " +
                        "'${modelSpec.expectedFileName}' ($officialSize bytes)."
                )
            }
        }

        return LlmReadiness.Ready(
            modelSpec = modelSpec,
            resolvedFilePath = existing.absolutePath,
            actualFileSizeBytes = length,
            matchesOfficialReleaseSize = matchesOfficial
        )
    }
}
