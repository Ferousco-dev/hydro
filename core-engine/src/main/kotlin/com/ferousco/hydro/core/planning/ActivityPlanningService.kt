package com.ferousco.hydro.core.planning

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.NoOpDiagnosticLogger
import com.ferousco.hydro.core.model.ActivityIntensity
import com.ferousco.hydro.core.model.ActivityPlanningOutcome
import com.ferousco.hydro.core.model.ActivityPlanningRequest
import com.ferousco.hydro.core.model.CalculationMethodSpec
import com.ferousco.hydro.core.model.CandidateForecastConditionsSummary
import com.ferousco.hydro.core.model.CandidateWindowEvaluation
import com.ferousco.hydro.core.model.ExposureComparisonVerdict
import com.ferousco.hydro.core.model.FreshnessPolicy
import com.ferousco.hydro.core.model.FreshnessState
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.model.QualityFlag
import com.ferousco.hydro.core.model.RelativeCandidateComparison
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.inUnitOrNull
import com.ferousco.hydro.core.model.rawValueOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

data class ActivityPlanningConfig(
    val maxForecastLocationMismatchKm: Double = 25.0,
    val maxCandidateCount: Int = 96,
    val peakHeatIndexTieToleranceCelsius: Double = 0.25,
    val meanHeatIndexTieToleranceCelsius: Double = 0.25,
    val uvIndexTieTolerance: Double = 0.5
) {
    init {
        require(maxForecastLocationMismatchKm > 0.0 && maxForecastLocationMismatchKm.isFinite()) {
            "maxForecastLocationMismatchKm must be positive."
        }
        require(maxCandidateCount in 1..200) {
            "maxCandidateCount must be within [1, 200]."
        }
    }
}

/**
 * Evaluates candidate outdoor activity departure windows against hourly forecast data
 * using the same deterministic [HeatAssessmentEngine] methods.
 *
 * Safety & domain invariants:
 * - Does NOT invent route shade, terrain, personal physiology, or exact safe exposure durations.
 * - Does NOT declare an activity "safe" simply because one forecast window is cooler than another.
 * - Preserves DST-accurate UTC [Instant] timelines alongside local [java.time.ZonedDateTime] representations.
 * - Flags missing forecast hours or incomplete duration coverage explicitly instead of extrapolating.
 */
