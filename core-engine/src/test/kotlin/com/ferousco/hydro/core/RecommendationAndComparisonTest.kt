package com.ferousco.hydro.core

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.memory.HistoricalBaselineSelector
import com.ferousco.hydro.core.memory.HistoricalComparator
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.HistoricalRecordDataType
import com.ferousco.hydro.core.model.MeasuredQuantity
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.UnavailableReason
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.recommendation.RecommendationEngine
import com.ferousco.hydro.core.recommendation.RecommendationRequest
import com.ferousco.hydro.core.recommendation.RecommendationUrgency
import com.ferousco.hydro.core.recommendation.RecommendedActionKind
import com.ferousco.hydro.core.model.HeatAssessment
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the deterministic guidance layer added after C4:
 *
 * 1. `RecommendationEngine` turns an assessment into urgency + next steps and a coolest window.
 * 2. `HistoricalBaselineSelector` / `HistoricalComparator` answer "how does today compare with
 *    yesterday?" without fabricating a baseline.
 */
class RecommendationAndComparisonTest {

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val zone = ZoneId.of("Africa/Lagos")
    private val clock = MutableClock(now)
    private val assessmentEngine = HeatAssessmentEngine(clock)

    // ------------------------------------------------------------------
    // Builders
    // ------------------------------------------------------------------

    private fun observationAssessment(
        airTempC: Double,
        rhPercent: Double? = 60.0,
        uvIndex: Double? = 2.0,
        observedAt: Instant = now,
        retrievedAt: Instant = now,
        evaluatedAt: Instant = now,
        allowStaleInput: Boolean = false
    ): HeatAssessment = assessmentEngine.assessObservation(
        TestFixtures.buildObservation(
            observedAt = observedAt,
            retrievedAt = retrievedAt,
            evaluatedAt = evaluatedAt,
            airTempC = airTempC,
            rhPercent = rhPercent,
            uvIndex = uvIndex
        ),
        allowStaleInput = allowStaleInput
    )

    private fun forecastAssessments(
        startHour: Instant,
        specs: List<Triple<Double?, Double?, Double?>>,
        skipHourOffsets: Set<Int> = emptySet()
    ): List<HeatAssessment.Supported> {
        val forecast = TestFixtures.buildHourlyForecast(
            startHour = startHour,
            hourlySpecs = specs,
            retrievedAt = now,
            skipHourOffsets = skipHourOffsets
        )
        return forecast.hourly.mapNotNull { point ->
            assessmentEngine.assessForecastPoint(point, forecast.quality) as? HeatAssessment.Supported
        }
    }

    private fun supported(assessment: HeatAssessment): HeatAssessment.Supported {
        assertTrue(assessment is HeatAssessment.Supported, "Expected a supported assessment.")
        return assessment
    }

    private fun request(
        current: HeatAssessment?,
        forecast: List<HeatAssessment.Supported> = emptyList(),
        activity: ActivityCategory? = null,
        duration: Duration? = null
    ): RecommendationRequest = RecommendationRequest(
        location = TestFixtures.IBADAN,
        evaluatedAt = now,
        timeZone = zone,
        currentAssessment = current,
        forecastAssessments = forecast,
        activityCategory = activity,
        plannedDuration = duration
    )

    private fun storedRecord(
        observation: EnvironmentalObservation,
        id: String,
        dataType: HistoricalRecordDataType = HistoricalRecordDataType.OBSERVATION,
        variables: EnvironmentalSnapshotVariables = observation.variables
    ): StoredEnvironmentalRecord = StoredEnvironmentalRecord(
        id = id,
        dataType = dataType,
        observationKind = if (dataType == HistoricalRecordDataType.ISSUED_FORECAST) {
            null
        } else {
            observation.observationKind
        },
        location = observation.effectiveLocation,
        source = observation.source,
        validAt = observation.observedAt,
        retrievedAt = observation.retrievedAt,
        timeZoneId = observation.timeZone.id,
        variables = variables
    )

    // ------------------------------------------------------------------
    // 1. Recommendation: thresholds and tone
    // ------------------------------------------------------------------

