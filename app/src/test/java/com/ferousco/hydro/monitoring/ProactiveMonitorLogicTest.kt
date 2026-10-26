package com.ferousco.hydro.monitoring

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.model.DataAcquisitionMethod
import com.ferousco.hydro.core.model.DataQualityAndFreshness
import com.ferousco.hydro.core.model.DeliveryOrigin
import com.ferousco.hydro.core.model.EnvironmentalDataSource
import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HourlyForecastPoint
import com.ferousco.hydro.core.model.IrradianceUnit
import com.ferousco.hydro.core.model.MeasuredQuantity
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.model.PercentageUnit
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.UvIndexUnit
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.WeatherForecast
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The worker's assembly rules are pure, so they are tested here rather than through WorkManager.
 *
 * These tests pin the two properties that matter operationally: the near-term window never reaches
 * beyond six hours, and the peak is deterministic so a retry cannot produce a different alert.
 */
class ProactiveMonitorLogicTest {

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val zone = ZoneId.of("Africa/Lagos")
    private val location = GeoLocation(
        latitude = 7.3775,
        longitude = 3.947,
        elevationMeters = 230.0,
        timeZone = zone,
        label = "Ibadan"
    )
    private val engine = HeatAssessmentEngine(Clock.fixed(now, ZoneOffset.UTC))

    private val forecastSource = EnvironmentalDataSource(
        providerId = "open-meteo",
        providerName = "Open-Meteo",
        endpointOrDataset = "Weather Forecast API (/v1/forecast hourly)",
        acquisitionMethod = DataAcquisitionMethod.NUMERICAL_WEATHER_FORECAST,
        modelInfo = "best_match",
        license = EnvironmentalDataSource.OPEN_METEO_CC_BY_LICENSE,
        attribution = EnvironmentalDataSource.OPEN_METEO_ATTRIBUTION,
        resolvedGridLocation = location
    )

    // ------------------------------------------------------------------
    // Builders
    // ------------------------------------------------------------------

    private fun variables(
        validAt: Instant,
        airTempC: Double?,
        rhPercent: Double?
    ): EnvironmentalSnapshotVariables {
        fun <U : com.ferousco.hydro.core.model.PhysicalUnit> available(
            value: Double?,
            unit: U
        ): FieldAvailability<U> = if (value == null) {
            FieldAvailability.Unavailable(
                com.ferousco.hydro.core.model.UnavailableReason.NULL_IN_PROVIDER_RESPONSE
            )
        } else {
            FieldAvailability.Available(
                MeasuredQuantity(value, unit, validAt, location, forecastSource, ValueNature.FORECAST)
            )
        }

        return EnvironmentalSnapshotVariables(
            airTemperature = available(airTempC, TemperatureUnit.CELSIUS),
            relativeHumidity = available(rhPercent, PercentageUnit.PERCENT),
            windSpeed = available(2.5, SpeedUnit.METERS_PER_SECOND),
            feelsLikeTemperature = available(airTempC?.plus(2.0), TemperatureUnit.CELSIUS),
            uvIndex = available(2.0, UvIndexUnit.WHO_INDEX),
            solarRadiation = available(170.0, IrradianceUnit.WATTS_PER_SQUARE_METER),
            isDaylight = true,
            wmoWeatherCode = 1
        )
    }

    private fun forecast(specs: List<Pair<Double?, Double?>>): WeatherForecast {
        val points = specs.mapIndexed { index, (tempC, rh) ->
            val validAt = now.plus(Duration.ofHours(index.toLong()))
            HourlyForecastPoint(
                validAt = validAt,
                localDateTime = validAt.atZone(zone),
                issuedOrRetrievedAt = now,
                location = location,
                variables = variables(validAt, tempC, rh),
                source = forecastSource
            )
        }
        val quality = DataQualityAndFreshness.evaluateForecast(
            retrievedAt = now,
            evaluatedAt = now,
            anchorTime = points.first().validAt,
            deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
            completenessRatio = points.map { it.variables.completenessRatio() }.average()
        )
        return WeatherForecast(
            requestedLocation = location,
            effectiveLocation = location,
            timeZone = zone,
            issuedOrRetrievedAt = now,
            hourly = points,
            source = forecastSource,
            quality = quality
        )
    }

    // ------------------------------------------------------------------
    // Near-term window
    // ------------------------------------------------------------------

