package com.ferousco.hydro.core.model

import java.time.Duration
import java.time.Instant

/**
 * How the environmental data reached the caller.
 * Cached or stored records must never be presented as live network responses.
 */
enum class DeliveryOrigin {
    LIVE_NETWORK,
    LOCAL_CACHE,
    LOCAL_HISTORY_DB
}

enum class FreshnessState {
    /** Recently retrieved and within primary operational freshness thresholds. */
    FRESH,

    /** Usable with caution; older than fresh threshold but within maximum acceptable age. */
    AGING,

    /** Exceeds maximum operational age or exhibits excessive lag behind evaluation time. */
    STALE,

    /** Historical archived data evaluated for completeness rather than real-time currency. */
    HISTORICAL_RECORD
}

enum class QualityFlag {
    SERVED_FROM_CACHE,
    STALE_INPUT_DATA,
    MODEL_DERIVED_CURRENT_CONDITION,
    REANALYSIS_INTERPOLATED,
    GRID_CELL_OFFSET_SIGNIFICANT,
    PARTIAL_VARIABLES_MISSING,
    INVALID_PROVIDER_VALUES_REJECTED
}

data class FreshnessPolicy(
    val currentFreshThreshold: Duration = Duration.ofMinutes(30),
    val currentMaxAcceptableAge: Duration = Duration.ofMinutes(60),
    val currentMaxDataLag: Duration = Duration.ofMinutes(90),
    val forecastFreshThreshold: Duration = Duration.ofHours(3),
    val forecastMaxAcceptableAge: Duration = Duration.ofHours(6),
    val significantGridOffsetKm: Double = 10.0
)

/**
 * Tracks retrieval provenance, age, delivery origin, completeness, and quality caveats.
 */
