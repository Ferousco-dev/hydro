package com.ferousco.hydro.core

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HistoricalWeatherSeries
import com.ferousco.hydro.core.model.WeatherForecast
import com.ferousco.hydro.core.provider.CachePolicy
import com.ferousco.hydro.core.provider.WeatherRepository
import com.ferousco.hydro.core.provider.WeatherRetrievalEnvelope
import com.ferousco.hydro.core.recommendation.RecommendationService
import com.ferousco.hydro.core.recommendation.RecommendationUrgency
import com.ferousco.hydro.core.recommendation.RecommendedActionKind
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The service is the one place where retrieval, assessment, and recommendation are joined. These
 * tests pin the honesty boundaries: a missing observation fails, a missing forecast degrades, stale
 * data never becomes an all-clear, and the lookahead is not silently widened.
 */
class RecommendationServiceTest {

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val zone = ZoneId.of("Africa/Lagos")
    private val clock = MutableClock(now)
    private val assessmentEngine = HeatAssessmentEngine(clock)
    private val location = TestFixtures.IBADAN

    private fun envelope(
        observation: EnvironmentalObservation
    ): WeatherRetrievalEnvelope<EnvironmentalObservation> = WeatherRetrievalEnvelope(
        data = observation,
        deliveryOrigin = observation.quality.deliveryOrigin,
        freshness = observation.quality
    )

    private fun envelope(forecast: WeatherForecast): WeatherRetrievalEnvelope<WeatherForecast> =
        WeatherRetrievalEnvelope(
            data = forecast,
            deliveryOrigin = forecast.quality.deliveryOrigin,
            freshness = forecast.quality
        )

    private class StubWeatherRepository(
        var observation: EnvironmentalObservation? = null,
        var forecast: WeatherForecast? = null,
        var observationError: EngineError? = null,
        var forecastError: EngineError? = null
    ) : WeatherRepository {

        override suspend fun getCurrentObservation(
            location: GeoLocation,
            cachePolicy: CachePolicy
        ): EngineResult<WeatherRetrievalEnvelope<EnvironmentalObservation>> {
            observationError?.let { return EngineResult.Failure(it) }
            val value = observation
                ?: return EngineResult.Failure(EngineError.NetworkUnavailable("No observation configured."))
            return EngineResult.Success(
                WeatherRetrievalEnvelope(
                    data = value,
                    deliveryOrigin = value.quality.deliveryOrigin,
                    freshness = value.quality
                )
            )
        }

        override suspend fun getHourlyForecast(
            location: GeoLocation,
            forecastHours: Int,
            cachePolicy: CachePolicy
        ): EngineResult<WeatherRetrievalEnvelope<WeatherForecast>> {
            forecastError?.let { return EngineResult.Failure(it) }
            val value = forecast
                ?: return EngineResult.Failure(EngineError.NetworkUnavailable("No forecast configured."))
            return EngineResult.Success(
                WeatherRetrievalEnvelope(
                    data = value,
                    deliveryOrigin = value.quality.deliveryOrigin,
                    freshness = value.quality
                )
            )
        }

        override suspend fun getHistoricalWeather(
            location: GeoLocation,
            startDate: LocalDate,
            endDate: LocalDate,
            cachePolicy: CachePolicy
        ): EngineResult<WeatherRetrievalEnvelope<HistoricalWeatherSeries>> =
            EngineResult.Failure(EngineError.NetworkUnavailable("Not used in this test."))

        override fun clearCache() = Unit
    }

    private fun service(repository: WeatherRepository): RecommendationService =
        RecommendationService(
            weatherRepository = repository,
            assessmentEngine = assessmentEngine,
            clock = clock
        )

    private fun forecast(
        startHour: Instant,
        specs: List<Triple<Double?, Double?, Double?>>
    ): WeatherForecast = TestFixtures.buildHourlyForecast(
        startHour = startHour,
        hourlySpecs = specs,
        retrievedAt = now,
        evaluatedAt = now
    )

    // ------------------------------------------------------------------
    // Assembly
    // ------------------------------------------------------------------

    @Test
    fun `combines current conditions and forecast into a cooler window`() = runBlocking {
        val observation = TestFixtures.buildObservation(
            observedAt = now,
            retrievedAt = now,
            evaluatedAt = now,
            airTempC = 31.0,
            rhPercent = 60.0,
            uvIndex = 2.0
        )
        val repository = StubWeatherRepository(
            observation = observation,
            forecast = forecast(
                startHour = now.plus(Duration.ofHours(2)),
                specs = listOf(
                    Triple(30.0, 60.0, 2.0),
                    Triple(26.0, 50.0, 2.0),
                    Triple(26.0, 50.0, 2.0),
                    Triple(26.0, 50.0, 2.0)
                )
            )
        )

        val recommendation = service(repository).forLocation(
            location = location,
            activityCategory = ActivityCategory.RUNNING,
            plannedDuration = Duration.ofHours(2)
        ).getOrNull()

        val value = assertNotNull(recommendation)
        assertEquals(RecommendationUrgency.HIGH, value.urgency)
        assertTrue(value.actions.any { it.kind == RecommendedActionKind.LIMIT_EXERTION })
        assertTrue(value.actions.any { it.kind == RecommendedActionKind.SHIFT_WINDOW })
        val window = assertNotNull(value.bestWindow)
        assertEquals(now.plus(Duration.ofHours(3)), window.start)
        assertEquals(2, window.hoursEvaluated)
    }

