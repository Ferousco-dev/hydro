package com.ferousco.hydro.core

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.history.InMemoryEnvironmentalHistoryStore
import com.ferousco.hydro.core.integration.InMemoryNotificationSink
import com.ferousco.hydro.core.integration.NotificationCategory
import com.ferousco.hydro.core.integration.NotificationSeverity
import com.ferousco.hydro.core.integration.NotificationTrigger
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.preferences.AlertPreferences
import com.ferousco.hydro.core.preferences.InMemoryUserPreferencesStore
import com.ferousco.hydro.core.preferences.UserPreferences
import com.ferousco.hydro.core.proactive.ProactiveDecisionEngine
import com.ferousco.hydro.core.proactive.ProactiveEvaluationRequest
import com.ferousco.hydro.core.proactive.ProactiveSignalType
import com.ferousco.hydro.core.tools.ToolCallOutcome
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C4 coverage: the deterministic "Evaluate → Decide" stage of proactive monitoring.
 *
 * The engine must never invent an alert, never call a model, and never bypass the single
 * `create_notification` policy path.
 */
class ProactiveDecisionEngineTest {

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = MutableClock(now)
    private val assessmentEngine = HeatAssessmentEngine(clock)

    // ------------------------------------------------------------------
    // Assessment builders
    // ------------------------------------------------------------------