    @Test
    fun `below-threshold conditions recommend monitoring, not a false all-clear`() {
        val current = supported(observationAssessment(airTempC = 26.0, rhPercent = 50.0, uvIndex = 2.0))

        val recommendation = RecommendationEngine.recommend(request(current))

        assertEquals(RecommendationUrgency.LOW, recommendation.urgency)
        assertEquals("No heat risk right now", recommendation.headline)
        assertTrue(recommendation.actions.any { it.kind == RecommendedActionKind.MONITOR_CONDITIONS })
        assertTrue(recommendation.summary.contains("not a medical exposure limit"))
        assertFalse(recommendation.summary.contains("safe", ignoreCase = true))
        assertEquals(0.95, recommendation.confidence, 0.0001)
        assertTrue(recommendation.isCurrentData)
    }

    @Test
    fun `extreme caution limits exposure and points at a cooler window`() {
        val current = supported(observationAssessment(airTempC = 32.0, rhPercent = 60.0, uvIndex = 2.0))
        assertEquals("Extreme Caution", current.category.displayName)

        val forecast = forecastAssessments(
            startHour = now.plus(Duration.ofHours(3)),
            specs = listOf(
                Triple(30.0, 60.0, 2.0),
                Triple(26.0, 50.0, 2.0),
                Triple(26.0, 50.0, 2.0),
                Triple(26.0, 50.0, 2.0)
            )
        )
        val recommendation = RecommendationEngine.recommend(
            request(
                current = current,
                forecast = forecast,
                activity = ActivityCategory.RUNNING,
                duration = Duration.ofHours(2)
            )
        )

        assertEquals(RecommendationUrgency.HIGH, recommendation.urgency)
        assertEquals("Limit outdoor exposure", recommendation.headline)
        val kinds = recommendation.actions.map { it.kind }
        assertTrue(RecommendedActionKind.LIMIT_EXERTION in kinds)
        assertTrue(RecommendedActionKind.SHIFT_WINDOW in kinds)
        assertTrue(RecommendedActionKind.HYDRATE in kinds)
        assertTrue(RecommendedActionKind.REST_BREAKS in kinds)
        assertTrue(RecommendedActionKind.ACTIVITY_ADJUSTMENT in kinds)

        val window = assertNotNull(recommendation.bestWindow)
        assertEquals(now.plus(Duration.ofHours(4)), window.start)
        assertEquals(2, window.hoursEvaluated)
        assertTrue(recommendation.summary.contains("coolest forecast window"))
    }

    @Test
    fun `danger escalates to critical and includes symptom guidance`() {
        val current = supported(observationAssessment(airTempC = 35.0, rhPercent = 60.0, uvIndex = 2.0))
        assertEquals("Danger", current.category.displayName)

        val recommendation = RecommendationEngine.recommend(request(current))

        assertEquals(RecommendationUrgency.CRITICAL, recommendation.urgency)
        assertEquals("Avoid outdoor exertion", recommendation.headline)
        val kinds = recommendation.actions.map { it.kind }
        assertTrue(RecommendedActionKind.POSTPONE_OR_INDOORS in kinds)
        assertTrue(RecommendedActionKind.MONITOR_SYMPTOMS in kinds)
        assertTrue(RecommendedActionKind.CHECK_VULNERABLE in kinds)
    }

    @Test
    fun `high UV raises urgency even when heat is mild`() {
        val current = supported(observationAssessment(airTempC = 27.0, rhPercent = 50.0, uvIndex = 9.0))

        val recommendation = RecommendationEngine.recommend(request(current))

        assertEquals(RecommendationUrgency.MODERATE, recommendation.urgency)
        assertEquals("Protect against sun exposure", recommendation.headline)
        assertTrue(recommendation.actions.any { it.kind == RecommendedActionKind.SUN_PROTECTION })
        assertTrue(recommendation.evidence.any { it.contains("UV index category") })
    }

    @Test
    fun `a stale calm reading becomes unknown and asks for a refresh`() {
        val stale = supported(
            observationAssessment(
                airTempC = 26.0,
                rhPercent = 50.0,
                uvIndex = 2.0,
                observedAt = now.minus(Duration.ofHours(3)),
                retrievedAt = now.minus(Duration.ofHours(3)),
                evaluatedAt = now,
                allowStaleInput = true
            )
        )

        val recommendation = RecommendationEngine.recommend(request(stale))

        assertEquals(RecommendationUrgency.UNKNOWN, recommendation.urgency)
        assertEquals("Refresh before relying on this reading", recommendation.headline)
        assertTrue(recommendation.actions.any { it.kind == RecommendedActionKind.REFRESH_DATA })
        assertEquals(0.4, recommendation.confidence, 0.0001)
        assertFalse(recommendation.isCurrentData)
        assertTrue(recommendation.summary.contains("stale"))
    }

