package com.ferousco.hydro.core.quality

import com.ferousco.hydro.core.model.FreshnessState
import com.ferousco.hydro.core.model.ValueNature

/**
 * How much Hydro trusts an input, independent of how alarming it is.
 *
 * Confidence describes the *data*, never the danger. A stale reading of an extreme heat index is
 * still low-confidence data: it may be true, but Hydro cannot claim it is current. Keeping this in
 * one place stops the proactive, recommendation, and notification paths from drifting apart.
 */
object DataConfidence {

    /**
     * Base confidence for a value's provenance, reduced by staleness.
     *
     * `OBSERVED` and `FORECAST` are treated differently because a forecast is a statement about the
     * future, not a measurement. Archived values are complete but not current, so they land below
     * the notification policy's 0.5 floor and are suppressed there rather than by a special case.
     */
    fun forInput(nature: ValueNature, freshness: FreshnessState): Double {
        val base = when (nature) {
            ValueNature.OBSERVED -> 0.95
            ValueNature.FORECAST -> 0.85
            ValueNature.HISTORICAL_REANALYSIS,
            ValueNature.DERIVED -> 0.75
        }
        val adjusted = when (freshness) {
            FreshnessState.FRESH -> base
            FreshnessState.AGING -> base - 0.2
            FreshnessState.STALE -> 0.4
            FreshnessState.HISTORICAL_RECORD -> 0.4
        }
        return adjusted.coerceIn(0.0, 1.0)
    }

    /** True when the value is too old to support a current-conditions claim. */
    fun isCurrent(freshness: FreshnessState): Boolean =
        freshness == FreshnessState.FRESH || freshness == FreshnessState.AGING
}
