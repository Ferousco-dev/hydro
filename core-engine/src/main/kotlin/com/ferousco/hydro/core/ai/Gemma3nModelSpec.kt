package com.ferousco.hydro.core.ai

import java.io.File

/**
 * Supported hardware delegates for LiteRT-LM on-device execution.
 */
enum class LlmHardwareBackend {
    CPU,
    GPU,
    NPU
}

/**
 * Active modality capabilities negotiated during cascading LiteRT-LM initialization.
 */
enum class LlmModalityMode {
    MULTIMODAL_TEXT_VISION_AUDIO,
    TEXT_ONLY
}

/**
 * Sampling configuration presets tuned for Gemma 3n on-device inference.
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
         * so Gemma 3n stays faithful to deterministic tool outputs without inventing numbers.
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
 * Verified model specification for compact open-weight Gemma 3n models packaged for LiteRT-LM.
 *
 * Model weights (`~3.66 GB` for E2B int4) are gated under the Gemma Terms of Use and must NEVER
 * be committed to Git or bundled inside the APK; they are provisioned into app-private external
 * or internal files storage on-device.
 */
data class Gemma3nModelSpec(
    val displayName: String,
    val huggingFaceRepoId: String,
    val expectedFileName: String,
    val pinnedCommitHash: String,
    val expectedSizeBytes: Long,
    val effectiveParametersBillions: Double,
    val minDeviceRamGb: Int,
    val liteRtMaxContextTokens: Int,
    val architectureMaxContextTokens: Int,
    val quantizationSummary: String,
    val supportsVision: Boolean,
    val supportsAudio: Boolean,
    val supportedBackends: Set<LlmHardwareBackend>,
    val licenseName: String,
    val licenseUrl: String,
    val requiresUserLicenseAcceptance: Boolean
) {
    init {
        require(expectedFileName.endsWith(".litertlm")) {
            "LiteRT-LM model artifact must use the .litertlm extension."
        }
        require(expectedSizeBytes > 0) { "expectedSizeBytes must be positive." }
        require(minDeviceRamGb > 0) { "minDeviceRamGb must be positive." }
        require(liteRtMaxContextTokens > 0) { "liteRtMaxContextTokens must be positive." }
    }

    companion object {
        /**
         * Primary open-weight model selected for Hydro:
         * `google/gemma-3n-E2B-it-litert-lm` (`gemma-3n-E2B-it-int4.litertlm`).
         *
         * Metadata verified against `google/gemma-3n-E2B-it-litert-lm` and Google AI Edge Gallery
         * (`model_allowlists/1_0_20.json`).
         */
        val GEMMA_3N_E2B_IT_LITERT_LM = Gemma3nModelSpec(
            displayName = "Gemma 3n E2B-IT (LiteRT-LM int4)",
            huggingFaceRepoId = "google/gemma-3n-E2B-it-litert-lm",
            expectedFileName = "gemma-3n-E2B-it-int4.litertlm",
            pinnedCommitHash = "ba9ca88da013b537b6ed38108be609b8db1c3a16",
            expectedSizeBytes = 3_655_827_456L,
            effectiveParametersBillions = 2.0,
            minDeviceRamGb = 8,
            liteRtMaxContextTokens = 4096,
            architectureMaxContextTokens = 32_768,
            quantizationSummary = "int4 weights, float activations",
            supportsVision = true,
            supportsAudio = true,
            supportedBackends = setOf(
                LlmHardwareBackend.CPU,
                LlmHardwareBackend.GPU,
                LlmHardwareBackend.NPU
            ),
            licenseName = "Gemma Terms of Use",
            licenseUrl = "https://ai.google.dev/gemma/terms",
            requiresUserLicenseAcceptance = true
        )

        /**
         * Higher-capacity 4B effective-parameter sibling for 12 GB+ flagship devices.
         */
        val GEMMA_3N_E4B_IT_LITERT_LM = Gemma3nModelSpec(
            displayName = "Gemma 3n E4B-IT (LiteRT-LM int4)",
            huggingFaceRepoId = "google/gemma-3n-E4B-it-litert-lm",
            expectedFileName = "gemma-3n-E4B-it-int4.litertlm",
            pinnedCommitHash = "297ed75955702dec3503e00c2c2ecbbf475300bc",
            expectedSizeBytes = 4_919_541_760L,
            effectiveParametersBillions = 4.0,
            minDeviceRamGb = 12,
            liteRtMaxContextTokens = 4096,
            architectureMaxContextTokens = 32_768,
            quantizationSummary = "int4 weights, float activations",
            supportsVision = true,
            supportsAudio = true,
            supportedBackends = setOf(
                LlmHardwareBackend.CPU,
                LlmHardwareBackend.GPU,
                LlmHardwareBackend.NPU
            ),
            licenseName = "Gemma Terms of Use",
            licenseUrl = "https://ai.google.dev/gemma/terms",
            requiresUserLicenseAcceptance = true
        )
    }
}