class ActivityPlanningService(
    private val assessmentEngine: HeatAssessmentEngine = HeatAssessmentEngine(),
    private val freshnessPolicy: FreshnessPolicy = FreshnessPolicy(),
    private val config: ActivityPlanningConfig = ActivityPlanningConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) {

    fun evaluate(request: ActivityPlanningRequest): ActivityPlanningOutcome {
        val evaluatedAt = clock.instant()
        val forecast = request.forecast
        val updatedFreshness = forecast.quality.reEvaluatedAt(
            now = evaluatedAt,
            newOrigin = forecast.quality.deliveryOrigin,
            isForecast = true,
            policy = freshnessPolicy
        )

        val baseLimitations = buildPlanningLimitations(request, updatedFreshness.flags, updatedFreshness.freshnessState)

        val distanceToForecastKm = minOf(
            request.location.distanceKilometersTo(forecast.effectiveLocation),
            request.location.distanceKilometersTo(forecast.requestedLocation)
        )
        if (distanceToForecastKm > config.maxForecastLocationMismatchKm) {
            return ActivityPlanningOutcome.InsufficientData(
                request = request,
                evaluatedAt = evaluatedAt,
                reason = String.format(
                    Locale.US,
                    "Forecast coordinates differ from requested activity location by %.1f km (maximum allowed %.1f km).",
                    distanceToForecastKm,
                    config.maxForecastLocationMismatchKm
                ),
                candidateDiagnostics = emptyList(),
                forecastFreshness = updatedFreshness,
                limitations = baseLimitations
            )
        }

        if (updatedFreshness.freshnessState == FreshnessState.STALE && !request.allowStaleForecast) {
            return ActivityPlanningOutcome.InsufficientData(
                request = request,
                evaluatedAt = evaluatedAt,
                reason = "Hourly forecast data is stale (retrieved at ${updatedFreshness.retrievedAt}, age ${updatedFreshness.age.toMinutes()} minutes) and stale planning is disabled.",
                candidateDiagnostics = emptyList(),
                forecastFreshness = updatedFreshness,
                limitations = baseLimitations
            )
        }

        val departureTimes = generateCandidateDepartures(request)
        if (departureTimes == null) {
            return ActivityPlanningOutcome.InsufficientData(
                request = request,
                evaluatedAt = evaluatedAt,
                reason = "Candidate departure window generates more than ${config.maxCandidateCount} departure slots; narrow the window or increase candidateStep.",
                candidateDiagnostics = emptyList(),
                forecastFreshness = updatedFreshness,
                limitations = baseLimitations
            )
        }

        val forecastByInstant = forecast.hourly.associateBy { it.validAt }
        val unrankedComplete = ArrayList<UnrankedCompleteCandidate>()
        val incompleteEvaluations = ArrayList<CandidateWindowEvaluation.IncompleteCoverage>()

        for (departure in departureTimes) {
            val endTime = departure.plus(request.plannedDuration)
            val localDeparture = departure.atZone(forecast.timeZone)
            val localEnd = endTime.atZone(forecast.timeZone)
            val requiredHours = requiredHourlySlots(departure, endTime)

            val missingHours = ArrayList<Instant>()
            val unsupportedHours = ArrayList<Instant>()
            val supportedAssessments = ArrayList<HeatAssessment.Supported>()

            for (hourInstant in requiredHours) {
                val point = forecastByInstant[hourInstant]
                if (point == null) {
                    missingHours.add(hourInstant)
                    continue
                }
                val assessment = assessmentEngine.assessForecastPoint(
                    point = point,
                    forecastQuality = updatedFreshness,
                    allowStaleInput = request.allowStaleForecast
                )
                when (assessment) {
                    is HeatAssessment.Supported -> supportedAssessments.add(assessment)
                    is HeatAssessment.Incomplete,
                    is HeatAssessment.Unsupported -> unsupportedHours.add(hourInstant)
                }
            }

            if (missingHours.isNotEmpty() || unsupportedHours.isNotEmpty()) {
                val reasonParts = buildList {
                    if (missingHours.isNotEmpty()) {
                        add("missing ${missingHours.size} of ${requiredHours.size} required forecast hour(s)")
                    }
                    if (unsupportedHours.isNotEmpty()) {
                        add("${unsupportedHours.size} hour(s) lacked required valid temperature/humidity inputs")
                    }
                }
                incompleteEvaluations.add(
                    CandidateWindowEvaluation.IncompleteCoverage(
                        departureTime = departure,
                        endTime = endTime,
                        localDepartureTime = localDeparture,
                        localEndTime = localEnd,
                        expectedHourlyTimestamps = requiredHours,
                        missingHourlyTimestamps = missingHours,
                        unsupportedOrIncompleteHours = unsupportedHours,
                        reason = "Window [$localDeparture .. $localEnd] cannot be evaluated completely: ${reasonParts.joinToString("; ")}."
                    )
                )
            } else {
                val conditions = summarizeConditions(supportedAssessments)
                val worstCategory = supportedAssessments.maxBy { it.category.severityRank }.category
                val peakHiC = supportedAssessments.maxOf { it.heatIndexCelsius.value }
                val peakHiF = supportedAssessments.maxOf { it.heatIndexFahrenheit }
                val meanHiC = supportedAssessments.map { it.heatIndexCelsius.value }.average()

                unrankedComplete.add(
                    UnrankedCompleteCandidate(
                        departureTime = departure,
                        endTime = endTime,
                        localDepartureTime = localDeparture,
                        localEndTime = localEnd,
                        conditions = conditions,
                        hourlyAssessments = supportedAssessments,
                        worstCategory = worstCategory,
                        peakHeatIndexCelsius = peakHiC,
                        peakHeatIndexFahrenheit = peakHiF,
                        meanHeatIndexCelsius = meanHiC
                    )
                )
            }
        }

        if (unrankedComplete.isEmpty()) {
            logger.info(
                COMPONENT,
                "Insufficient forecast coverage for activity planning",
                mapOf(
                    "region" to request.location.redactedSummary(),
                    "candidateCount" to departureTimes.size.toString(),
                    "incompleteCount" to incompleteEvaluations.size.toString()
                )
            )
            return ActivityPlanningOutcome.InsufficientData(
                request = request,
                evaluatedAt = evaluatedAt,
                reason = "None of the ${departureTimes.size} candidate departure window(s) had complete, valid hourly forecast coverage across the full ${request.plannedDuration.toMinutes()}-minute activity duration.",
                candidateDiagnostics = incompleteEvaluations,
                forecastFreshness = updatedFreshness,
                limitations = baseLimitations
            )
        }

        val rankedComplete = rankCandidates(unrankedComplete)
        val lowestExposureGroup = rankedComplete.filter { it.rank == 1 }
        val lowestDepartures = lowestExposureGroup.map { it.departureTime }
        val referenceBest = rankedComplete.first()

        val allCandidatesChronological: List<CandidateWindowEvaluation> =
            (rankedComplete + incompleteEvaluations).sortedBy { it.departureTime }

        val comparisons = rankedComplete.map { candidate ->
            val peakDeltaC = candidate.peakHeatIndexCelsius - referenceBest.peakHeatIndexCelsius
            val meanDeltaC = candidate.meanHeatIndexCelsius - referenceBest.meanHeatIndexCelsius
            val tempDeltaC = candidate.conditions.maxAirTemperatureCelsius - referenceBest.conditions.maxAirTemperatureCelsius
            val uvDelta = if (candidate.conditions.maxUvIndex != null && referenceBest.conditions.maxUvIndex != null) {
                candidate.conditions.maxUvIndex - referenceBest.conditions.maxUvIndex
            } else {
                null
            }
            val summary = buildRelativeSummary(candidate, referenceBest, peakDeltaC, meanDeltaC)
            RelativeCandidateComparison(
                candidateDeparture = candidate.departureTime,
                referenceLowestExposureDeparture = referenceBest.departureTime,
                peakHeatIndexDeltaCelsius = peakDeltaC,
                meanHeatIndexDeltaCelsius = meanDeltaC,
                maxAirTemperatureDeltaCelsius = tempDeltaC,
                maxUvIndexDelta = uvDelta,
                candidateWorstCategory = candidate.worstCategory,
                referenceWorstCategory = referenceBest.worstCategory,
                summary = summary
            )
        }

        val verdict = determineVerdict(rankedComplete, lowestExposureGroup)
        val evidence = buildSupportingEvidence(request, rankedComplete, incompleteEvaluations, lowestExposureGroup, verdict)
        val prepAdvice = buildPreparationAdvice(request, referenceBest, verdict)

        return ActivityPlanningOutcome.Evaluated(
            request = request,
            evaluatedAt = evaluatedAt,
            candidates = allCandidatesChronological,
            completeCandidatesRanked = rankedComplete,
            lowestExposureDepartures = lowestDepartures,
            relativeComparisons = comparisons,
            verdict = verdict,
            supportingEvidence = evidence,
            forecastFreshness = updatedFreshness,
            preparationAdvice = prepAdvice,
            limitations = baseLimitations
        )
    }

    private data class UnrankedCompleteCandidate(
        val departureTime: Instant,
        val endTime: Instant,
        val localDepartureTime: java.time.ZonedDateTime,
        val localEndTime: java.time.ZonedDateTime,
        val conditions: CandidateForecastConditionsSummary,
        val hourlyAssessments: List<HeatAssessment.Supported>,
        val worstCategory: NwsHeatIndexCategory,
        val peakHeatIndexCelsius: Double,
        val peakHeatIndexFahrenheit: Double,
        val meanHeatIndexCelsius: Double
    )

    private fun rankCandidates(
        candidates: List<UnrankedCompleteCandidate>
    ): List<CandidateWindowEvaluation.Complete> {
        // Sorting uses a strict total order so TimSort's contract is never violated; tolerance-based
        // tie grouping is applied afterwards against each group's anchor candidate. This keeps
        // ranking deterministic even when A≈B and B≈C but A is not within tolerance of C.
        val sorted = candidates.sortedWith { a, b -> compareCandidates(a, b) }
        val leader = sorted.first()
        val tiedWithLeaderCount = sorted.count { areTied(leader, it) }

        val ranked = ArrayList<CandidateWindowEvaluation.Complete>(sorted.size)
        var groupAnchor = leader
        var groupStartIndex = 0
        for (i in sorted.indices) {
            val item = sorted[i]
            if (!areTied(groupAnchor, item)) {
                groupAnchor = item
                groupStartIndex = i
            }
            val currentRank = groupStartIndex + 1
            val tiedForLowest = currentRank == 1 && tiedWithLeaderCount > 1
            ranked.add(
                CandidateWindowEvaluation.Complete(
                    departureTime = item.departureTime,
                    endTime = item.endTime,
                    localDepartureTime = item.localDepartureTime,
                    localEndTime = item.localEndTime,
                    conditions = item.conditions,
                    hourlyAssessments = item.hourlyAssessments,
                    worstCategory = item.worstCategory,
                    peakHeatIndexCelsius = item.peakHeatIndexCelsius,
                    peakHeatIndexFahrenheit = item.peakHeatIndexFahrenheit,
                    meanHeatIndexCelsius = item.meanHeatIndexCelsius,
                    rank = currentRank,
                    isTiedForLowestExposure = tiedForLowest
                )
            )
        }
        return ranked
    }

    /**
     * Strict ordering (no tolerance) used only to produce a deterministic sort.
     * Tolerance-based equality is evaluated separately by [areTied].
     */
    private fun compareCandidates(a: UnrankedCompleteCandidate, b: UnrankedCompleteCandidate): Int {
        val catDiff = a.worstCategory.severityRank.compareTo(b.worstCategory.severityRank)
        if (catDiff != 0) return catDiff

        val peakDiff = a.peakHeatIndexCelsius.compareTo(b.peakHeatIndexCelsius)
        if (peakDiff != 0) return peakDiff

        val meanDiff = a.meanHeatIndexCelsius.compareTo(b.meanHeatIndexCelsius)
        if (meanDiff != 0) return meanDiff

        val uvA = a.conditions.maxUvIndex
        val uvB = b.conditions.maxUvIndex
        if (uvA != null && uvB != null) {
            val uvDiff = uvA.compareTo(uvB)
            if (uvDiff != 0) return uvDiff
        }

        return a.departureTime.compareTo(b.departureTime)
    }

    private fun areTied(a: UnrankedCompleteCandidate, b: UnrankedCompleteCandidate): Boolean {
        if (a.worstCategory != b.worstCategory) return false
        if (abs(a.peakHeatIndexCelsius - b.peakHeatIndexCelsius) > config.peakHeatIndexTieToleranceCelsius) return false
        if (abs(a.meanHeatIndexCelsius - b.meanHeatIndexCelsius) > config.meanHeatIndexTieToleranceCelsius) return false
        val uvA = a.conditions.maxUvIndex
        val uvB = b.conditions.maxUvIndex
        if (uvA != null && uvB != null && abs(uvA - uvB) > config.uvIndexTieTolerance) return false
        return true
    }

    private fun summarizeConditions(
        assessments: List<HeatAssessment.Supported>
    ): CandidateForecastConditionsSummary {
        // A complete window always contains at least one Supported hour, and every Supported
        // assessment is guaranteed to expose air temperature and relative humidity. Fail fast on an
        // invariant violation rather than letting NaN reach user-facing evidence strings.
        check(assessments.isNotEmpty()) {
            "Cannot summarize a candidate window with zero supported hourly assessments."
        }
        val temps = assessments.mapNotNull { it.inputs.airTemperature.inUnitOrNull(TemperatureUnit.CELSIUS) }
        val rhs = assessments.mapNotNull { it.inputs.relativeHumidity.rawValueOrNull() }
        val winds = assessments.mapNotNull { it.inputs.windSpeed.inUnitOrNull(SpeedUnit.METERS_PER_SECOND) }
        val uvs = assessments.mapNotNull { it.inputs.uvIndex.rawValueOrNull() }
        val solars = assessments.mapNotNull { it.inputs.solarRadiation.rawValueOrNull() }
        val missingOptional = assessments
            .flatMap { it.missingInformation }
            .map { it.variableName }
            .toSet()

        check(temps.isNotEmpty() && rhs.isNotEmpty()) {
            "Supported assessments must expose finite air temperature and relative humidity inputs."
        }

        return CandidateForecastConditionsSummary(
            coveredHourCount = assessments.size,
            minAirTemperatureCelsius = temps.min(),
            maxAirTemperatureCelsius = temps.max(),
            meanRelativeHumidityPercent = rhs.average(),
            maxWindSpeedMetersPerSecond = winds.maxOrNull(),
            maxUvIndex = uvs.maxOrNull(),
            maxSolarRadiationWm2 = solars.maxOrNull(),
            missingOptionalVariables = missingOptional
        )
    }

    private fun determineVerdict(
        ranked: List<CandidateWindowEvaluation.Complete>,
        lowestGroup: List<CandidateWindowEvaluation.Complete>
    ): ExposureComparisonVerdict {
        val best = ranked.first()
        return when {
            best.worstCategory.severityRank >= NwsHeatIndexCategory.DANGER.severityRank ->
                ExposureComparisonVerdict.HIGH_HEAT_ACROSS_ALL_CANDIDATES
            best.worstCategory.severityRank >= NwsHeatIndexCategory.CAUTION.severityRank ->
                ExposureComparisonVerdict.LOWER_RELATIVE_HEAT_STILL_ELEVATED
            lowestGroup.size == ranked.size && ranked.size > 1 ->
                ExposureComparisonVerdict.UNIFORM_CONDITIONS_TIED
            else ->
                ExposureComparisonVerdict.LOWER_RELATIVE_HEAT_BELOW_CAUTION
        }
    }

    private fun buildRelativeSummary(
        candidate: CandidateWindowEvaluation.Complete,
        reference: CandidateWindowEvaluation.Complete,
        peakDeltaC: Double,
        meanDeltaC: Double
    ): String {
        if (candidate.departureTime == reference.departureTime) {
            return String.format(
                Locale.US,
                "Reference lowest-exposure window (%s): peak Heat Index %.1f °C (%s).",
                candidate.localDepartureTime,
                candidate.peakHeatIndexCelsius,
                candidate.worstCategory.displayName
            )
        }
        if (candidate.rank == 1) {
            return String.format(
                Locale.US,
                "Tied for lowest forecast heat exposure (%s): peak Heat Index %.1f °C (Δ %.2f °C vs %s, %s).",
                candidate.localDepartureTime,
                candidate.peakHeatIndexCelsius,
                peakDeltaC,
                reference.localDepartureTime,
                candidate.worstCategory.displayName
            )
        }
        return String.format(
            Locale.US,
            "Departing at %s increases peak Heat Index by +%.1f °C (mean %+.1f °C) relative to %s (%s vs %s).",
            candidate.localDepartureTime,
            peakDeltaC,
            meanDeltaC,
            reference.localDepartureTime,
            candidate.worstCategory.displayName,
            reference.worstCategory.displayName
        )
    }

    private fun buildSupportingEvidence(
        request: ActivityPlanningRequest,
        ranked: List<CandidateWindowEvaluation.Complete>,
        incomplete: List<CandidateWindowEvaluation.IncompleteCoverage>,
        lowestGroup: List<CandidateWindowEvaluation.Complete>,
        verdict: ExposureComparisonVerdict
    ): List<String> = buildList {
        val best = ranked.first()
        val worst = ranked.last()
        add(
            String.format(
                Locale.US,
                "Evaluated %d complete departure window(s) for %d-minute %s using %s.",
                ranked.size,
                request.plannedDuration.toMinutes(),
                request.activityCategory.displayName,
                CalculationMethodSpec.NOAA_NWS_HEAT_INDEX_SR_90_23.name
            )
        )
        if (lowestGroup.size > 1) {
            add(
                String.format(
                    Locale.US,
                    "%d departure times (%s) are tied for lowest forecast thermal load within ±%.2f °C tolerance (peak Heat Index %.1f °C, %s).",
                    lowestGroup.size,
                    lowestGroup.joinToString(", ") { it.localDepartureTime.toString() },
                    config.peakHeatIndexTieToleranceCelsius,
                    best.peakHeatIndexCelsius,
                    best.worstCategory.displayName
                )
            )
        } else {
            add(
                String.format(
                    Locale.US,
                    "Lowest forecast heat exposure starts at %s with peak Heat Index %.1f °C (max air temp %.1f °C, mean RH %.0f%%, %s).",
                    best.localDepartureTime,
                    best.peakHeatIndexCelsius,
                    best.conditions.maxAirTemperatureCelsius,
                    best.conditions.meanRelativeHumidityPercent,
                    best.worstCategory.displayName
                )
            )
        }
        if (ranked.size > 1 && worst.departureTime != best.departureTime) {
            add(
                String.format(
                    Locale.US,
                    "Warmest evaluated window (%s) reaches peak Heat Index %.1f °C (%s), %.1f °C higher than %s.",
                    worst.localDepartureTime,
                    worst.peakHeatIndexCelsius,
                    worst.worstCategory.displayName,
                    worst.peakHeatIndexCelsius - best.peakHeatIndexCelsius,
                    best.localDepartureTime
                )
            )
        }
        if (verdict == ExposureComparisonVerdict.HIGH_HEAT_ACROSS_ALL_CANDIDATES) {
            add("All complete candidate windows remain in NWS Danger or Extreme Danger categories; choosing a relatively cooler hour does NOT remove severe heat hazard.")
        } else if (verdict == ExposureComparisonVerdict.LOWER_RELATIVE_HEAT_STILL_ELEVATED) {
            add("Even the lowest-exposure window falls in ${best.worstCategory.displayName}; lower relative heat does NOT mean conditions are risk-free.")
        }
        if (incomplete.isNotEmpty()) {
            add("${incomplete.size} candidate departure window(s) were excluded from ranking due to missing or incomplete hourly forecast coverage.")
        }
    }

    private fun buildPreparationAdvice(
        request: ActivityPlanningRequest,
        best: CandidateWindowEvaluation.Complete,
        verdict: ExposureComparisonVerdict
    ): List<String> = buildList {
        when (verdict) {
            ExposureComparisonVerdict.HIGH_HEAT_ACROSS_ALL_CANDIDATES -> {
                add("NWS Danger / Extreme Danger conditions persist across every evaluated departure slot; consider postponing, shortening, or relocating strenuous outdoor activity if feasible.")
                add("Arrange access to cooling/rest stops and water before departure, and monitor for signs of heat exhaustion or heat stroke.")
            }
            ExposureComparisonVerdict.LOWER_RELATIVE_HEAT_STILL_ELEVATED -> {
                add("Even the cooler departure window remains in NWS ${best.worstCategory.displayName}; carry water, pace exertion, and schedule rest breaks.")
            }
            ExposureComparisonVerdict.LOWER_RELATIVE_HEAT_BELOW_CAUTION,
            ExposureComparisonVerdict.UNIFORM_CONDITIONS_TIED -> {
                add("Forecast Heat Index stays below the 80°F (26.7°C) NWS caution threshold during the lowest-exposure window; bring water appropriate for a ${request.plannedDuration.toMinutes()}-minute ${request.activityCategory.displayName.lowercase(Locale.US)} session.")
            }
        }

        if (request.intensity == ActivityIntensity.MODERATE || request.intensity == ActivityIntensity.VIGOROUS) {
            add("Planned intensity (${request.intensity.displayName}) increases metabolic heat production above the light-walking reference assumed by the NWS Heat Index.")
        }

        best.conditions.maxUvIndex?.let { maxUv ->
            if (maxUv >= 3.0) {
                add(String.format(Locale.US, "Forecast UV Index reaches %.1f during the lowest-exposure window; consider sun-protective clothing, hat, sunglasses, and sunscreen.", maxUv))
            }
        }
        best.conditions.maxSolarRadiationWm2?.let { maxSolar ->
            if (maxSolar >= 500.0) {
                add(String.format(Locale.US, "Forecast shortwave solar irradiance reaches %.0f W/m²; direct sun exposure can raise perceived heat above shade-based Heat Index numbers.", maxSolar))
            }
        }
    }

    private fun buildPlanningLimitations(
        request: ActivityPlanningRequest,
        flags: Set<QualityFlag>,
        freshnessState: FreshnessState
    ): List<String> = buildList {
        add("Comparison relies on gridded hourly weather forecasts and does not know street-level shade, pavement radiant heat, elevation changes along a route, or indoor transit segments.")
        add("Hydro does not estimate personalized safe exposure time limits, sweat rates, or medical risk; personal acclimatization, hydration, clothing, and health status are unknown.")
        add("Lower relative Heat Index in one departure slot does not guarantee that an outdoor activity is safe.")
        if (request.intensity == null) {
            add("Activity intensity was not specified; metabolic exertion above a gentle walk increases thermal strain beyond standard NWS Heat Index assumptions.")
        }
        if (QualityFlag.SERVED_FROM_CACHE in flags) {
            add("Forecast data was loaded from local cache rather than a live network fetch.")
        }
        if (freshnessState == FreshnessState.STALE) {
            add("Forecast data is marked STALE; updated model guidance may differ significantly.")
        }
    }

    private fun generateCandidateDepartures(request: ActivityPlanningRequest): List<Instant>? {
        val result = ArrayList<Instant>()
        var current = request.earliestDeparture
        while (!current.isAfter(request.latestDeparture)) {
            if (result.size >= config.maxCandidateCount) {
                return null
            }
            result.add(current)
            current = current.plus(request.candidateStep)
        }
        return result
    }

    companion object {
        private const val COMPONENT = "ActivityPlanningService"

        internal fun requiredHourlySlots(departure: Instant, endExclusive: Instant): List<Instant> {
            val firstHour = departure.truncatedTo(ChronoUnit.HOURS)
            val lastOccupiedInstant = endExclusive.minusNanos(1)
            val lastHour = lastOccupiedInstant.truncatedTo(ChronoUnit.HOURS)
            val hours = ArrayList<Instant>()
            var cursor = firstHour
            while (!cursor.isAfter(lastHour)) {
                hours.add(cursor)
                cursor = cursor.plus(Duration.ofHours(1))
            }
            return hours
        }
    }
}
