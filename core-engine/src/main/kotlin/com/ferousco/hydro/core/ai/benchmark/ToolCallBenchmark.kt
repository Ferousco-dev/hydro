package com.ferousco.hydro.core.ai.benchmark

import com.ferousco.hydro.core.HydroEngine
import com.ferousco.hydro.core.ai.HydroAssistantOutcome
import com.ferousco.hydro.core.ai.HydroOrchestratorConfig
import com.ferousco.hydro.core.ai.LlmClient
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.GeoLocation
import java.util.Locale
import kotlin.math.ceil

/**
 * One benchmark scenario: a natural-language request plus the deterministic tool behaviour it must
 * produce.
 *
 * Pass criteria are deliberately strict and mechanical so two models can be compared without
 * subjective judgement:
 * 1. the orchestrator produced a final natural-language answer (not a failure/unavailable state);
 * 2. every tool in [requiredTools] was executed at least once;
 * 3. no tool in [forbiddenTools] was executed;
 * 4. no tool outside `requiredTools + allowedAdditionalTools` was executed.
 */
data class BenchmarkPrompt(
    val id: String,
    val prompt: String,
    val requiredTools: Set<String>,
    val allowedAdditionalTools: Set<String> = emptySet(),
    val forbiddenTools: Set<String> = emptySet(),
    val description: String = ""
) {
    init {
        require(id.isNotBlank()) { "Benchmark prompt id must not be blank." }
        require(prompt.isNotBlank()) { "Benchmark prompt text must not be blank." }
        require(requiredTools.intersect(forbiddenTools).isEmpty()) {
            "Prompt '$id' cannot require and forbid the same tool."
        }
    }
}

/**
 * A named, versioned set of [BenchmarkPrompt]s run against every candidate model.
 *
 * Versioning matters: accuracy numbers are only comparable across models when they were produced by
 * the same suite. Treat changes here as a new suite version.
 */
class ToolCallBenchmarkSuite(
    val name: String,
    val prompts: List<BenchmarkPrompt>
) {
    init {
        require(name.isNotBlank()) { "Suite name must not be blank." }
        require(prompts.isNotEmpty()) { "Suite must contain at least one prompt." }
        val duplicates = prompts.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate benchmark prompt ids: $duplicates" }
    }

    companion object {
        /** Tools that must never be called for a non-environmental question. */
        private val ALL_ENVIRONMENTAL_TOOLS = setOf(
            "get_weather",
            "get_hourly_forecast",
            "get_historical_weather",
            "calculate_heat_risk",
            "compare_forecast_periods",
            "query_memory"
        )

        /**
         * Core Hydro tool-suite benchmark (v1).
         *
         * Covers single-tool lookup, dependent multi-step reasoning, argument grounding (coordinates
         * and timezone must come from the user/system context), history retrieval, and the ability to
         * *not* call a tool when the question is out of scope.
         */
        val HYDRO_CORE_V1: ToolCallBenchmarkSuite = ToolCallBenchmarkSuite(
            name = "hydro-core-v1",
            prompts = listOf(
                BenchmarkPrompt(
                    id = "current-conditions",
                    prompt = "What are the current conditions where I am right now? " +
                        "Give temperature, humidity and wind speed.",
                    requiredTools = setOf("get_weather"),
                    description = "Single observation lookup."
                ),
                BenchmarkPrompt(
                    id = "heat-risk-now",
                    prompt = "How risky is the heat right now? Tell me the NWS heat index category " +
                        "and whether I should limit outdoor exertion.",
                    requiredTools = setOf("calculate_heat_risk"),
                    allowedAdditionalTools = setOf("get_weather"),
                    description = "Deterministic heat assessment, optionally preceded by a weather read."
                ),
                BenchmarkPrompt(
                    id = "current-plus-heat",
                    prompt = "Get the current conditions and tell me the exact heat index in Celsius.",
                    requiredTools = setOf("get_weather", "calculate_heat_risk"),
                    description = "Dependent two-step chain: observation then assessment."
                ),
                BenchmarkPrompt(
                    id = "coolest-window",
                    prompt = "I want to walk for two hours this afternoon. Which departure window has " +
                        "the lowest expected heat exposure?",
                    requiredTools = setOf("compare_forecast_periods"),
                    allowedAdditionalTools = setOf("get_hourly_forecast", "get_weather"),
                    description = "Forecast window comparison and ranking."
                ),
                BenchmarkPrompt(
                    id = "history-compare",
                    prompt = "How does today compare with yesterday at this location?",
                    requiredTools = setOf("query_memory"),
                    allowedAdditionalTools = setOf(
                        "get_weather",
                        "get_historical_weather",
                        "calculate_heat_risk"
                    ),
                    description = "Local memory retrieval and comparison."
                ),
                BenchmarkPrompt(
                    id = "historical-archive",
                    prompt = "What was the air temperature here yesterday afternoon?",
                    requiredTools = setOf("get_historical_weather"),
                    allowedAdditionalTools = setOf("query_memory", "calculate_heat_risk"),
                    description = "Historical reanalysis lookup."
                ),
                BenchmarkPrompt(
                    id = "out-of-scope",
                    prompt = "What is the capital of France?",
                    requiredTools = emptySet(),
                    forbiddenTools = ALL_ENVIRONMENTAL_TOOLS,
                    description = "Must answer without invoking any environmental tool."
                )
            )
        )
    }
}

