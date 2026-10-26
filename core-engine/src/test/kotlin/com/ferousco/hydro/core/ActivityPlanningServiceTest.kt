package com.ferousco.hydro.core

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.ActivityIntensity
import com.ferousco.hydro.core.model.ActivityPlanningOutcome
import com.ferousco.hydro.core.model.ActivityPlanningRequest
import com.ferousco.hydro.core.model.CandidateWindowEvaluation
import com.ferousco.hydro.core.model.ExposureComparisonVerdict
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.planning.ActivityPlanningService
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityPlanningServiceTest {

    private val baseTime = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = MutableClock(baseTime)
    private val assessmentEngine = HeatAssessmentEngine(clock = clock)
    private val planningService = ActivityPlanningService(
        assessmentEngine = assessmentEngine,
        clock = clock
    )

    @Test
    fun `afternoon cooling curve ranks lower-heat evening slot first without declaring elevated heat safe`() {
        // Hours 12:00Z..18:00Z (13:00..19:00 Africa/Lagos)
        // 12Z: 34C/65%, 13Z: 35C/62%, 14Z: 34C/60%, 15Z: 32C/62%, 16Z: 29.5C/68%, 17Z: 28C/72%
        val forecast = TestFixtures.buildHourlyForecast(
            startHour = baseTime,
            hourlySpecs = listOf(
                Triple(34.0, 65.0, 9.0),
                Triple(35.0, 62.0, 8.5),
                Triple(34.0, 60.0, 7.0),
                Triple(32.0, 62.0, 4.5),
                Triple(29.5, 68.0, 1.5),
                Triple(28.0, 72.0, 0.2)
            )
        )
        val request = ActivityPlanningRequest(
            location = TestFixtures.IBADAN,
            activityCategory = ActivityCategory.WALKING,
            plannedDuration = Duration.ofHours(2),
            intensity = ActivityIntensity.MODERATE,
            earliestDeparture = baseTime,
            latestDeparture = baseTime.plus(Duration.ofHours(4)),
            candidateStep = Duration.ofHours(1),
            forecast = forecast
        )

        val outcome = planningService.evaluate(request)
        assertTrue(outcome is ActivityPlanningOutcome.Evaluated)
        // Latest 2-hour window starting at 16:00Z (covering 16:00Z and 17:00Z) has lowest Heat Index,
        // yet 29.5°C @ 68% RH is still in EXTREME_CAUTION -> LOWER_RELATIVE_HEAT_STILL_ELEVATED!
        assertEquals(listOf(baseTime.plus(Duration.ofHours(4))), outcome.lowestExposureDepartures)
        assertEquals(ExposureComparisonVerdict.LOWER_RELATIVE_HEAT_STILL_ELEVATED, outcome.verdict)

        val best = outcome.completeCandidatesRanked.first()
        assertEquals(1, best.rank)
        assertFalse(best.isTiedForLowestExposure)
        assertEquals(NwsHeatIndexCategory.EXTREME_CAUTION, best.worstCategory)

        // Verify relative comparison against 12:00Z departure shows positive delta
        val middayComp = outcome.relativeComparisons.first { it.candidateDeparture == baseTime }
        assertTrue(middayComp.peakHeatIndexDeltaCelsius > 8.0)

        // Verify no fabricated safety claims or route shade inventions
        val allText = (outcome.supportingEvidence + outcome.preparationAdvice + outcome.limitations).joinToString(" ")
        assertTrue(allText.contains("does not guarantee", ignoreCase = true) || allText.contains("does NOT mean", ignoreCase = true))
        assertTrue(outcome.limitations.any { it.contains("shade", ignoreCase = true) })
    }

    @Test
    fun `severe heat across all windows yields HIGH_HEAT_ACROSS_ALL_CANDIDATES`() {
        val forecast = TestFixtures.buildHourlyForecast(
            startHour = baseTime,
            hourlySpecs = listOf(
                Triple(37.0, 65.0, 10.0),
                Triple(36.0, 62.0, 9.0),
                Triple(35.0, 60.0, 7.0)
            )
        )
        val request = ActivityPlanningRequest(
            location = TestFixtures.IBADAN,
            activityCategory = ActivityCategory.RUNNING,
            plannedDuration = Duration.ofHours(1),
            intensity = ActivityIntensity.VIGOROUS,
            earliestDeparture = baseTime,
            latestDeparture = baseTime.plus(Duration.ofHours(2)),
            forecast = forecast
        )
        val outcome = planningService.evaluate(request) as ActivityPlanningOutcome.Evaluated
        assertEquals(ExposureComparisonVerdict.HIGH_HEAT_ACROSS_ALL_CANDIDATES, outcome.verdict)
        assertTrue(outcome.supportingEvidence.any { it.contains("Danger", ignoreCase = true) })
    }

    @Test
    fun `DST spring-forward and fall-back transitions evaluate accurately without missing or duplicating UTC hours`() {
        val berlinZone = ZoneId.of("Europe/Berlin")

        // 1. Spring-forward: 2026-03-29 in Europe/Berlin jumps 02:00 CET -> 03:00 CEST (01:00Z is 03:00+02:00)
        val springStart = Instant.parse("2026-03-29T00:00:00Z")
        val springClock = MutableClock(springStart)
        val springService = ActivityPlanningService(HeatAssessmentEngine(springClock), clock = springClock)
        val springForecast = TestFixtures.buildHourlyForecast(
            startHour = springStart,
            hourlySpecs = List(5) { idx -> Triple(20.0 + idx, 50.0, 1.0) },
            location = TestFixtures.BERLIN,
            timeZone = berlinZone
        )
        val springReq = ActivityPlanningRequest(
            location = TestFixtures.BERLIN,
            activityCategory = ActivityCategory.CYCLING,
            plannedDuration = Duration.ofHours(2),
            earliestDeparture = springStart, // 01:00+01:00 local
            latestDeparture = springStart.plus(Duration.ofHours(2)), // 04:00+02:00 local
            forecast = springForecast
        )
        val springOut = springService.evaluate(springReq) as ActivityPlanningOutcome.Evaluated
        assertEquals(3, springOut.completeCandidatesRanked.size)
        val firstSpring = springOut.completeCandidatesRanked.first { it.departureTime == springStart }
        assertEquals(1, firstSpring.localDepartureTime.hour) // 01:00 CET
        assertEquals(4, firstSpring.localEndTime.hour)       // 2 real hours later = 04:00 CEST!

        // 2. Fall-back: 2026-10-25 in Europe/Berlin repeats 02:00 (00:00Z = 02:00+02:00 CEST, 01:00Z = 02:00+01:00 CET)
        val fallStart = Instant.parse("2026-10-25T00:00:00Z")
        val fallClock = MutableClock(fallStart)
        val fallService = ActivityPlanningService(HeatAssessmentEngine(fallClock), clock = fallClock)
        val fallForecast = TestFixtures.buildHourlyForecast(
            startHour = fallStart,
            hourlySpecs = listOf(
                Triple(28.0, 70.0, 0.0), // 00:00Z (02:00+02:00) warmer
                Triple(22.0, 55.0, 0.0), // 01:00Z (02:00+01:00) cooler
                Triple(21.0, 55.0, 0.0)  // 02:00Z (03:00+01:00) cooler
            ),
            location = TestFixtures.BERLIN,
            timeZone = berlinZone
        )
        val fallReq = ActivityPlanningRequest(
            location = TestFixtures.BERLIN,
            activityCategory = ActivityCategory.WALKING,
            plannedDuration = Duration.ofHours(1),
            earliestDeparture = fallStart,
            latestDeparture = fallStart.plus(Duration.ofHours(1)),
            forecast = fallForecast
        )
        val fallOut = fallService.evaluate(fallReq) as ActivityPlanningOutcome.Evaluated
        assertEquals(2, fallOut.completeCandidatesRanked.size)
        val first02 = fallOut.candidates[0]
        val second02 = fallOut.candidates[1]
        assertEquals(2, first02.localDepartureTime.hour)
        assertEquals(2, second02.localDepartureTime.hour)
        assertTrue(first02.localDepartureTime.offset != second02.localDepartureTime.offset)
        // Second 02:00 (CET +01:00) is cooler and ranked #1
        assertEquals(listOf(fallStart.plus(Duration.ofHours(1))), fallOut.lowestExposureDepartures)
    }

    @Test
    fun `missing forecast hours and incomplete duration coverage are explicitly reported rather than extrapolated`() {
        // Hour offset 1 (13:00Z) is skipped!
        val gappyForecast = TestFixtures.buildHourlyForecast(
            startHour = baseTime,
            hourlySpecs = listOf(
                Triple(30.0, 60.0, 5.0),
                Triple(31.0, 60.0, 6.0), // skipped!
                Triple(29.0, 60.0, 4.0),
                Triple(28.0, 60.0, 2.0)
            ),
            skipHourOffsets = setOf(1)
        )

        // 2-hour activity:
        // - 12:00Z needs [12Z, 13Z] -> incomplete (missing 13Z)
        // - 13:00Z needs [13Z, 14Z] -> incomplete (missing 13Z)
        // - 14:00Z needs [14Z, 15Z] -> COMPLETE!
        // - 15:00Z needs [15Z, 16Z] -> incomplete duration at horizon end (missing 16Z)
        val req = ActivityPlanningRequest(
            location = TestFixtures.IBADAN,
            activityCategory = ActivityCategory.WALKING,
            plannedDuration = Duration.ofHours(2),
            earliestDeparture = baseTime,
            latestDeparture = baseTime.plus(Duration.ofHours(3)),
            forecast = gappyForecast
        )
        val out = planningService.evaluate(req) as ActivityPlanningOutcome.Evaluated
        assertEquals(1, out.completeCandidatesRanked.size)
        assertEquals(baseTime.plus(Duration.ofHours(2)), out.completeCandidatesRanked.single().departureTime)
        assertEquals(3, out.candidates.filterIsInstance<CandidateWindowEvaluation.IncompleteCoverage>().size)

        // Now request a 4-hour activity where NO candidate window has complete coverage -> InsufficientData
        val impossibleReq = req.copy(plannedDuration = Duration.ofHours(4))
        val insufficient = planningService.evaluate(impossibleReq)
        assertTrue(insufficient is ActivityPlanningOutcome.InsufficientData)
        assertEquals(4, insufficient.candidateDiagnostics.size)
    }

    @Test
    fun `identical or near-identical candidate windows within tolerance are reported as tied`() {
        val tiedForecast = TestFixtures.buildHourlyForecast(
            startHour = baseTime,
            hourlySpecs = listOf(
                Triple(25.0, 50.0, 2.0),
                Triple(25.05, 50.0, 2.1),
                Triple(25.0, 50.0, 2.0)
            )
        )
        val req = ActivityPlanningRequest(
            location = TestFixtures.IBADAN,
            activityCategory = ActivityCategory.COMMUTING,
            plannedDuration = Duration.ofHours(1),
            earliestDeparture = baseTime,
            latestDeparture = baseTime.plus(Duration.ofHours(2)),
            forecast = tiedForecast
        )
        val out = planningService.evaluate(req) as ActivityPlanningOutcome.Evaluated
        assertEquals(ExposureComparisonVerdict.UNIFORM_CONDITIONS_TIED, out.verdict)
        assertEquals(3, out.lowestExposureDepartures.size)
        assertTrue(out.completeCandidatesRanked.all { it.rank == 1 && it.isTiedForLowestExposure })
    }

    @Test
    fun `sub-hourly windows, location mismatch, and stale forecasts are handled deterministically`() {
        val forecast = TestFixtures.buildHourlyForecast(
            startHour = baseTime,
            hourlySpecs = listOf(
                Triple(29.0, 60.0, 6.0), // 12:00Z
                Triple(30.0, 60.0, 7.0), // 13:00Z
                Triple(31.0, 60.0, 7.0)  // 14:00Z
            )
        )

        // 12:30Z for 90 min -> [12:30Z, 14:00Z) spans 12:00Z and 13:00Z (2 hours), NOT 14:00Z
        val subHourReq = ActivityPlanningRequest(
            location = TestFixtures.IBADAN,
            activityCategory = ActivityCategory.WALKING,
            plannedDuration = Duration.ofMinutes(90),
            earliestDeparture = baseTime.plus(Duration.ofMinutes(30)),
            latestDeparture = baseTime.plus(Duration.ofMinutes(30)),
            forecast = forecast
        )
        val subHourOut = planningService.evaluate(subHourReq) as ActivityPlanningOutcome.Evaluated
        assertEquals(2, subHourOut.completeCandidatesRanked.single().conditions.coveredHourCount)

        // Location mismatch: Lagos (~114 km away) against Ibadan forecast -> InsufficientData
        val mismatchedLocationReq = subHourReq.copy(location = TestFixtures.LAGOS)
        val mismatchOut = planningService.evaluate(mismatchedLocationReq)
        assertTrue(mismatchOut is ActivityPlanningOutcome.InsufficientData)
        assertTrue(mismatchOut.reason.contains("differ", ignoreCase = true))

        // Stale forecast (advance clock by 8 hours > 6h forecast max acceptable age) -> InsufficientData
        val lateClock = MutableClock(baseTime.plus(Duration.ofHours(8)))
        val lateService = ActivityPlanningService(HeatAssessmentEngine(lateClock), clock = lateClock)
        val staleOut = lateService.evaluate(subHourReq)
        assertTrue(staleOut is ActivityPlanningOutcome.InsufficientData)
        assertTrue(staleOut.reason.contains("stale", ignoreCase = true))
    }
}
