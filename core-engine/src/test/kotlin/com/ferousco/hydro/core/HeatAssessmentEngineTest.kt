package com.ferousco.hydro.core

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.model.DeliveryOrigin
import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.FreshnessState
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.MeasuredQuantity
import com.ferousco.hydro.core.model.MissingVariableRole
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.model.PercentageUnit
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.UnavailableReason
import com.ferousco.hydro.core.model.UnsupportedAssessmentReason
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.WhoUvCategory
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeatAssessmentEngineTest {

    private val fixedNow = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = MutableClock(fixedNow)
    private val engine = HeatAssessmentEngine(clock = clock)

    @Test
    fun `normal conditions below 80F use Steadman simple formula and classify BELOW_CAUTION_THRESHOLD`() {
        val obs = TestFixtures.buildObservation(
            observedAt = fixedNow.minusSeconds(300),
            retrievedAt = fixedNow,
            airTempC = 24.0, // 75.2°F
            rhPercent = 50.0,
            uvIndex = 2.0
        )
        val res = engine.assessObservation(obs)
        assertTrue(res is HeatAssessment.Supported)
        assertEquals(NwsHeatIndexCategory.BELOW_CAUTION_THRESHOLD, res.category)
        assertEquals(HeatAssessmentEngine.BRANCH_STEADMAN_SIMPLE, res.formulaBranchUsed)
        assertEquals(WhoUvCategory.LOW, res.uvCategory)
        assertEquals(ValueNature.OBSERVED, res.inputNature)
        assertEquals(ValueNature.DERIVED, res.heatIndexCelsius.nature)
    }

    @Test
    fun `high-temperature low-humidity conditions apply official NWS dry-air adjustment`() {
        // 38.0°C = 100.4°F, RH = 10% (<13% and 80..112°F -> low humidity adjustment subtracted)
        val obs = TestFixtures.buildObservation(
            observedAt = fixedNow,
            retrievedAt = fixedNow,
            airTempC = 38.0,
            rhPercent = 10.0,
            uvIndex = 9.5
        )
        val res = engine.assessObservation(obs) as HeatAssessment.Supported
        assertEquals(HeatAssessmentEngine.BRANCH_ROTHFUSZ_LOW_RH_ADJ, res.formulaBranchUsed)
        assertEquals(NwsHeatIndexCategory.EXTREME_CAUTION, res.category)
        assertEquals(WhoUvCategory.VERY_HIGH, res.uvCategory)
        // At 100.4°F and 10% RH, dry-air Heat Index is lower than dry-bulb (~94.5°F / ~34.7°C)
        assertTrue(res.heatIndexFahrenheit in 93.5..96.0, "Got ${res.heatIndexFahrenheit}°F")
    }

    @Test
    fun `high-humidity warm conditions apply official NWS high-humidity adjustment and tropical heat reaches DANGER and EXTREME_DANGER`() {
        // 28.0°C = 82.4°F, RH = 90% (>85% and 80..87°F -> high humidity adjustment added)
        val warmHumid = TestFixtures.buildObservation(
            observedAt = fixedNow,
            retrievedAt = fixedNow,
            airTempC = 28.0,
            rhPercent = 90.0,
            uvIndex = 4.0
        )
        val resAdj = engine.assessObservation(warmHumid) as HeatAssessment.Supported
        assertEquals(HeatAssessmentEngine.BRANCH_ROTHFUSZ_HIGH_RH_ADJ, resAdj.formulaBranchUsed)
        assertEquals(NwsHeatIndexCategory.EXTREME_CAUTION, resAdj.category)
        assertEquals(WhoUvCategory.MODERATE, resAdj.uvCategory)

        // 34.0°C (93.2°F) with 70% RH -> NWS Heat Index ~119°F -> DANGER
        val dangerObs = TestFixtures.buildObservation(
            observedAt = fixedNow,
            retrievedAt = fixedNow,
            airTempC = 34.0,
            rhPercent = 70.0,
            uvIndex = 11.5
        )
        val resDanger = engine.assessObservation(dangerObs) as HeatAssessment.Supported
        assertEquals(NwsHeatIndexCategory.DANGER, resDanger.category)
        assertEquals(WhoUvCategory.EXTREME, resDanger.uvCategory)

        // 38.0°C (100.4°F) with 65% RH -> NWS Heat Index ~137°F -> EXTREME_DANGER
        val extremeObs = TestFixtures.buildObservation(
            observedAt = fixedNow,
            retrievedAt = fixedNow,
            airTempC = 38.0,
            rhPercent = 65.0
        )
        val resExtreme = engine.assessObservation(extremeObs) as HeatAssessment.Supported
        assertEquals(NwsHeatIndexCategory.EXTREME_DANGER, resExtreme.category)
    }

    @Test
    fun `missing required variables return Incomplete without fabricating an index while missing optional variables remain Supported`() {
        val missingTemp = TestFixtures.buildObservation(airTempC = null, rhPercent = 70.0)
        val incTemp = engine.assessObservation(missingTemp)
        assertTrue(incTemp is HeatAssessment.Incomplete)
        assertTrue(incTemp.missingInformation.any { it.variableName == "air_temperature" && it.role == MissingVariableRole.REQUIRED_FOR_PRIMARY_INDEX })

        val missingRh = TestFixtures.buildObservation(airTempC = 33.0, rhPercent = null)
        val incRh = engine.assessObservation(missingRh)
        assertTrue(incRh is HeatAssessment.Incomplete)
        assertTrue(incRh.missingInformation.any { it.variableName == "relative_humidity" && it.role == MissingVariableRole.REQUIRED_FOR_PRIMARY_INDEX })

        val missingOptionalOnly = TestFixtures.buildObservation(
            airTempC = 32.0,
            rhPercent = 60.0,
            windMs = null,
            uvIndex = null,
            solarWm2 = null
        )
        val supported = engine.assessObservation(missingOptionalOnly)
        assertTrue(supported is HeatAssessment.Supported)
        assertNull(supported.uvCategory, "UV category must be null when UV index is unavailable")
        val missingNames = supported.missingInformation.map { it.variableName }.toSet()
        assertTrue("uv_index" in missingNames)
        assertTrue("solar_radiation" in missingNames)
        assertTrue("wind_speed" in missingNames)
    }

    @Test
    fun `invalid values, out-of-envelope temperatures, and mixed provenance return Unsupported`() {
        // Out of terrestrial Steadman/Rothfusz envelope (62°C)
        val outOfEnvelope = TestFixtures.buildObservation(airTempC = 62.0, rhPercent = 40.0)
        val resOut = engine.assessObservation(outOfEnvelope)
        assertTrue(resOut is HeatAssessment.Unsupported)
        assertEquals(UnsupportedAssessmentReason.OUTSIDE_METHOD_VALIDITY_RANGE, resOut.reason)

        // Invalid RH (110%)
        val invalidRhVars = EnvironmentalSnapshotVariables(
            airTemperature = FieldAvailability.Available(
                MeasuredQuantity(32.0, TemperatureUnit.CELSIUS, fixedNow, TestFixtures.IBADAN, TestFixtures.TEST_OBSERVATION_SOURCE, ValueNature.OBSERVED)
            ),
            relativeHumidity = FieldAvailability.Available(
                MeasuredQuantity(110.0, PercentageUnit.PERCENT, fixedNow, TestFixtures.IBADAN, TestFixtures.TEST_OBSERVATION_SOURCE, ValueNature.OBSERVED)
            )
        )
        val obsTemplate = TestFixtures.buildObservation()
        val resInvalid = engine.assessSnapshot(
            location = TestFixtures.IBADAN,
            validAt = fixedNow,
            inputNature = ValueNature.OBSERVED,
            inputSource = TestFixtures.TEST_OBSERVATION_SOURCE,
            variables = invalidRhVars,
            quality = obsTemplate.quality
        )
        assertTrue(resInvalid is HeatAssessment.Unsupported)
        assertEquals(UnsupportedAssessmentReason.INVALID_INPUT_VALUES, resInvalid.reason)

        // Mixed OBSERVED + FORECAST provenance inside one snapshot
        val mixedVars = EnvironmentalSnapshotVariables(
            airTemperature = FieldAvailability.Available(
                MeasuredQuantity(32.0, TemperatureUnit.CELSIUS, fixedNow, TestFixtures.IBADAN, TestFixtures.TEST_OBSERVATION_SOURCE, ValueNature.OBSERVED)
            ),
            relativeHumidity = FieldAvailability.Available(
                MeasuredQuantity(65.0, PercentageUnit.PERCENT, fixedNow, TestFixtures.IBADAN, TestFixtures.TEST_FORECAST_SOURCE, ValueNature.FORECAST)
            )
        )
        val resMixed = engine.assessSnapshot(
            location = TestFixtures.IBADAN,
            validAt = fixedNow,
            inputNature = ValueNature.OBSERVED,
            inputSource = TestFixtures.TEST_OBSERVATION_SOURCE,
            variables = mixedVars,
            quality = obsTemplate.quality
        )
        assertTrue(resMixed is HeatAssessment.Unsupported)
        assertEquals(UnsupportedAssessmentReason.MIXED_INCOMPATIBLE_PROVENANCE, resMixed.reason)
    }

    @Test
    fun `stale inputs are rejected by default and explicitly flagged when allowStaleInput is enabled`() {
        val oldRetrievedAt = fixedNow.minus(Duration.ofHours(3))
        val staleObs = TestFixtures.buildObservation(
            observedAt = oldRetrievedAt,
            retrievedAt = oldRetrievedAt,
            evaluatedAt = fixedNow,
            deliveryOrigin = DeliveryOrigin.LOCAL_CACHE
        )
        assertEquals(FreshnessState.STALE, staleObs.quality.freshnessState)

        val strictResult = engine.assessObservation(staleObs, allowStaleInput = false)
        assertTrue(strictResult is HeatAssessment.Unsupported)
        assertEquals(UnsupportedAssessmentReason.STALE_INPUT_DATA, strictResult.reason)

        val permissiveResult = engine.assessObservation(staleObs, allowStaleInput = true)
        assertTrue(permissiveResult is HeatAssessment.Supported)
        assertEquals(FreshnessState.STALE, permissiveResult.quality.freshnessState)
        assertTrue(permissiveResult.limitations.any { it.contains("STALE") })
    }

    @Test
    fun `NWS Heat Index and WHO UV threshold boundaries classify accurately`() {
        assertEquals(NwsHeatIndexCategory.BELOW_CAUTION_THRESHOLD, NwsHeatIndexCategory.fromHeatIndexFahrenheit(79.999))
        assertEquals(NwsHeatIndexCategory.CAUTION, NwsHeatIndexCategory.fromHeatIndexFahrenheit(80.0))
        assertEquals(NwsHeatIndexCategory.CAUTION, NwsHeatIndexCategory.fromHeatIndexFahrenheit(89.999))
        assertEquals(NwsHeatIndexCategory.EXTREME_CAUTION, NwsHeatIndexCategory.fromHeatIndexFahrenheit(90.0))
        assertEquals(NwsHeatIndexCategory.EXTREME_CAUTION, NwsHeatIndexCategory.fromHeatIndexFahrenheit(102.999))
        assertEquals(NwsHeatIndexCategory.DANGER, NwsHeatIndexCategory.fromHeatIndexFahrenheit(103.0))
        assertEquals(NwsHeatIndexCategory.DANGER, NwsHeatIndexCategory.fromHeatIndexFahrenheit(124.999))
        assertEquals(NwsHeatIndexCategory.EXTREME_DANGER, NwsHeatIndexCategory.fromHeatIndexFahrenheit(125.0))

        assertEquals(WhoUvCategory.LOW, WhoUvCategory.fromUvIndex(2.99))
        assertEquals(WhoUvCategory.MODERATE, WhoUvCategory.fromUvIndex(3.0))
        assertEquals(WhoUvCategory.MODERATE, WhoUvCategory.fromUvIndex(5.99))
        assertEquals(WhoUvCategory.HIGH, WhoUvCategory.fromUvIndex(6.0))
        assertEquals(WhoUvCategory.VERY_HIGH, WhoUvCategory.fromUvIndex(8.0))
        assertEquals(WhoUvCategory.EXTREME, WhoUvCategory.fromUvIndex(11.0))
    }

    @Test
    fun `forecast vs observation distinction and deterministic reproducibility hold across repeated runs`() {
        val obs = TestFixtures.buildObservation(airTempC = 33.5, rhPercent = 66.0)
        val forecast = TestFixtures.buildHourlyForecast(
            startHour = fixedNow,
            hourlySpecs = listOf(Triple(33.5, 66.0, 8.5))
        )

        val obsAssessment = engine.assessObservation(obs) as HeatAssessment.Supported
        val fcstAssessment = engine.assessForecastPoint(forecast.hourly.single(), forecast.quality) as HeatAssessment.Supported

        assertEquals(ValueNature.OBSERVED, obsAssessment.inputNature)
        assertEquals(ValueNature.FORECAST, fcstAssessment.inputNature)
        assertTrue(obsAssessment.explanation.contains("Observed"))
        assertTrue(fcstAssessment.explanation.contains("Forecast"))
        // Identical numerical Heat Index from identical physical values
        assertEquals(obsAssessment.heatIndexCelsius.value, fcstAssessment.heatIndexCelsius.value, 1e-12)

        // Reproducibility check across 50 invocations
        val baseline = engine.assessObservation(obs)
        repeat(50) {
            assertEquals(baseline, engine.assessObservation(obs))
        }
        assertNotNull(obsAssessment.primaryMethod.citation)
    }

    @Test
    fun `Rothfusz equation matches published NOAA NWS reference Heat Index table values`() {
        // Official NOAA / NWS Heat Index chart reference points (weather.gov/ffc/hichart & NWS Heat Safety brochure)
        val nwsReferenceCases = listOf(
            Triple(80.0, 40.0, 80L),
            Triple(84.0, 70.0, 90L),
            Triple(88.0, 70.0, 100L),
            Triple(92.0, 70.0, 112L),
            Triple(95.0, 50.0, 105L),
            Triple(96.0, 50.0, 108L),
            Triple(100.0, 40.0, 109L),
            Triple(80.0, 100.0, 89L)
        )
        for ((tempF, rh, expectedRoundedF) in nwsReferenceCases) {
            val computed = HeatAssessmentEngine.computeNwsHeatIndexFahrenheit(tempF, rh)
            val rounded = kotlin.math.round(computed.heatIndexFahrenheit).toLong()
            assertEquals(
                expectedRoundedF,
                rounded,
                "Mismatch vs NWS chart at T=${tempF}°F, RH=${rh}% (got ${computed.heatIndexFahrenheit}°F)"
            )
        }
    }
}