data class DataQualityAndFreshness(
    val retrievedAt: Instant,
    val evaluatedAt: Instant,
    val referenceDataTime: Instant,
    val age: Duration,
    val dataLag: Duration?,
    val deliveryOrigin: DeliveryOrigin,
    val freshnessState: FreshnessState,
    val completenessRatio: Double,
    val flags: Set<QualityFlag>,
    val notes: List<String> = emptyList()
) {
    init {
        require(completenessRatio.isFinite() && completenessRatio in 0.0..1.0) {
            "completenessRatio must be within [0.0, 1.0], got $completenessRatio."
        }
    }

    /**
     * Strictly true only when served from live network AND currently fresh.
     * Cached responses are NEVER reported as current live information.
     */
    val isCurrentAndLive: Boolean
        get() = deliveryOrigin == DeliveryOrigin.LIVE_NETWORK && freshnessState == FreshnessState.FRESH

    /**
     * Re-evaluates freshness at [now] without mutating [retrievedAt].
     * Used when reading cached or persisted data so stale entries are never misrepresented as fresh.
     */
    fun reEvaluatedAt(
        now: Instant,
        newOrigin: DeliveryOrigin = deliveryOrigin,
        isForecast: Boolean,
        isHistoricalArchive: Boolean = freshnessState == FreshnessState.HISTORICAL_RECORD,
        policy: FreshnessPolicy = FreshnessPolicy(),
        extraNotes: List<String> = emptyList()
    ): DataQualityAndFreshness {
        val rawAge = Duration.between(retrievedAt, now)
        val clampedAge = if (rawAge.isNegative) Duration.ZERO else rawAge
        val computedLag = if (!isForecast && !isHistoricalArchive) {
            val lag = Duration.between(referenceDataTime, now)
            if (lag.isNegative) Duration.ZERO else lag
        } else {
            dataLag
        }

        val updatedState = when {
            isHistoricalArchive -> FreshnessState.HISTORICAL_RECORD
            isForecast -> when {
                clampedAge <= policy.forecastFreshThreshold -> FreshnessState.FRESH
                clampedAge <= policy.forecastMaxAcceptableAge -> FreshnessState.AGING
                else -> FreshnessState.STALE
            }
            else -> when {
                computedLag != null && computedLag > policy.currentMaxDataLag -> FreshnessState.STALE
                clampedAge <= policy.currentFreshThreshold -> FreshnessState.FRESH
                clampedAge <= policy.currentMaxAcceptableAge -> FreshnessState.AGING
                else -> FreshnessState.STALE
            }
        }

        val updatedFlags = flags.toMutableSet()
        if (newOrigin == DeliveryOrigin.LOCAL_CACHE) {
            updatedFlags.add(QualityFlag.SERVED_FROM_CACHE)
        }
        if (updatedState == FreshnessState.STALE) {
            updatedFlags.add(QualityFlag.STALE_INPUT_DATA)
        }

        val mergedNotes = buildList {
            addAll(notes)
            if (newOrigin == DeliveryOrigin.LOCAL_CACHE && notes.none { it.contains("cache", ignoreCase = true) }) {
                add("Served from local cache originally retrieved at $retrievedAt; not a live network reading.")
            }
            addAll(extraNotes)
        }

        return copy(
            evaluatedAt = now,
            age = clampedAge,
            dataLag = computedLag,
            deliveryOrigin = newOrigin,
            freshnessState = updatedState,
            flags = updatedFlags,
            notes = mergedNotes
        )
    }

    companion object {
        fun evaluateCurrentObservation(
            retrievedAt: Instant,
            evaluatedAt: Instant,
            observedAt: Instant,
            deliveryOrigin: DeliveryOrigin,
            completenessRatio: Double,
            baseFlags: Set<QualityFlag> = emptySet(),
            notes: List<String> = emptyList(),
            policy: FreshnessPolicy = FreshnessPolicy()
        ): DataQualityAndFreshness {
            return DataQualityAndFreshness(
                retrievedAt = retrievedAt,
                evaluatedAt = evaluatedAt,
                referenceDataTime = observedAt,
                age = Duration.ZERO,
                dataLag = null,
                deliveryOrigin = deliveryOrigin,
                freshnessState = FreshnessState.FRESH,
                completenessRatio = completenessRatio,
                flags = baseFlags,
                notes = notes
            ).reEvaluatedAt(
                now = evaluatedAt,
                newOrigin = deliveryOrigin,
                isForecast = false,
                isHistoricalArchive = false,
                policy = policy
            )
        }

        fun evaluateForecast(
            retrievedAt: Instant,
            evaluatedAt: Instant,
            anchorTime: Instant,
            deliveryOrigin: DeliveryOrigin,
            completenessRatio: Double,
            baseFlags: Set<QualityFlag> = emptySet(),
            notes: List<String> = emptyList(),
            policy: FreshnessPolicy = FreshnessPolicy()
        ): DataQualityAndFreshness {
            return DataQualityAndFreshness(
                retrievedAt = retrievedAt,
                evaluatedAt = evaluatedAt,
                referenceDataTime = anchorTime,
                age = Duration.ZERO,
                dataLag = null,
                deliveryOrigin = deliveryOrigin,
                freshnessState = FreshnessState.FRESH,
                completenessRatio = completenessRatio,
                flags = baseFlags,
                notes = notes
            ).reEvaluatedAt(
                now = evaluatedAt,
                newOrigin = deliveryOrigin,
                isForecast = true,
                isHistoricalArchive = false,
                policy = policy
            )
        }

        fun evaluateHistoricalArchive(
            retrievedAt: Instant,
            evaluatedAt: Instant,
            referenceDataTime: Instant,
            deliveryOrigin: DeliveryOrigin,
            completenessRatio: Double,
            baseFlags: Set<QualityFlag> = setOf(QualityFlag.REANALYSIS_INTERPOLATED),
            notes: List<String> = emptyList()
        ): DataQualityAndFreshness {
            val age = Duration.between(retrievedAt, evaluatedAt).let { if (it.isNegative) Duration.ZERO else it }
            val flags = baseFlags.toMutableSet().apply {
                if (deliveryOrigin == DeliveryOrigin.LOCAL_CACHE) add(QualityFlag.SERVED_FROM_CACHE)
            }
            return DataQualityAndFreshness(
                retrievedAt = retrievedAt,
                evaluatedAt = evaluatedAt,
                referenceDataTime = referenceDataTime,
                age = age,
                dataLag = null,
                deliveryOrigin = deliveryOrigin,
                freshnessState = FreshnessState.HISTORICAL_RECORD,
                completenessRatio = completenessRatio,
                flags = flags,
                notes = notes
            )
        }
    }
}