    private fun observationAssessment(
        airTempC: Double,
        rhPercent: Double,
        uvIndex: Double? = null,
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

    private fun forecastAssessment(
        airTempC: Double,
        rhPercent: Double,
        uvIndex: Double? = null,
        validAt: Instant = now.plus(Duration.ofHours(3))
    ): HeatAssessment {
        val forecast = TestFixtures.buildHourlyForecast(
            startHour = validAt,
            hourlySpecs = listOf(Triple(airTempC, rhPercent, uvIndex)),
            retrievedAt = now
        )
        return assessmentEngine.assessForecastPoint(
            forecast.hourly.first(),
            forecastQuality = forecast.quality
        )
    }

    private fun supported(assessment: HeatAssessment): HeatAssessment.Supported {
        assertTrue(assessment is HeatAssessment.Supported, "Expected a supported assessment.")
        return assessment
    }

    private fun request(
        current: HeatAssessment? = null,
        forecast: HeatAssessment? = null,
        previous: NwsHeatIndexCategory? = null,
        activity: ActivityCategory? = null,
        minutes: Int? = null
    ): ProactiveEvaluationRequest = ProactiveEvaluationRequest(
        location = TestFixtures.IBADAN,
        evaluatedAt = now,
        timeZone = ZoneId.of("Africa/Lagos"),
        currentAssessment = current,
        forecastAssessment = forecast,
        previousForecastCategory = previous,
        activityCategory = activity,
        plannedOutdoorMinutes = minutes
    )

    // ------------------------------------------------------------------
    // 1. Thresholds
    // ------------------------------------------------------------------

    @Test
    fun `a caution-level reading does not interrupt the user`() {
        val assessment = supported(observationAssessment(airTempC = 28.0, rhPercent = 40.0))
        assertEquals(NwsHeatIndexCategory.CAUTION, assessment.category)

        val decision = ProactiveDecisionEngine.evaluate(request(current = assessment))

        assertFalse(decision.shouldNotify)
        assertTrue(decision.alerts.isEmpty())
        assertTrue(decision.rationale.any { it.contains("below the") })
        assertTrue(decision.rationale.any { it.contains("nothing will be sent") })
    }

    @Test
    fun `extreme caution raises a warning with an actionable body`() {
        val assessment = supported(observationAssessment(airTempC = 32.0, rhPercent = 60.0))
        assertEquals(NwsHeatIndexCategory.EXTREME_CAUTION, assessment.category)

        val decision = ProactiveDecisionEngine.evaluate(request(current = assessment))

        assertTrue(decision.shouldNotify)
        val alert = decision.alerts.single()
        assertEquals(ProactiveSignalType.HEAT_THRESHOLD, alert.signal)
        assertEquals(NotificationCategory.HEAT, alert.category)
        assertEquals(NotificationTrigger.HEAT_THRESHOLD, alert.trigger)
        assertEquals(NotificationSeverity.WARNING, alert.severity)
        assertEquals(0.95, alert.confidence, 0.0001)
        assertTrue(alert.title.startsWith("Heat risk: Extreme Caution"))
        assertTrue(alert.body.contains("°C"))
        assertTrue(alert.body.contains("Ibadan"))
        assertTrue(alert.body.length <= 280)
        assertTrue(alert.title.length <= 80)
        assertTrue(alert.dedupKey.startsWith("heat:"))
        assertEquals(4, alert.evidence.size)
        assertTrue(alert.expiresAt.isAfter(now))
    }

    @Test
    fun `danger escalates to critical so it can bypass quiet hours`() {
        val assessment = supported(observationAssessment(airTempC = 35.0, rhPercent = 60.0))
        assertEquals(NwsHeatIndexCategory.DANGER, assessment.category)

        val alert = ProactiveDecisionEngine.evaluate(request(current = assessment)).alerts.single()

        assertEquals(NotificationSeverity.CRITICAL, alert.severity)
        assertTrue(alert.suggestedNextStep.contains("Avoid outdoor exertion"))
    }

    @Test
    fun `very high UV raises its own alert alongside heat`() {
        val assessment = supported(
            observationAssessment(airTempC = 32.0, rhPercent = 60.0, uvIndex = 9.0)
        )
        assertNotNull(assessment.uvCategory)
        assertEquals(3, assessment.uvCategory!!.severityRank)

        val decision = ProactiveDecisionEngine.evaluate(request(current = assessment))

        assertEquals(2, decision.alerts.size)
        val uv = decision.alerts.single { it.signal == ProactiveSignalType.UV_THRESHOLD }
        assertEquals(NotificationCategory.UV, uv.category)
        assertEquals(NotificationSeverity.WARNING, uv.severity)
        assertTrue(uv.dedupKey.startsWith("uv:"))
        // Heat is more severe than UV, so it is ordered first.
        assertEquals(ProactiveSignalType.HEAT_THRESHOLD, decision.alerts.first().signal)
    }

    @Test
    fun `moderate UV is reported in the rationale but not sent`() {
        val assessment = supported(
            observationAssessment(airTempC = 28.0, rhPercent = 40.0, uvIndex = 5.0)
        )
        val decision = ProactiveDecisionEngine.evaluate(request(current = assessment))

        assertFalse(decision.shouldNotify)
        assertTrue(decision.rationale.any { it.contains("UV index") })
    }

    // ------------------------------------------------------------------
    // 2. Forecast deterioration
    // ------------------------------------------------------------------

    @Test
    fun `a sub-threshold forecast deterioration only alerts when an activity is planned`() {
        val forecast = supported(forecastAssessment(airTempC = 28.0, rhPercent = 40.0))
        assertEquals(NwsHeatIndexCategory.CAUTION, forecast.category)

        val withoutActivity = ProactiveDecisionEngine.evaluate(
            request(forecast = forecast, previous = NwsHeatIndexCategory.BELOW_CAUTION_THRESHOLD)
        )
        assertFalse(withoutActivity.shouldNotify)
        assertTrue(withoutActivity.rationale.any { it.contains("no planned outdoor activity") })

        val withActivity = ProactiveDecisionEngine.evaluate(
            request(
                forecast = forecast,
                previous = NwsHeatIndexCategory.BELOW_CAUTION_THRESHOLD,
                activity = ActivityCategory.RUNNING,
                minutes = 60
            )
        )
        val alert = withActivity.alerts.single()
        assertEquals(ProactiveSignalType.FORECAST_DETERIORATION, alert.signal)
        assertEquals(NotificationCategory.WEATHER_CHANGE, alert.category)
        assertEquals(NotificationTrigger.FORECAST_CHANGE, alert.trigger)
        assertEquals(NotificationSeverity.WATCH, alert.severity)
        assertTrue(alert.body.contains("60-minute running"))
        assertTrue(alert.dedupKey.contains("forecast-change"))
    }

    @Test
    fun `a deterioration that also crosses the heat threshold is not sent twice`() {
        val forecast = supported(forecastAssessment(airTempC = 32.0, rhPercent = 60.0))
        val decision = ProactiveDecisionEngine.evaluate(
            request(
                forecast = forecast,
                previous = NwsHeatIndexCategory.CAUTION,
                activity = ActivityCategory.CYCLING,
                minutes = 45
            )
        )

        assertEquals(1, decision.alerts.size)
        assertEquals(ProactiveSignalType.HEAT_THRESHOLD, decision.alerts.single().signal)
        assertTrue(decision.rationale.any { it.contains("already covers it") })
    }

    @Test
    fun `an improving forecast produces no alert`() {
        val forecast = supported(forecastAssessment(airTempC = 28.0, rhPercent = 40.0))
        val decision = ProactiveDecisionEngine.evaluate(
            request(
                forecast = forecast,
                previous = NwsHeatIndexCategory.EXTREME_CAUTION,
                activity = ActivityCategory.WALKING,
                minutes = 30
            )
        )

        assertFalse(decision.shouldNotify)
        assertTrue(decision.rationale.any { it.contains("did not deteriorate") })
    }

    // ------------------------------------------------------------------
    // 3. Missing data
    // ------------------------------------------------------------------

    @Test
    fun `missing assessments produce a rationale instead of a guess`() {
        val decision = ProactiveDecisionEngine.evaluate(request())

        assertFalse(decision.shouldNotify)
        assertTrue(decision.rationale.any { it.contains("No supported heat assessment") })
    }

    @Test
    fun `an incomplete assessment is explained and never alerted on`() {
        val incomplete = assessmentEngine.assessObservation(
            TestFixtures.buildObservation(airTempC = 32.0, rhPercent = null)
        )
        assertTrue(incomplete is HeatAssessment.Incomplete)

        val decision = ProactiveDecisionEngine.evaluate(request(current = incomplete))

        assertFalse(decision.shouldNotify)
        assertTrue(decision.rationale.any { it.contains("incomplete") })
    }

    // ------------------------------------------------------------------
    // 4. Confidence follows data quality
    // ------------------------------------------------------------------

    @Test
    fun `stale data yields low confidence so policy suppresses it`() {
        val stale = supported(
            observationAssessment(
                airTempC = 32.0,
                rhPercent = 60.0,
                observedAt = now.minus(Duration.ofHours(3)),
                retrievedAt = now.minus(Duration.ofHours(3)),
                evaluatedAt = now,
                allowStaleInput = true
            )
        )
        assertEquals(0.4, ProactiveDecisionEngine.confidenceFor(stale), 0.0001)

        val forecast = supported(forecastAssessment(airTempC = 32.0, rhPercent = 60.0))
        assertEquals(0.85, ProactiveDecisionEngine.confidenceFor(forecast), 0.0001)
    }

    // ------------------------------------------------------------------
    // 5. End-to-end delivery through create_notification
    // ------------------------------------------------------------------

    @Test
    fun `runProactiveCheck submits through the notification policy and dedups a repeat`() = runBlocking {
        val sink = InMemoryNotificationSink(clock)
        val engine = HydroEngine.create(
            historyStore = InMemoryEnvironmentalHistoryStore(),
            notificationSink = sink,
            preferencesStore = InMemoryUserPreferencesStore(clock = clock),
            clock = clock
        )
        val assessment = supported(observationAssessment(airTempC = 32.0, rhPercent = 60.0))

        val first = engine.runProactiveCheck(request(current = assessment))
        assertEquals(1, first.decision.alerts.size)
        assertEquals(1, first.submittedCount)
        assertEquals(0, first.suppressedCount)
        assertEquals(1, sink.deliveredIntents().size)

        val repeated = engine.runProactiveCheck(request(current = assessment))
        assertEquals(1, repeated.decision.alerts.size)
        assertEquals(0, repeated.submittedCount)
        assertEquals(1, repeated.suppressedCount)
        val suppressed = repeated.deliveries.single().outcome as ToolCallOutcome.Success
        assertEquals("SUPPRESSED", suppressed.jsonResult.getString("status"))
        assertEquals("DUPLICATE", suppressed.jsonResult.getString("reason"))
        assertEquals(1, sink.deliveredIntents().size)
    }

    @Test
    fun `runProactiveCheck honours a user who disabled alerts`() = runBlocking {
        val sink = InMemoryNotificationSink(clock)
        val engine = HydroEngine.create(
            historyStore = InMemoryEnvironmentalHistoryStore(),
            notificationSink = sink,
            preferencesStore = InMemoryUserPreferencesStore(
                UserPreferences(alerts = AlertPreferences(enabled = false)),
                clock
            ),
            clock = clock
        )
        val assessment = supported(observationAssessment(airTempC = 35.0, rhPercent = 60.0))

        val result = engine.runProactiveCheck(request(current = assessment))

        assertEquals(1, result.decision.alerts.size)
        assertEquals(0, result.submittedCount)
        assertEquals(1, result.suppressedCount)
        val suppressed = result.deliveries.single().outcome as ToolCallOutcome.Success
        assertEquals("USER_DISABLED", suppressed.jsonResult.getString("reason"))
        assertTrue(sink.deliveredIntents().isEmpty())
    }

    @Test
    fun `runProactiveCheck reports a policy failure without throwing`() = runBlocking {
        // No sink wired: create_notification returns UNSUPPORTED_CALCULATION, which must surface as
        // a delivery failure rather than crashing the background job.
        val engine = HydroEngine.create(
            historyStore = InMemoryEnvironmentalHistoryStore(),
            preferencesStore = InMemoryUserPreferencesStore(clock = clock),
            clock = clock
        )
        val assessment = supported(observationAssessment(airTempC = 32.0, rhPercent = 60.0))

        val result = engine.runProactiveCheck(request(current = assessment))

        assertEquals(0, result.submittedCount)
        val failure = result.deliveries.single().outcome
        assertTrue(failure is ToolCallOutcome.Failure)
        assertEquals("UNSUPPORTED_CALCULATION", failure.errorCode)
    }
}
