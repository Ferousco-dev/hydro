package com.ferousco.hydro.monitoring

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.model.WeatherForecast
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Pure assembly rules for the background monitor, kept out of the Android worker so they can be
 * unit-tested on the JVM.
 *
 * The worker is deliberately thin: it resolves a location, fetches data, then hands the pieces to
 * these functions. Every decision about *what* to evaluate lives here, and every decision about
 * *whether to notify* still lives in `ProactiveDecisionEngine` + `create_notification`.
 */
object ProactiveMonitorLogic {

    /**
     * Only the next six hours are considered for the "near-term peak".
     *
     * A background pass is about what the user can still act on today. Looking 24 hours ahead would
     * surface tomorrow afternoon's heat as an interruption now, which is exactly the kind of noise
     * proactive alerts must avoid.
     */
    val NEAR_TERM_HORIZON: Duration = Duration.ofHours(6)

    /**
     * Assesses forecast points inside the near-term horizon.
     *
     * Points outside the window are dropped rather than clamped: a clamped point would claim the
     * heat at 18:00 describes 16:00. Unsupported points are dropped too, so a missing humidity value
     * cannot silently become a heat-index guess.
     */
    fun nearTermAssessments(
        forecast: WeatherForecast,
        assessmentEngine: HeatAssessmentEngine,
        evaluatedAt: Instant,
        allowStaleInput: Boolean = true
    ): List<HeatAssessment.Supported> {
        val horizonEnd = evaluatedAt.plus(NEAR_TERM_HORIZON)
        return forecast.hourly
            .asSequence()
            .filter { !it.validAt.isBefore(evaluatedAt) && !it.validAt.isAfter(horizonEnd) }
            .map { point ->
                assessmentEngine.assessForecastPoint(
                    point = point,
                    forecastQuality = forecast.quality,
                    allowStaleInput = allowStaleInput
                )
            }
            .filterIsInstance<HeatAssessment.Supported>()
            .toList()
    }

    /**
     * The worst near-term forecast hour.
     *
     * Ties on category break toward the higher heat index, then toward the earlier hour, so the
     * same input always produces the same alert and the dedup key stays stable across retries.
     */
    fun peak(assessments: List<HeatAssessment.Supported>): HeatAssessment.Supported? =
        assessments.minWithOrNull(
            compareByDescending<HeatAssessment.Supported> { it.category.severityRank }
                .thenByDescending { it.heatIndexCelsius.value }
                .thenBy { it.validAt }
        )

    /**
     * Storage key for the previous forecast category.
     *
     * Scoping by grid cell keeps two nearby saved locations from overwriting each other, and scoping
     * by local date stops yesterday's category from looking like a deterioration today.
     */
    fun forecastCategoryKey(
        location: GeoLocation,
        evaluatedAt: Instant,
        zone: ZoneId
    ): String {
        val cell = location.cacheCellKey()
        val localDate = evaluatedAt.atZone(zone).toLocalDate()
        return "forecast_category:$cell:$localDate"
    }

    /**
     * Parses a stored category name.
     *
     * A renamed or removed enum value returns `null` instead of throwing, because a stale preference
     * file must not crash a background pass. The caller then simply skips the deterioration check.
     */
    fun parseCategory(raw: String?): NwsHeatIndexCategory? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return NwsHeatIndexCategory.entries.firstOrNull { it.name == trimmed }
    }
}
