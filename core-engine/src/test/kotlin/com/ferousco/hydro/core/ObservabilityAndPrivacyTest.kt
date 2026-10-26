package com.ferousco.hydro.core

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.history.InMemoryEnvironmentalHistoryStore
import com.ferousco.hydro.core.logging.InMemoryDiagnosticLogger
import com.ferousco.hydro.core.provider.openmeteo.OpenMeteoConfig
import com.ferousco.hydro.core.provider.openmeteo.OpenMeteoWeatherProvider
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservabilityAndPrivacyTest {

    @Test
    fun `diagnostic logger redacts precise coordinates, URL query parameters, and tokens`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-26T12:00:00Z"))
        val logger = InMemoryDiagnosticLogger(clock = clock)

        logger.info(
            component = "TestComponent",
            event = "Requesting https://api.open-meteo.com/v1/forecast?latitude=7.37756&longitude=3.94703&apikey=supersecret123 for 7.37756, 3.94703",
            attributes = mapOf(
                "latitude" to "7.37756",
                "longitude" to "3.94703",
                "api_key" to "supersecret123",
                "note" to "User at lat=7.37756 lon=3.94703"
            )
        )

        val transport = FakeHttpTransport()
        transport.enqueueSuccess(
            body = """
            {
              "latitude": 7.38,
              "longitude": 3.95,
              "utc_offset_seconds": 3600,
              "timezone": "Africa/Lagos",
              "current_units": { "time": "unixtime", "temperature_2m": "°C", "relative_humidity_2m": "%" },
              "current": { "time": 1793015100, "temperature_2m": 32.0, "relative_humidity_2m": 65.0 }
            }
            """.trimIndent()
        )
        transport.enqueueFailure(EngineError.NetworkUnavailable("Offline"))

        val provider = OpenMeteoWeatherProvider(
            config = OpenMeteoConfig(),
            transport = transport,
            clock = clock,
            logger = logger
        )
        val engine = HydroEngine.create(
            weatherProvider = provider,
            historyStore = InMemoryEnvironmentalHistoryStore(),
            clock = clock,
            logger = logger
        )

        // Trigger live fetch + offline cache fallback + tool rejection logs
        engine.weatherRepository.getCurrentObservation(TestFixtures.IBADAN)
        engine.weatherRepository.getCurrentObservation(TestFixtures.IBADAN)
        engine.toolRegistry.executeJson("unknown_tool", "{}")

        val events = logger.events()
        assertTrue(events.isNotEmpty())

        val dumped = events.joinToString("\n") { "${it.event} ${it.attributes}" }
        assertFalse(dumped.contains("7.3775"), "Exact latitude must never appear in diagnostic logs: $dumped")
        assertFalse(dumped.contains("3.9470"), "Exact longitude must never appear in diagnostic logs: $dumped")
        assertFalse(dumped.contains("supersecret123"), "Secrets must never appear in diagnostic logs: $dumped")
    }
}