enum class BenchmarkCaseOutcome {
    /** The model produced a final answer. */
    ANSWERED,

    /** The model artifact or device memory was unavailable. */
    MODEL_UNAVAILABLE,

    /** Inference or orchestration failed (tool-loop exhaustion, runtime error, empty output). */
    FAILED
}

data class BenchmarkCaseResult(
    val promptId: String,
    val outcome: BenchmarkCaseOutcome,
    val executedToolNames: List<String>,
    val missingRequiredTools: Set<String>,
    val unexpectedTools: Set<String>,
    val forbiddenToolsCalled: Set<String>,
    val malformedToolCalls: Int,
    val succeededToolCalls: Int,
    val failedToolCalls: Int,
    val toolErrorCodes: Map<String, Int>,
    val latencyMillis: Long,
    val answerCharCount: Int
) {
    val passed: Boolean
        get() = outcome == BenchmarkCaseOutcome.ANSWERED &&
            missingRequiredTools.isEmpty() &&
            unexpectedTools.isEmpty() &&
            forbiddenToolsCalled.isEmpty()
}

/**
 * Aggregated, comparable results for one model over one suite.
 */
data class ToolCallBenchmarkReport(
    val modelId: String,
    val modelDisplayName: String,
    val suiteName: String,
    val results: List<BenchmarkCaseResult>,
    val totalElapsedMillis: Long
) {
    val totalCases: Int get() = results.size

    val passedCases: Int get() = results.count { it.passed }

    /** Fraction of prompts whose required tools were selected with no unexpected calls. */
    val toolSelectionAccuracy: Double get() = ratio(passedCases, totalCases)

    val answeredCases: Int get() = results.count { it.outcome == BenchmarkCaseOutcome.ANSWERED }
    val modelUnavailableCases: Int get() = results.count { it.outcome == BenchmarkCaseOutcome.MODEL_UNAVAILABLE }
    val failedCases: Int get() = results.count { it.outcome == BenchmarkCaseOutcome.FAILED }

    val totalToolCalls: Int get() = results.sumOf { it.succeededToolCalls + it.failedToolCalls }
    val malformedToolCallCount: Int get() = results.sumOf { it.malformedToolCalls }

    /** Share of attempted tool calls whose arguments parsed and validated. */
    val schemaValidToolCallRate: Double
        get() = ratio(results.sumOf { it.succeededToolCalls }, totalToolCalls)

    val malformedToolCallRate: Double get() = ratio(malformedToolCallCount, totalToolCalls)

    val meanLatencyMillis: Long
        get() = if (results.isEmpty()) 0L else results.sumOf { it.latencyMillis } / results.size

    val p95LatencyMillis: Long
        get() {
            if (results.isEmpty()) return 0L
            val sorted = results.map { it.latencyMillis }.sorted()
            val index = ceil(sorted.size * 0.95).toInt().coerceIn(1, sorted.size) - 1
            return sorted[index]
        }

    val toolErrorCodeCounts: Map<String, Int>
        get() = results
            .flatMap { it.toolErrorCodes.entries }
            .groupingBy { it.key }
            .fold(0) { acc, entry -> acc + entry.value }
            .toList()
            .sortedByDescending { it.second }
            .toMap()

    /** Stable, human-readable comparison block suitable for a benchmark log. */
    fun summary(): String = buildString {
        appendLine("Hydro tool-call benchmark — $suiteName")
        appendLine("model: $modelDisplayName ($modelId)")
        appendLine(
            String.format(
                Locale.US,
                "tool-selection accuracy: %d/%d (%.1f%%)",
                passedCases,
                totalCases,
                toolSelectionAccuracy * 100.0
            )
        )
        appendLine(
            String.format(
                Locale.US,
                "answered: %d · model-unavailable: %d · failed: %d",
                answeredCases,
                modelUnavailableCases,
                failedCases
            )
        )
        appendLine(
            String.format(
                Locale.US,
                "tool calls: %d · schema-valid: %.1f%% · malformed: %d",
                totalToolCalls,
                schemaValidToolCallRate * 100.0,
                malformedToolCallCount
            )
        )
        appendLine(
            String.format(
                Locale.US,
                "latency: mean %d ms · p95 %d ms · wall clock %d ms",
                meanLatencyMillis,
                p95LatencyMillis,
                totalElapsedMillis
            )
        )
        if (toolErrorCodeCounts.isNotEmpty()) {
            appendLine("tool error codes: " + toolErrorCodeCounts.entries.joinToString { "${it.key}=${it.value}" })
        }
        append("cases: " + results.joinToString { "${it.promptId}=${if (it.passed) "pass" else "fail"}" })
    }

    /** Machine-readable form so results can be persisted and diffed across model versions. */
    fun toJson(): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf(
            "suite" to JsonValue.StringValue(suiteName),
            "model_id" to JsonValue.StringValue(modelId),
            "model_display_name" to JsonValue.StringValue(modelDisplayName),
            "total_cases" to SafeJsonCodec.number(totalCases),
            "passed_cases" to SafeJsonCodec.number(passedCases),
            "tool_selection_accuracy" to SafeJsonCodec.number(toolSelectionAccuracy),
            "answered_cases" to SafeJsonCodec.number(answeredCases),
            "model_unavailable_cases" to SafeJsonCodec.number(modelUnavailableCases),
            "failed_cases" to SafeJsonCodec.number(failedCases),
            "total_tool_calls" to SafeJsonCodec.number(totalToolCalls),
            "schema_valid_tool_call_rate" to SafeJsonCodec.number(schemaValidToolCallRate),
            "malformed_tool_calls" to SafeJsonCodec.number(malformedToolCallCount),
            "mean_latency_ms" to SafeJsonCodec.number(meanLatencyMillis),
            "p95_latency_ms" to SafeJsonCodec.number(p95LatencyMillis),
            "total_elapsed_ms" to SafeJsonCodec.number(totalElapsedMillis),
            "cases" to JsonValue.ArrayValue(
                results.map { result ->
                    JsonValue.ObjectValue(
                        linkedMapOf(
                            "prompt_id" to JsonValue.StringValue(result.promptId),
                            "outcome" to JsonValue.StringValue(result.outcome.name),
                            "passed" to JsonValue.BooleanValue(result.passed),
                            "executed_tools" to JsonValue.ArrayValue(
                                result.executedToolNames.map { JsonValue.StringValue(it) }
                            ),
                            "missing_required_tools" to JsonValue.ArrayValue(
                                result.missingRequiredTools.sorted().map { JsonValue.StringValue(it) }
                            ),
                            "unexpected_tools" to JsonValue.ArrayValue(
                                result.unexpectedTools.sorted().map { JsonValue.StringValue(it) }
                            ),
                            "forbidden_tools_called" to JsonValue.ArrayValue(
                                result.forbiddenToolsCalled.sorted().map { JsonValue.StringValue(it) }
                            ),
                            "malformed_tool_calls" to SafeJsonCodec.number(result.malformedToolCalls),
                            "succeeded_tool_calls" to SafeJsonCodec.number(result.succeededToolCalls),
                            "failed_tool_calls" to SafeJsonCodec.number(result.failedToolCalls),
                            "latency_ms" to SafeJsonCodec.number(result.latencyMillis),
                            "answer_chars" to SafeJsonCodec.number(result.answerCharCount)
                        )
                    )
                }
            )
        )
    )

    fun toJsonString(): String = SafeJsonCodec.stringify(toJson())

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator <= 0) 0.0 else numerator.toDouble() / denominator.toDouble()
}

