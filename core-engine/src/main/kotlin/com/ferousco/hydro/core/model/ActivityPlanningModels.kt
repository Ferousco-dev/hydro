package com.ferousco.hydro.core.model

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

enum class ActivityCategory(val displayName: String) {
    WALKING("Walking"),
    RUNNING("Running"),
    CYCLING("Cycling"),
    OUTDOOR_WORK("Outdoor Work"),
    COMMUTING("Commuting"),
    GENERAL_OUTDOOR("General Outdoor Activity")
}

enum class ActivityIntensity(val displayName: String) {
    LIGHT("Light"),
    MODERATE("Moderate"),
    VIGOROUS("Vigorous")
}

data class ActivityPlanningRequest(
    val location: GeoLocation,
    val activityCategory: ActivityCategory,
    val plannedDuration: Duration,
    val intensity: ActivityIntensity? = null,
    val earliestDeparture: Instant,
    val latestDeparture: Instant,
    val candidateStep: Duration = Duration.ofHours(1),
    val forecast: WeatherForecast,
    val allowStaleForecast: Boolean = false
) {
    init {
        require(!plannedDuration.isZero && !plannedDuration.isNegative) {
            "plannedDuration must be strictly positive, got $plannedDuration."
        }
        require(plannedDuration <= Duration.ofHours(24)) {
            "plannedDuration cannot exceed 24 hours for hourly short-range activity comparison."
        }
        require(!latestDeparture.isBefore(earliestDeparture)) {
            "latestDeparture ($latestDeparture) cannot precede earliestDeparture ($earliestDeparture)."
        }
        require(!candidateStep.isZero && !candidateStep.isNegative && candidateStep >= Duration.ofMinutes(15)) {
            "candidateStep must be at least 15 minutes, got $candidateStep."
        }
    }
}

/**
 * Explicit non-medical comparison verdict that avoids declaring any outdoor activity "safe"
 * simply because one window has lower heat index than another.
 */
enum class ExposureComparisonVerdict {
    /** Lowest-exposure candidate(s) remain below the 80°F (26.7°C) NWS Heat Index caution threshold. */
    LOWER_RELATIVE_HEAT_BELOW_CAUTION,

    /** Lowest-exposure candidate(s) reduce heat relative to warmer slots, yet still fall in NWS Caution or Extreme Caution. */
    LOWER_RELATIVE_HEAT_STILL_ELEVATED,

    /** Every complete candidate window reaches NWS Danger or Extreme Danger thresholds. */
    HIGH_HEAT_ACROSS_ALL_CANDIDATES,

    /** All evaluated complete candidates have indistinguishable thermal conditions within tie tolerance. */
    UNIFORM_CONDITIONS_TIED
}

data class CandidateForecastConditionsSummary(
    val coveredHourCount: Int,
    val minAirTemperatureCelsius: Double,
    val maxAirTemperatureCelsius: Double,
    val meanRelativeHumidityPercent: Double,
    val maxWindSpeedMetersPerSecond: Double?,
    val maxUvIndex: Double?,
    val maxSolarRadiationWm2: Double?,
    val missingOptionalVariables: Set<String>
)

sealed interface CandidateWindowEvaluation {
    val departureTime: Instant
    val endTime: Instant
    val localDepartureTime: ZonedDateTime
    val localEndTime: ZonedDateTime

    data class Complete(
        override val departureTime: Instant,
        override val endTime: Instant,
        override val localDepartureTime: ZonedDateTime,
        override val localEndTime: ZonedDateTime,
        val conditions: CandidateForecastConditionsSummary,
        val hourlyAssessments: List<HeatAssessment.Supported>,
        val worstCategory: NwsHeatIndexCategory,
        val peakHeatIndexCelsius: Double,
        val peakHeatIndexFahrenheit: Double,
        val meanHeatIndexCelsius: Double,
        val rank: Int,
        val isTiedForLowestExposure: Boolean
    ) : CandidateWindowEvaluation

    data class IncompleteCoverage(
        override val departureTime: Instant,
        override val endTime: Instant,
        override val localDepartureTime: ZonedDateTime,
        override val localEndTime: ZonedDateTime,
        val expectedHourlyTimestamps: List<Instant>,
        val missingHourlyTimestamps: List<Instant>,
        val unsupportedOrIncompleteHours: List<Instant>,
        val reason: String
    ) : CandidateWindowEvaluation
}

data class RelativeCandidateComparison(
    val candidateDeparture: Instant,
    val referenceLowestExposureDeparture: Instant,
    val peakHeatIndexDeltaCelsius: Double,
    val meanHeatIndexDeltaCelsius: Double,
    val maxAirTemperatureDeltaCelsius: Double,
    val maxUvIndexDelta: Double?,
    val candidateWorstCategory: NwsHeatIndexCategory,
    val referenceWorstCategory: NwsHeatIndexCategory,
    val summary: String
)

sealed interface ActivityPlanningOutcome {
    val request: ActivityPlanningRequest
    val evaluatedAt: Instant
    val forecastFreshness: DataQualityAndFreshness
    val limitations: List<String>

    data class Evaluated(
        override val request: ActivityPlanningRequest,
        override val evaluatedAt: Instant,
        val candidates: List<CandidateWindowEvaluation>,
        val completeCandidatesRanked: List<CandidateWindowEvaluation.Complete>,
        val lowestExposureDepartures: List<Instant>,
        val relativeComparisons: List<RelativeCandidateComparison>,
        val verdict: ExposureComparisonVerdict,
        val supportingEvidence: List<String>,
        override val forecastFreshness: DataQualityAndFreshness,
        val preparationAdvice: List<String>,
        override val limitations: List<String>
    ) : ActivityPlanningOutcome

    data class InsufficientData(
        override val request: ActivityPlanningRequest,
        override val evaluatedAt: Instant,
        val reason: String,
        val candidateDiagnostics: List<CandidateWindowEvaluation.IncompleteCoverage>,
        override val forecastFreshness: DataQualityAndFreshness,
        override val limitations: List<String>
    ) : ActivityPlanningOutcome
}
