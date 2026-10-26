package com.ferousco.hydro.core

import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HourlyForecastPoint
import com.ferousco.hydro.core.model.ObservationKind
import com.ferousco.hydro.core.model.PercentageUnit
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.UnavailableReason
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.inUnitOrNull
import com.ferousco.hydro.core.model.rawValueOrNull
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainModelsAndJsonTest {

    @Test
    fun `GeoLocation validates WGS84 coordinate bounds and computes Haversine distance`() {
        assertTrue(GeoLocation.validated(91.0, 3.9).isFailure)
        assertTrue(GeoLocation.validated(-90.1, 3.9).isFailure)
        assertTrue(GeoLocation.validated(7.3, 180.5).isFailure)
        assertTrue(GeoLocation.validated(Double.NaN, 3.9).isFailure)

        val distKm = TestFixtures.IBADAN.distanceKilometersTo(TestFixtures.LAGOS)
        // Ibadan (7.3775N, 3.9470E) to Lagos (6.5244N, 3.3792E) is ~114 km
        assertTrue(distKm in 110.0..120.0, "Expected ~114 km, got $distKm")
    }

    @Test
    fun `Physical units convert accurately and reject unphysical values`() {
        assertEquals(0.0, TemperatureUnit.FAHRENHEIT.convert(32.0, TemperatureUnit.CELSIUS), 1e-9)
        assertEquals(100.0, TemperatureUnit.FAHRENHEIT.convert(212.0, TemperatureUnit.CELSIUS), 1e-9)
        assertEquals(273.15, TemperatureUnit.CELSIUS.convert(0.0, TemperatureUnit.KELVIN), 1e-9)

        assertEquals(10.0, SpeedUnit.KILOMETERS_PER_HOUR.convert(36.0, SpeedUnit.METERS_PER_SECOND), 1e-9)
        assertEquals(0.44704, SpeedUnit.MILES_PER_HOUR.convert(1.0, SpeedUnit.METERS_PER_SECOND), 1e-9)

        assertTrue(TemperatureUnit.validateAmbientAir(-120.0, TemperatureUnit.CELSIUS).isFailure)
        assertTrue(TemperatureUnit.validateAmbientAir(80.0, TemperatureUnit.CELSIUS).isFailure)
        assertTrue(PercentageUnit.validateRelativeHumidity(-0.1).isFailure)
        assertTrue(PercentageUnit.validateRelativeHumidity(100.1).isFailure)
        assertTrue(SpeedUnit.validateWindSpeed(-1.0, SpeedUnit.METERS_PER_SECOND).isFailure)
    }

    @Test
    fun `Unavailable fields never substitute zero defaults`() {
        val unavail: FieldAvailability<TemperatureUnit> =
            FieldAvailability.Unavailable(UnavailableReason.NULL_IN_PROVIDER_RESPONSE)
        assertFalse(unavail.isAvailable)
        assertNull(unavail.quantityOrNull())
        assertNull(unavail.rawValueOrNull())
        assertNull(unavail.inUnitOrNull(TemperatureUnit.CELSIUS))
    }

    @Test
    fun `Observation and Forecast domain models enforce strict provenance separation`() {
        val now = Instant.parse("2026-10-26T12:00:00Z")
        val forecastVars = TestFixtures.buildVariables(now, nature = ValueNature.FORECAST)
        val obsTemplate = TestFixtures.buildObservation(observedAt = now)

        assertFailsWith<IllegalArgumentException> {
            EnvironmentalObservation(
                requestedLocation = TestFixtures.IBADAN,
                effectiveLocation = TestFixtures.IBADAN,
                observedAt = now,
                retrievedAt = now,
                timeZone = ZoneId.of("Africa/Lagos"),
                observationKind = ObservationKind.IN_SITU_OBSERVATION,
                variables = forecastVars,
                source = TestFixtures.TEST_OBSERVATION_SOURCE,
                quality = obsTemplate.quality
            )
        }

        val observedVars = TestFixtures.buildVariables(now, nature = ValueNature.OBSERVED)
        assertFailsWith<IllegalArgumentException> {
            HourlyForecastPoint(
                validAt = now,
                localDateTime = now.atZone(ZoneId.of("Africa/Lagos")),
                issuedOrRetrievedAt = now,
                location = TestFixtures.IBADAN,
                variables = observedVars,
                source = TestFixtures.TEST_FORECAST_SOURCE
            )
        }
    }

    @Test
    fun `SafeJsonCodec parses valid RFC-8259 JSON and rejects malformed or abusive inputs`() {
        val json = """{"city":"Ibadan","temp":33.5,"active":true,"items":[1,2,null],"escaped":"a\nb\u0041"}"""
        val obj = SafeJsonCodec.parseObject(json).getOrNull()!!
        assertEquals("Ibadan", obj.getString("city"))
        assertEquals(33.5, obj.getDouble("temp")!!, 1e-9)
        assertEquals(true, obj.getBoolean("active"))
        assertEquals("a\nbA", obj.getString("escaped"))
        assertEquals(3, obj.getArray("items")!!.items.size)
        assertTrue(obj.getArray("items")!!.items[2] is JsonValue.NullValue)

        // Round-trip
        val roundTripped = SafeJsonCodec.parseObject(SafeJsonCodec.stringify(obj)).getOrNull()!!
        assertEquals("Ibadan", roundTripped.getString("city"))

        // Reject trailing tokens, duplicate keys, leading zeros, unquoted keys, and excessive depth
        assertTrue(SafeJsonCodec.parse("""{"a": 1} trailing""").isFailure)
        assertTrue(SafeJsonCodec.parse("""{"a": 1, "a": 2}""").isFailure)
        assertTrue(SafeJsonCodec.parse("""{"a": 01}""").isFailure)
        assertTrue(SafeJsonCodec.parse("""{a: 1}""").isFailure)
        assertTrue(SafeJsonCodec.parse("[[[[[[[[[[1]]]]]]]]]]", maxDepth = 4).isFailure)
        assertTrue(SafeJsonCodec.parse("""{"big":"1234567890"}""", maxChars = 5).isFailure)
    }
}