    @Test
    fun `a failed forecast degrades to current-condition guidance`() = runBlocking {
        val observation = TestFixtures.buildObservation(
            observedAt = now,
            retrievedAt = now,
            evaluatedAt = now,
            airTempC = 35.0,
            rhPercent = 60.0
        )
        val repository = StubWeatherRepository(
            observation = observation,
            forecastError = EngineError.NetworkUnavailable("Forecast endpoint offline")
        )

        val recommendation = service(repository)
            .forLocation(location = location)
            .getOrNull()

        val value = assertNotNull(recommendation)
        assertEquals(RecommendationUrgency.CRITICAL, value.urgency)
        assertNull(value.bestWindow)
        assertTrue(
            value.limitations.any { it.contains("No usable forecast coverage") },
            "A failed forecast must be disclosed, not silently treated as calm weather."
        )
    }

    @Test
    fun `a failed current observation is a failure, not a guessed recommendation`() = runBlocking {
        val repository = StubWeatherRepository(
            observationError = EngineError.NetworkUnavailable("Observation endpoint offline"),
            forecast = forecast(
                startHour = now.plus(Duration.ofHours(1)),
                specs = listOf(Triple(26.0, 50.0, 2.0))
            )
        )

        val result = service(repository).forLocation(location = location)

        assertTrue(result is EngineResult.Failure)
        assertTrue(result.error is EngineError.NetworkUnavailable)
    }

    @Test
    fun `a stale observation becomes unknown and asks for a refresh`() = runBlocking {
        val observation = TestFixtures.buildObservation(
            observedAt = now.minus(Duration.ofHours(3)),
            retrievedAt = now.minus(Duration.ofHours(3)),
            evaluatedAt = now,
            airTempC = 26.0,
            rhPercent = 50.0,
            uvIndex = 1.0
        )
        val repository = StubWeatherRepository(observation = observation)

        val recommendation = service(repository).forLocation(location = location).getOrNull()

        val value = assertNotNull(recommendation)
        assertEquals(RecommendationUrgency.UNKNOWN, value.urgency)
        assertFalse(value.isCurrentData)
        assertTrue(value.actions.any { it.kind == RecommendedActionKind.REFRESH_DATA })
    }

    @Test
    fun `lookahead is not silently widened`() = runBlocking {
        val observation = TestFixtures.buildObservation(
            observedAt = now,
            retrievedAt = now,
            evaluatedAt = now,
            airTempC = 26.0,
            rhPercent = 50.0,
            uvIndex = 1.0
        )
        val repository = StubWeatherRepository(
            observation = observation,
            forecast = forecast(
                startHour = now.plus(Duration.ofHours(20)),
                specs = listOf(Triple(36.0, 60.0, 2.0))
            )
        )

        val recommendation = service(repository).forLocation(
            location = location,
            lookahead = Duration.ofHours(6)
        ).getOrNull()

        val value = assertNotNull(recommendation)
        assertEquals(RecommendationUrgency.LOW, value.urgency)
        assertEquals("No heat risk right now", value.headline)
        assertNull(value.bestWindow)
    }

    @Test
    fun `activity and duration are reflected in the recommendation`() = runBlocking {
        val observation = TestFixtures.buildObservation(
            observedAt = now,
            retrievedAt = now,
            evaluatedAt = now,
            airTempC = 31.0,
            rhPercent = 60.0
        )
        val repository = StubWeatherRepository(observation = observation)

        val recommendation = service(repository).forLocation(
            location = location,
            activityCategory = ActivityCategory.CYCLING,
            plannedDuration = Duration.ofMinutes(90)
        ).getOrNull()

        val value = assertNotNull(recommendation)
        val tailored = value.actions.firstOrNull {
            it.kind == RecommendedActionKind.ACTIVITY_ADJUSTMENT
        }
        assertNotNull(tailored)
        assertTrue(tailored.title.contains("cycling"))
        assertTrue(tailored.detail.contains("90-minute"))
    }

    @Test
    fun `negative lookahead is rejected before any network call`() = runBlocking {
        val repository = StubWeatherRepository()
        val result = runCatching {
            service(repository).forLocation(
                location = location,
                lookahead = Duration.ofMinutes(-5)
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