/**
 * Runs a [ToolCallBenchmarkSuite] against any [LlmClient].
 *
 * This is the measurement layer that decides Hydro's model choice: the same prompts, the same tool
 * registry, the same orchestrator configuration, only the model (and its runtime adapter) differ.
 * It executes entirely offline against fake providers in tests and requires no model file.
 *
 * A fresh orchestrator is created per prompt so no conversation state leaks between cases, while the
 * supplied client is reused so model load time is not paid once per case.
 */
class ToolCallBenchmarkHarness(
    private val engine: HydroEngine,
    private val suite: ToolCallBenchmarkSuite = ToolCallBenchmarkSuite.HYDRO_CORE_V1,
    private val orchestratorConfig: HydroOrchestratorConfig = HydroOrchestratorConfig(),
    private val userLocation: GeoLocation? = null
) {
    /** Error code the orchestrator attaches to a tool call whose JSON could not be parsed. */
    private val malformedToolCallCode = "INVALID_TOOL_CALL_SYNTAX"

    suspend fun run(llmClient: LlmClient): ToolCallBenchmarkReport {
        val modelSpec = llmClient.modelSpec
        val wallStart = System.nanoTime()
        val results = suite.prompts.map { benchmarkPrompt ->
            runCase(llmClient, benchmarkPrompt)
        }
        val wallElapsed = (System.nanoTime() - wallStart) / 1_000_000L

        return ToolCallBenchmarkReport(
            modelId = modelSpec.modelId,
            modelDisplayName = modelSpec.displayName,
            suiteName = suite.name,
            results = results,
            totalElapsedMillis = wallElapsed
        )
    }

    private suspend fun runCase(
        llmClient: LlmClient,
        benchmarkPrompt: BenchmarkPrompt
    ): BenchmarkCaseResult {
        val started = System.nanoTime()
        val outcome = engine.createOrchestrator(llmClient, orchestratorConfig)
            .respond(userPrompt = benchmarkPrompt.prompt, userLocationContext = userLocation)
        val elapsed = (System.nanoTime() - started) / 1_000_000L

        val (caseOutcome, traces, answerChars) = when (outcome) {
            is HydroAssistantOutcome.Responded ->
                Triple(BenchmarkCaseOutcome.ANSWERED, outcome.grounding.executedTools, outcome.responseText.length)
            is HydroAssistantOutcome.ModelUnavailable ->
                Triple(BenchmarkCaseOutcome.MODEL_UNAVAILABLE, emptyList(), 0)
            is HydroAssistantOutcome.Failed ->
                Triple(BenchmarkCaseOutcome.FAILED, outcome.partialToolTraces, 0)
        }

        val calledTools = traces.map { it.toolName }.distinct()
        val malformed = traces.count { !it.succeeded && it.errorCode == malformedToolCallCode }
        val errorCodes = traces
            .mapNotNull { it.errorCode }
            .groupingBy { it }
            .eachCount()

        return BenchmarkCaseResult(
            promptId = benchmarkPrompt.id,
            outcome = caseOutcome,
            executedToolNames = traces.map { it.toolName },
            missingRequiredTools = benchmarkPrompt.requiredTools - calledTools.toSet(),
            unexpectedTools = calledTools.toSet() -
                benchmarkPrompt.requiredTools -
                benchmarkPrompt.allowedAdditionalTools,
            forbiddenToolsCalled = calledTools.toSet() intersect benchmarkPrompt.forbiddenTools,
            malformedToolCalls = malformed,
            succeededToolCalls = traces.count { it.succeeded },
            failedToolCalls = traces.count { !it.succeeded },
            toolErrorCodes = errorCodes,
            latencyMillis = elapsed,
            answerCharCount = answerChars
        )
    }
}
