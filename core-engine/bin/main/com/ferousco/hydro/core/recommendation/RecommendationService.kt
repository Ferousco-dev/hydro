package com.ferousco.hydro.core.recommendation

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.NoOpDiagnosticLogger
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.provider.WeatherRepository
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import kotlin.math.ceil

/**
 * Assembles the deterministic recommendation pipeline: fetch → assess → recommend.
 *
 * The engine is deliberately separate from [RecommendationEngine] so the pure decision rules stay
 * testable without I/O, while this service owns the one place where weather retrieval and assessment
 * are joined for guidance. A UI screen and a future proactive recommendation pass can both call it
 * without re-implementing the assembly.
 *
 * Honesty rules:
 * - A failed current-observation fetch is an [EngineResult.Failure], not a fabricated recommendation.
 * - A failed forecast fetch is not fatal: guidance falls back to current conditions and records the
 *   missing coverage as a limitation.
 * - Stale current data is assessed with `allowStaleInput = true` so [RecommendationEngine] can mark
 *   it `UNKNOWN` and ask for a refresh instead of presenting an all-clear.
 * - Stale forecast points are dropped (`allowStaleInput = false`), because a seven-hour-old forecast
 *   must not become today's coolest window.
 */
class RecommendationService(
    private val weatherRepository: WeatherRepository,
    private val assessmentEngine: HeatAssessmentEngine,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) {

    suspend fun forLocation(
        location: GeoLocation,
        activityCategory: ActivityCategory? = null,
        plannedDuration: Duration? = null,
        lookahead: Duration = DEFAULT_LOOKAHEAD
    ): EngineResult<Recommendation> {
        require(!lookahead.isNegative && !lookahead.isZero) { "lookahead must be positive." }
        require(plannedDuration == null || (!plannedDuration.isNegative && !plannedDuration.isZero)) {
            "plannedDuration must be positive when supplied."
        }

        val evaluatedAt = clock.instant()

        val observationEnvelope = when (val fetched = weatherRepository.getCurrentObservation(location)) {
            is EngineResult.Failure -> return fetched
            is EngineResult.Success -> fetched.value
        }

        val currentAssessment = assessmentEngine.assessObservation(
            observation = observationEnvelope.data,
            allowStaleInput = true
        )

        val forecastAssessments = when (
            val fetched = weatherRepository.getHourlyForecast(
                location = location,
                forecastHours = forecastHoursFor(lookahead)
            )
        ) {
            is EngineResult.Failure -> {
                logger.info(
                    COMPONENT,
                    "Forecast unavailable; recommending from current conditions only",
                    mapOf("error" to fetched.error.message)
                )
                emptyList()
            }

            is EngineResult.Success -> {
                val forecast = fetched.value
                val horizonEnd = evaluatedAt.plus(lookahead)
                forecast.data.hourly
                    .asSequence()
                    .filter { !it.validAt.isBefore(evaluatedAt) && !it.validAt.isAfter(horizonEnd) }
                    .mapNotNull { point ->
                        assessmentEngine.assessForecastPoint(
                            point = point,
                            forecastQuality = forecast.data.quality,
                            allowStaleInput = false
                        ) as? HeatAssessment.Supported
                    }
                    .toList()
            }
        }

        val recommendation = RecommendationEngine.recommend(
            RecommendationRequest(
                location = location,
                evaluatedAt = evaluatedAt,
                timeZone = location.timeZone ?: ZoneId.systemDefault(),
                currentAssessment = currentAssessment,
                forecastAssessments = forecastAssessments,
                activityCategory = activityCategory,
                plannedDuration = plannedDuration,
                lookahead = lookahead
            )
        )

        logger.info(
            COMPONENT,
            "Generated deterministic recommendation",
            mapOf(
                "urgency" to recommendation.urgency.name,
                "action_count" to recommendation.actions.size.toString(),
                "forecast_hours_used" to forecastAssessments.size.toString(),
                "confidence" to recommendation.confidence.toString()
            )
        )

        return EngineResult.Success(recommendation)
    }

    companion object {
        const val COMPONENT = "RecommendationService"

        /** Twelve hours covers the rest of a typical day without turning tomorrow into today's alert. */
        val DEFAULT_LOOKAHEAD: Duration = Duration.ofHours(12)

        /** Hard ceiling so one recommendation cannot request more than two days of forecast. */
        private const val MAX_FORECAST_HOURS = 48

        /**
         * The provider returns hourly points, so a 12-hour lookahead needs 13 points to include the
         * far edge. One extra hour also absorbs a point that has just fallen into the past.
         */
        private fun forecastHoursFor(lookahead: Duration): Int =
            (ceil(lookahead.toMinutes() / 60.0).toInt() + 1).coerceIn(2, MAX_FORECAST_HOURS)
    }
}