    @Test
    fun `near-term window includes the horizon edge and excludes later hours`() {
        val specs = List(9) { index ->
            when (index) {
                6 -> 34.0 to 60.0 // exactly +6h, inside the window
                7, 8 -> 38.0 to 70.0 // beyond the window, must be ignored
                else -> 28.0 to 55.0
            }
        }

        val assessments = ProactiveMonitorLogic.nearTermAssessments(
            forecast = forecast(specs),
            assessmentEngine = engine,
            evaluatedAt = now
        )

        assertEquals(7, assessments.size)
        assertTrue(assessments.none { it.validAt.isAfter(now.plus(ProactiveMonitorLogic.NEAR_TERM_HORIZON)) })
    }

    @Test
    fun `unsupported forecast hours are dropped instead of guessed`() {
        val specs = listOf(
            30.0 to 60.0,
            33.0 to null, // humidity missing -> no heat index
            30.0 to 60.0
        )

        val assessments = ProactiveMonitorLogic.nearTermAssessments(
            forecast = forecast(specs),
            assessmentEngine = engine,
            evaluatedAt = now
        )

        assertEquals(2, assessments.size)
    }

    @Test
    fun `past hours are not evaluated`() {
        val specs = listOf(34.0 to 60.0, 30.0 to 55.0, 30.0 to 55.0, 30.0 to 55.0)

        val assessments = ProactiveMonitorLogic.nearTermAssessments(
            forecast = forecast(specs),
            assessmentEngine = engine,
            evaluatedAt = now.plus(Duration.ofMinutes(30))
        )

        assertEquals(3, assessments.size)
        assertEquals(now.plus(Duration.ofHours(1)), assessments.first().validAt)
    }

    // ------------------------------------------------------------------
    // Peak selection
    // ------------------------------------------------------------------

    @Test
    fun `peak prefers the worst category even when another hour is hotter but milder`() {
        val assessments = ProactiveMonitorLogic.nearTermAssessments(
            forecast = forecast(
                listOf(
                    30.0 to 60.0, // Extreme Caution
                    34.0 to 60.0, // Danger
                    35.0 to 45.0, // hotter dry air, lower heat index
                    31.0 to 60.0
                )
            ),
            assessmentEngine = engine,
            evaluatedAt = now
        )

        val peak = ProactiveMonitorLogic.peak(assessments)

        assertEquals(NwsHeatIndexCategory.DANGER, peak?.category)
        assertEquals(now.plus(Duration.ofHours(1)), peak?.validAt)
    }

    @Test
    fun `peak is deterministic on ties and picks the earlier hour`() {
        val assessments = ProactiveMonitorLogic.nearTermAssessments(
            forecast = forecast(listOf(33.0 to 60.0, 33.0 to 60.0, 33.0 to 60.0)),
            assessmentEngine = engine,
            evaluatedAt = now
        )

        val first = ProactiveMonitorLogic.peak(assessments)
        val second = ProactiveMonitorLogic.peak(assessments.reversed())

        assertEquals(first?.validAt, second?.validAt)
        assertEquals(now, first?.validAt)
    }

    @Test
    fun `peak is null when nothing is supported`() {
        assertNull(ProactiveMonitorLogic.peak(emptyList()))
    }

    // ------------------------------------------------------------------
    // Previous-category storage
    // ------------------------------------------------------------------

    @Test
    fun `category key is scoped by grid cell and local date`() {
        val today = ProactiveMonitorLogic.forecastCategoryKey(location, now, zone)
        val tomorrow = ProactiveMonitorLogic.forecastCategoryKey(
            location,
            now.plus(Duration.ofDays(1)),
            zone
        )
        val elsewhere = ProactiveMonitorLogic.forecastCategoryKey(
            location.copy(latitude = 6.5244, longitude = 3.3792, label = "Lagos"),
            now,
            zone
        )

        assertTrue(today.startsWith("forecast_category:"))
        assertTrue(today != tomorrow)
        assertTrue(today != elsewhere)
        // Lagos is +01:00 as well, so the date component must stay comparable.
        assertTrue(today.endsWith("2026-10-26"))
        assertTrue(tomorrow.endsWith("2026-10-27"))
    }

    @Test
    fun `category parsing tolerates blank and unknown stored values`() {
        assertNull(ProactiveMonitorLogic.parseCategory(null))
        assertNull(ProactiveMonitorLogic.parseCategory("   "))
        assertNull(ProactiveMonitorLogic.parseCategory("NOT_A_CATEGORY"))
        assertEquals(
            NwsHeatIndexCategory.EXTREME_CAUTION,
            ProactiveMonitorLogic.parseCategory(" EXTREME_CAUTION ")
        )
    }
}