    @Test
    fun `forecast heat build is surfaced before the threshold is crossed`() {
        val current = supported(observationAssessment(airTempC = 27.0, rhPercent = 50.0, uvIndex = 2.0))
        val forecast = forecastAssessments(
            startHour = now.plus(Duration.ofHours(4)),
            specs = listOf(Triple(32.0, 60.0, 2.0))
        )

        val recommendation = RecommendationEngine.recommend(request(current, forecast))

        assertEquals(RecommendationUrgency.MODERATE, recommendation.urgency)
        assertEquals("Heat builds later", recommendation.headline)
        assertTrue(recommendation.summary.contains("reaches Extreme Caution"))
    }

    @Test
    fun `best window never spans a missing forecast hour`() {
        val forecast = forecastAssessments(
            startHour = now.plus(Duration.ofHours(1)),
            specs = listOf(
                Triple(30.0, 60.0, 2.0),
                Triple(30.0, 60.0, 2.0),
                Triple(30.0, 60.0, 2.0),
                Triple(26.0, 50.0, 2.0),
                Triple(26.0, 50.0, 2.0),
                Triple(26.0, 50.0, 2.0)
            ),
            skipHourOffsets = setOf(2)
        )

        val window = assertNotNull(
            RecommendationEngine.bestWindow(
                request(
                    current = null,
                    forecast = forecast,
                    duration = Duration.ofHours(3)
                )
            )
        )

        assertEquals(now.plus(Duration.ofHours(4)), window.start)
        assertEquals(3, window.hoursEvaluated)
        assertEquals("Below NWS Caution Threshold", window.peakCategory.displayName)
    }

    @Test
    fun `missing assessment yields no fabricated advice`() {
        val recommendation = RecommendationEngine.recommend(request(current = null))

        assertEquals(RecommendationUrgency.UNKNOWN, recommendation.urgency)
        assertEquals("Current conditions are unavailable", recommendation.headline)
        assertEquals(0.0, recommendation.confidence, 0.0)
        assertTrue(recommendation.evidence.isEmpty())
        assertTrue(recommendation.actions.single().kind == RecommendedActionKind.REFRESH_DATA)
    }

    // ------------------------------------------------------------------
    // 2. Historical baseline selection
    // ------------------------------------------------------------------

    @Test
    fun `selects the same clock time yesterday and ignores forecasts and older records`() = runBlocking {
        val yesterday = TestFixtures.buildObservation(
            observedAt = now.minus(Duration.ofDays(1)).plus(Duration.ofMinutes(10)),
            retrievedAt = now.minus(Duration.ofDays(1)).plus(Duration.ofMinutes(15)),
            airTempC = 29.0
        )
        val threeDaysAgo = TestFixtures.buildObservation(
            observedAt = now.minus(Duration.ofDays(3)),
            retrievedAt = now.minus(Duration.ofDays(3)),
            airTempC = 25.0
        )
        val forecast = TestFixtures.buildHourlyForecast(
            startHour = now.minus(Duration.ofDays(1)),
            hourlySpecs = listOf(Triple(31.0, 60.0, 2.0)),
            retrievedAt = now.minus(Duration.ofDays(1))
        )
        val forecastRecord = storedRecord(
            observation = TestFixtures.buildObservation(
                observedAt = forecast.hourly.first().validAt,
                retrievedAt = forecast.hourly.first().issuedOrRetrievedAt,
                airTempC = 31.0
            ),
            id = "forecast-yesterday",
            dataType = HistoricalRecordDataType.ISSUED_FORECAST,
            variables = forecast.hourly.first().variables
        )

        val selection = assertNotNull(
            HistoricalBaselineSelector.selectSameTimeYesterday(
                records = listOf(
                    storedRecord(yesterday, "obs-yesterday"),
                    storedRecord(threeDaysAgo, "obs-old"),
                    forecastRecord
                ),
                targetValidAt = now,
                targetLocation = TestFixtures.IBADAN
            )
        )

        assertEquals("obs-yesterday", selection.baseline.id)
        assertEquals(10L, selection.offsetFromTarget.toMinutes())
        assertTrue(selection.caveats.any { it.contains("same-clock-time snapshot") })
    }