sealed interface ModelArtifactReadiness {
    data class Ready(
        val modelSpec: Gemma3nModelSpec,
        val resolvedFilePath: String,
        val actualFileSizeBytes: Long,
        val matchesOfficialReleaseSize: Boolean
    ) : ModelArtifactReadiness

    data class NotInstalled(
        val modelSpec: Gemma3nModelSpec,
        val searchedPaths: List<String>,
        val provisioningHint: String
    ) : ModelArtifactReadiness

    data class InvalidArtifact(
        val modelSpec: Gemma3nModelSpec,
        val filePath: String,
        val reason: String
    ) : ModelArtifactReadiness

    data class InsufficientDeviceMemory(
        val modelSpec: Gemma3nModelSpec,
        val detectedRamGb: Double,
        val requiredRamGb: Int
    ) : ModelArtifactReadiness
}

object LocalModelArtifactValidator {
    /**
     * Minimum plausible `.litertlm` package size (1 MiB) to catch truncated zero-byte downloads
     * while still permitting custom re-quantized `.litertlm` test artifacts when `strictOfficialSize` is false.
     */
    const val MIN_VALID_LITERTLM_BYTES: Long = 1_048_576L

    fun validate(
        candidateFiles: List<File>,
        modelSpec: Gemma3nModelSpec = Gemma3nModelSpec.GEMMA_3N_E2B_IT_LITERT_LM,
        detectedDeviceRamBytes: Long? = null,
        strictOfficialSize: Boolean = false,
        minimumBytes: Long = MIN_VALID_LITERTLM_BYTES
    ): ModelArtifactReadiness {
        if (detectedDeviceRamBytes != null && detectedDeviceRamBytes > 0L) {
            val ramGb = detectedDeviceRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            // Allow 0.5 GB kernel carve-out tolerance (e.g., an "8 GB" phone reports ~7.55 GiB totalMem)
            if (ramGb + 0.55 < modelSpec.minDeviceRamGb.toDouble()) {
                return ModelArtifactReadiness.InsufficientDeviceMemory(
                    modelSpec = modelSpec,
                    detectedRamGb = ramGb,
                    requiredRamGb = modelSpec.minDeviceRamGb
                )
            }
        }

        val existing = candidateFiles.firstOrNull { it.exists() }
            ?: return ModelArtifactReadiness.NotInstalled(
                modelSpec = modelSpec,
                searchedPaths = candidateFiles.map { it.absolutePath },
                provisioningHint = "Accept the Gemma Terms of Use on Hugging Face (${modelSpec.huggingFaceRepoId}) " +
                    "and download '${modelSpec.expectedFileName}' into the app's models directory."
            )

        if (!existing.isFile || !existing.canRead()) {
            return ModelArtifactReadiness.InvalidArtifact(
                modelSpec = modelSpec,
                filePath = existing.absolutePath,
                reason = "Artifact exists but is not a readable regular file."
            )
        }

        if (!existing.name.endsWith(".litertlm", ignoreCase = true)) {
            return ModelArtifactReadiness.InvalidArtifact(
                modelSpec = modelSpec,
                filePath = existing.absolutePath,
                reason = "Expected a '.litertlm' package for LiteRT-LM, got '${existing.name}'."
            )
        }

        val length = existing.length()
        if (length < minimumBytes) {
            return ModelArtifactReadiness.InvalidArtifact(
                modelSpec = modelSpec,
                filePath = existing.absolutePath,
                reason = "Model file is incomplete or truncated ($length bytes < minimum $minimumBytes bytes)."
            )
        }

        val matchesOfficial = length == modelSpec.expectedSizeBytes
        if (strictOfficialSize && !matchesOfficial) {
            return ModelArtifactReadiness.InvalidArtifact(
                modelSpec = modelSpec,
                filePath = existing.absolutePath,
                reason = "Model file size ($length bytes) does not match official '${modelSpec.expectedFileName}' (${modelSpec.expectedSizeBytes} bytes)."
            )
        }

        return ModelArtifactReadiness.Ready(
            modelSpec = modelSpec,
            resolvedFilePath = existing.absolutePath,
            actualFileSizeBytes = length,
            matchesOfficialReleaseSize = matchesOfficial
        )
    }
}
