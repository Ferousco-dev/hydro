package com.ferousco.hydro.core

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.model.DeliveryOrigin
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.FreshnessState
import com.ferousco.hydro.core.model.ObservationKind
import com.ferousco.hydro.core.model.QualityFlag
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.UnavailableReason
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.inUnitOrNull
import com.ferousco.hydro.core.model.rawValueOrNull
import com.ferousco.hydro.core.provider.CachePolicy
import com.ferousco.hydro.core.provider.DefaultWeatherRepository
import com.ferousco.hydro.core.provider.openmeteo.OpenMeteoConfig
import com.ferousco.hydro.core.provider.openmeteo.OpenMeteoWeatherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenMeteoProviderAndRepositoryTest {

    @Test
    fun `OpenMeteo current observation normalizes units, preserves provenance, and flags model assimilation`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-26T12:00:00Z"))
        val transport = FakeHttpTransport()
        // Return Fahrenheit + km/h to verify unit normalization into Celsius + m/s
        transport.enqueueSuccess(
            body = """
            {
              "latitude": 7.38,
              "longitude": 3.95,
              "elevation": 228.0,
              "utc_offset_seconds": 3600,
              "timezone": "Africa/Lagos",
              "current_units": {
                "time": "unixtime",
                "interval": "seconds",
                "temperature_2m": "°F",
                "relative_humidity_2m": "%",
                "apparent_temperature": "°F",
                "wind_speed_10m": "km/h",
                "uv_index": "",
                "shortwave_radiation": "W/m²"
              },
              "current": {
                "time": 1793015100,
                "interval": 900,
                "temperature_2m": 95.0,
                "relative_humidity_2m": 60,
                "apparent_temperature": 104.0,
                "wind_speed_10m": 18.0,
                "uv_index": null,
                "shortwave_radiation": 710.5,
                "is_day": 1,
                "weather_code": 2
              }
            }
            """.trimIndent()
        )

        val provider = OpenMeteoWeatherProvider(
            config = OpenMeteoConfig(),
            transport = transport,
            clock = clock
        )
        val result = provider.fetchCurrentObservation(TestFixtures.IBADAN)
        assertTrue(result is EngineResult.Success)
        val obs = result.value

        // Verify query only asks for required variables and uses HTTPS + unixtime + no apikey
        val reqUri = transport.recordedRequests.single().uri.toString()
        assertTrue(reqUri.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(reqUri.contains("timeformat=unixtime"))
        assertFalse(reqUri.contains("apikey", ignoreCase = true))
        assertFalse(reqUri.contains("soil_moisture"))

        // 95°F -> 35.0°C; 18 km/h -> 5.0 m/s
        assertEquals(35.0, obs.variables.airTemperature.inUnitOrNull(TemperatureUnit.CELSIUS)!!, 1e-6)
        assertEquals(60.0, obs.variables.relativeHumidity.rawValueOrNull()!!, 1e-6)
        assertEquals(40.0, obs.variables.feelsLikeTemperature.inUnitOrNull(TemperatureUnit.CELSIUS)!!, 1e-6)
        assertEquals(5.0, obs.variables.windSpeed.inUnitOrNull(SpeedUnit.METERS_PER_SECOND)!!, 1e-6)
        assertEquals(710.5, obs.variables.solarRadiation.rawValueOrNull()!!, 1e-6)

        // Null uv_index must be Unavailable, NEVER 0.0
        val uv = obs.variables.uvIndex
        assertTrue(uv is FieldAvailability.Unavailable)
        assertEquals(UnavailableReason.NULL_IN_PROVIDER_RESPONSE, uv.reason)
        assertNull(uv.rawValueOrNull())

        // Provenance and nature checks
        assertEquals(ObservationKind.MODEL_ASSIMILATED_CURRENT, obs.observationKind)
        assertEquals(ValueNature.OBSERVED, obs.variables.airTemperature.quantityOrNull()!!.nature)
        assertTrue(QualityFlag.MODEL_DERIVED_CURRENT_CONDITION in obs.quality.flags)
        assertTrue(QualityFlag.PARTIAL_VARIABLES_MISSING in obs.quality.flags)
        assertTrue(obs.source.license.contains("CC-BY 4.0"))
    }

    @Test
    fun `OpenMeteo hourly forecast preserves FORECAST nature, handles null and invalid elements, and rejects mismatched arrays`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-26T12:00:00Z"))
        val transport = FakeHttpTransport()
        transport.enqueueSuccess(
            body = """
            {
              "latitude": 7.50,
              "longitude": 4.05,
              "elevation": 240.0,
              "utc_offset_seconds": 3600,
              "timezone": "Africa/Lagos",
              "hourly_units": {
                "time": "iso8601",
                "temperature_2m": "°C",
                "relative_humidity_2m": "%",
                "apparent_temperature": "°C",
                "wind_speed_10m": "mph",
                "uv_index": "",
                "shortwave_radiation": "W/m²"
              },
              "hourly": {
                "time": ["2026-10-26T13:00", "2026-10-26T14:00"],
                "temperature_2m": [31.5, null],
                "relative_humidity_2m": [65.0, 145.0],
                "apparent_temperature": [35.2, 36.0],
                "wind_speed_10m": [10.0, 5.0],
                "uv_index": [8.0, 6.5],
                "shortwave_radiation": [780.0, 620.0],
                "is_day": [1, 1],
                "weather_code": [1, 2]
              }
            }
            """.trimIndent()
        )

        val provider = OpenMeteoWeatherProvider(transport = transport, clock = clock)
        val fcstResult = provider.fetchHourlyForecast(TestFixtures.IBADAN, forecastHours = 24)
        assertTrue(fcstResult is EngineResult.Success)
        val fcst = fcstResult.value
        assertEquals(2, fcst.hourly.size)

        // 2026-10-26T13:00 at utc_offset_seconds=3600 -> 12:00:00Z
        assertEquals(Instant.parse("2026-10-26T12:00:00Z"), fcst.hourly[0].validAt)
        assertEquals(ValueNature.FORECAST, fcst.hourly[0].variables.airTemperature.quantityOrNull()!!.nature)
        assertEquals(4.4704, fcst.hourly[0].variables.windSpeed.inUnitOrNull(SpeedUnit.METERS_PER_SECOND)!!, 1e-4)

        // Second hour had null temp and invalid 145% RH -> both explicitly Unavailable!
        val h1 = fcst.hourly[1].variables
        assertEquals(
            UnavailableReason.NULL_IN_PROVIDER_RESPONSE,
            (h1.airTemperature as FieldAvailability.Unavailable).reason
        )
        assertEquals(
            UnavailableReason.FAILED_VALIDATION,
            (h1.relativeHumidity as FieldAvailability.Unavailable).reason
        )
        assertTrue(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED in fcst.quality.flags)
        // Grid coordinates (7.50, 4.05) are ~17.8 km from Ibadan (7.3775, 3.9470) -> GRID_CELL_OFFSET_SIGNIFICANT
        assertTrue(QualityFlag.GRID_CELL_OFFSET_SIGNIFICANT in fcst.quality.flags)

        // Now enqueue a corrupted response with mismatched array lengths
        transport.enqueueSuccess(
            body = """
            {
              "latitude": 7.38,
              "longitude": 3.95,
              "utc_offset_seconds": 3600,
              "timezone": "Africa/Lagos",
              "hourly_units": { "time": "iso8601", "temperature_2m": "°C" },
              "hourly": {
                "time": ["2026-10-26T13:00", "2026-10-26T14:00"],
                "temperature_2m": [31.5]
              }
            }
            """.trimIndent()
        )
        val badResult = provider.fetchHourlyForecast(TestFixtures.IBADAN, forecastHours = 24)
        assertTrue(badResult.errorOrNull() is EngineError.InvalidResponse)
    }

    @Test
    fun `OpenMeteo historical archive omits uv_index from request and marks uvIndex Unavailable`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-26T12:00:00Z"))
        val transport = FakeHttpTransport()
        transport.enqueueSuccess(
            body = """
            {
              "latitude": 7.38,
              "longitude": 3.95,
              "utc_offset_seconds": 3600,
              "timezone": "Africa/Lagos",
              "hourly_units": {
                "time": "unixtime",
                "temperature_2m": "°C",
                "relative_humidity_2m": "%",
                "apparent_temperature": "°C",
                "wind_speed_10m": "m/s",
                "shortwave_radiation": "W/m²"
              },
              "hourly": {
                "time": [1792929600],
                "temperature_2m": [30.2],
                "relative_humidity_2m": [72.0],
                "apparent_temperature": [34.8],
                "wind_speed_10m": [2.1],
                "shortwave_radiation": [590.0],
                "is_day": [1],
                "weather_code": [1]
              }
            }
            """.trimIndent()
        )

        val provider = OpenMeteoWeatherProvider(transport = transport, clock = clock)
        val hist = provider.fetchHistoricalWeather(
            location = TestFixtures.IBADAN,
            startDate = LocalDate.of(2026, 10, 25),
            endDate = LocalDate.of(2026, 10, 25)
        )
        assertTrue(hist is EngineResult.Success)
        val reqUri = transport.recordedRequests.single().uri.toString()
        assertFalse(reqUri.contains("uv_index"), "Archive endpoint does not support hourly uv_index")

        val firstObs = hist.value.hourlyObservations.single()
        assertEquals(ObservationKind.HISTORICAL_REANALYSIS_OBSERVATION, firstObs.observationKind)
        assertEquals(
            UnavailableReason.NOT_PROVIDED_BY_SOURCE,
            (firstObs.variables.uvIndex as FieldAvailability.Unavailable).reason
        )

        // Future historical dates must fail validation before hitting network
        val futureAttempt = provider.fetchHistoricalWeather(
            location = TestFixtures.IBADAN,
            startDate = LocalDate.of(2026, 10, 27),
            endDate = LocalDate.of(2026, 10, 28)
        )
        assertTrue(futureAttempt.errorOrNull() is EngineError.InvalidInput)
    }

    @Test
    fun `OpenMeteo provider retries transient HTTP 503 once and respects coroutine cancellation`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-26T12:00:00Z"))
        val transport = FakeHttpTransport()
        transport.enqueueSuccess(statusCode = 503, body = "Service Unavailable")
        transport.enqueueSuccess(
            statusCode = 200,
            body = """
            {
              "latitude": 7.38,
              "longitude": 3.95,
              "utc_offset_seconds": 3600,
              "timezone": "Africa/Lagos",
              "current_units": { "time": "unixtime", "temperature_2m": "°C", "relative_humidity_2m": "%" },
              "current": { "time": 1793015100, "temperature_2m": 29.0, "relative_humidity_2m": 70.0 }
            }
            """.trimIndent()
        )

        val provider = OpenMeteoWeatherProvider(
            config = OpenMeteoConfig(maxRetryAttempts = 2, initialRetryBackoff = Duration.ofMillis(10)),
            transport = transport,
            clock = clock
        )
        val res = provider.fetchCurrentObservation(TestFixtures.IBADAN)
        assertTrue(res.isSuccess)
        assertEquals(2, transport.recordedRequests.size)

        // Non-transient 400 error JSON must NOT retry
        transport.enqueueSuccess(
            statusCode = 400,
            body = """{"error": true, "reason": "Invalid parameter"}"""
        )
        val badRes = provider.fetchCurrentObservation(TestFixtures.IBADAN)
        val err = badRes.errorOrNull() as EngineError.ProviderError
        assertFalse(err.isTransient)
        assertEquals("Invalid parameter", err.providerReason)
        assertEquals(3, transport.recordedRequests.size)

        // Cancellation test
        transport.enqueueDelayedSuccess(delayMs = 5_000L, body = "{}")
        val deferred = async { provider.fetchCurrentObservation(TestFixtures.IBADAN) }
        delay(30)
        deferred.cancel()
        assertFailsWith<CancellationException> { deferred.await() }
    }

    @Test
    fun `DefaultWeatherRepository never claims cached data is current and transitions freshness accurately`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-26T12:00:00Z"))
        val fakeProvider = FakeWeatherProvider()
        val liveObs = TestFixtures.buildObservation(
            observedAt = Instant.parse("2026-10-26T11:50:00Z"),
            retrievedAt = Instant.parse("2026-10-26T12:00:00Z")
        )
        fakeProvider.currentResult = EngineResult.Success(liveObs)

        val repo = DefaultWeatherRepository(provider = fakeProvider, clock = clock)

        // 1. Live network fetch is LIVE_NETWORK and current
        val first = repo.getCurrentObservation(TestFixtures.IBADAN).getOrNull()!!
        assertEquals(DeliveryOrigin.LIVE_NETWORK, first.deliveryOrigin)
        assertEquals(FreshnessState.FRESH, first.freshness.freshnessState)
        assertTrue(first.isLiveAndFresh)
        assertNull(first.fallbackTriggerError)

        // 2. Simulate offline failure 20 minutes later -> cached fallback is LOCAL_CACHE and NOT live
        fakeProvider.currentResult = EngineResult.Failure(EngineError.NetworkUnavailable("Device offline"))
        clock.advance(Duration.ofMinutes(20))

        val cachedFresh = repo.getCurrentObservation(TestFixtures.IBADAN).getOrNull()!!
        assertEquals(DeliveryOrigin.LOCAL_CACHE, cachedFresh.deliveryOrigin)
        assertTrue(QualityFlag.SERVED_FROM_CACHE in cachedFresh.freshness.flags)
        assertFalse(cachedFresh.isLiveAndFresh, "Cached data must NEVER be reported as current and live")
        assertNotNull(cachedFresh.fallbackTriggerError)
        assertEquals(FreshnessState.FRESH, cachedFresh.freshness.freshnessState)

        // 3. Advance clock to 45 minutes after retrieval -> AGING
        clock.advance(Duration.ofMinutes(25))
        val cachedAging = repo.getCurrentObservation(TestFixtures.IBADAN).getOrNull()!!
        assertEquals(FreshnessState.AGING, cachedAging.freshness.freshnessState)
        assertFalse(cachedAging.isLiveAndFresh)

        // 4. Advance clock to 95 minutes after retrieval -> STALE
        clock.advance(Duration.ofMinutes(50))
        val cachedStale = repo.getCurrentObservation(TestFixtures.IBADAN).getOrNull()!!
        assertEquals(FreshnessState.STALE, cachedStale.freshness.freshnessState)
        assertTrue(QualityFlag.STALE_INPUT_DATA in cachedStale.freshness.flags)

        // 5. NETWORK_ONLY policy refuses cache fallback and returns NetworkUnavailable
        val networkOnlyRes = repo.getCurrentObservation(TestFixtures.IBADAN, CachePolicy.NETWORK_ONLY)
        assertTrue(networkOnlyRes.errorOrNull() is EngineError.NetworkUnavailable)
    }
}