    @Test
    fun `a record beyond the distance limit is not used as a baseline`() {
        val farAway = TestFixtures.buildObservation(
            observedAt = now.minus(Duration.ofDays(1)),
            retrievedAt = now.minus(Duration.ofDays(1)),
            location = TestFixtures.LAGOS
        )

        val selection = HistoricalBaselineSelector.selectSameTimeYesterday(
            records = listOf(storedRecord(farAway, "obs-lagos")),
            targetValidAt = now,
            maxDistanceKm = 10.0,
            targetLocation = TestFixtures.IBADAN
        )

        assertNull(selection)
    }

    @Test
    fun `comparison normalizes a Fahrenheit baseline to Celsius`() {
        val currentObservation = TestFixtures.buildObservation(
            observedAt = now,
            retrievedAt = now,
            airTempC = 30.0,
            rhPercent = 60.0
        )
        val baselineObservation = TestFixtures.buildObservation(
            observedAt = now.minus(Duration.ofDays(1)),
            retrievedAt = now.minus(Duration.ofDays(1)),
            airTempC = 30.0,
            rhPercent = 70.0
        )
        val fahrenheit = MeasuredQuantity(
            value = 86.0,
            unit = TemperatureUnit.FAHRENHEIT,
            validAt = baselineObservation.observedAt,
            location = baselineObservation.effectiveLocation,
            source = baselineObservation.source,
            nature = ValueNature.OBSERVED
        )
        val baseline = storedRecord(
            observation = baselineObservation,
            id = "obs-baseline",
            variables = baselineObservation.variables.copy(
                airTemperature = FieldAvailability.Available(fahrenheit)
            )
        )

        val summary = HistoricalComparator.compare(
            current = storedRecord(currentObservation, "obs-current"),
            baseline = baseline
        )

        val temperature = assertNotNull(summary.deltaFor(HistoricalComparator.AIR_TEMPERATURE))
        assertEquals("°C", temperature.unit)
        assertEquals(30.0, temperature.currentValue, 0.001)
        assertEquals(30.0, temperature.baselineValue, 0.001)
        assertEquals(0.0, temperature.delta, 0.001)

        val humidity = assertNotNull(summary.deltaFor(HistoricalComparator.RELATIVE_HUMIDITY))
        assertEquals(-10.0, humidity.delta, 0.001)
    }

    @Test
    fun `comparison reports provider caveats and never zero-fills a missing variable`() {
        val currentObservation = TestFixtures.buildObservation(observedAt = now, retrievedAt = now)
        val reanalysisObservation = TestFixtures.buildObservation(
            observedAt = now.minus(Duration.ofDays(1)),
            retrievedAt = now.minus(Duration.ofDays(1)),
            kind = com.ferousco.hydro.core.model.ObservationKind.HISTORICAL_REANALYSIS_OBSERVATION
        )
        val reanalysis = storedRecord(
            observation = reanalysisObservation,
            id = "obs-reanalysis",
            dataType = HistoricalRecordDataType.HISTORICAL_REANALYSIS_OBSERVATION
        )

        val withValues = HistoricalComparator.compare(
            current = storedRecord(currentObservation, "obs-current"),
            baseline = reanalysis
        )
        assertTrue(withValues.deltas.isNotEmpty())
        assertTrue(withValues.caveats.any { it.contains("different") })

        val unavailable = EnvironmentalSnapshotVariables(
            airTemperature = FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE),
            relativeHumidity = FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE),
            windSpeed = FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE),
            feelsLikeTemperature = FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE),
            uvIndex = FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE),
            solarRadiation = FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
        )
        val emptyBaseline = storedRecord(
            observation = reanalysisObservation,
            id = "obs-empty",
            variables = unavailable
        )
        val noDeltas = HistoricalComparator.compare(
            current = storedRecord(currentObservation, "obs-current"),
            baseline = emptyBaseline
        )
        assertTrue(noDeltas.deltas.isEmpty())
        assertTrue(noDeltas.caveats.any { it.contains("No environmental variable was available") })
    }

    @Test
    fun `compareToYesterday returns null when no baseline exists`() {
        val currentObservation = TestFixtures.buildObservation(observedAt = now, retrievedAt = now)

        val summary = HistoricalComparator.compareToYesterday(
            records = listOf(storedRecord(currentObservation, "obs-current")),
            current = storedRecord(currentObservation, "obs-current")
        )

        assertNull(summary)
    }
}
