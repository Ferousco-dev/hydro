package com.ferousco.hydro.core

import com.ferousco.hydro.core.integration.AirQualityReading
import com.ferousco.hydro.core.integration.DustLevel
import com.ferousco.hydro.core.integration.HarmattanAdvisor
import com.ferousco.hydro.core.model.GeoLocation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarmattanAdvisorTest {
    private val kano = GeoLocation(latitude = 12.0022, longitude = 8.5920)
    private val lagos = GeoLocation(latitude = 6.5244, longitude = 3.3792)
    private val nairobi = GeoLocation(latitude = -1.2921, longitude = 36.8219)

    private fun reading(dust: Double?, pm10: Double?, aqi: Double = 80.0) = AirQualityReading(
        observedAt = null, usAqi = aqi, europeanAqi = null, category = null,
        pollutantsMicrogramsPerM3 = listOfNotNull(dust?.let { "dust" to it }, pm10?.let { "pm10" to it }).toMap(),
        retrievedAt = Instant.EPOCH, providerId = "test"
    )

    @Test
    fun `clean air gives no warning`() {
        assertEquals(DustLevel.NONE, HarmattanAdvisor.assess(reading(3.0, 18.2), lagos, month = 7).level)
    }

    @Test
    fun `Kano on the October readings is dusty`() {
        // Real figures seen for Kano: dust 82, PM10 125.
        assertEquals(DustLevel.DUSTY, HarmattanAdvisor.assess(reading(82.0, 125.3), kano, month = 10).level)
    }

    @Test
    fun `a very unhealthy air index with dust behind it is heavy`() {
        // Kano on 27 October 2026: AQI 247, dust 82, PM10 125.
        assertEquals(DustLevel.HEAVY, HarmattanAdvisor.assess(reading(82.0, 125.3, aqi = 247.0), kano, month = 10).level)
        // The same index with no dust behind it is not a dust warning.
        assertEquals(DustLevel.NONE, HarmattanAdvisor.assess(reading(5.0, 30.0, aqi = 247.0), kano, month = 10).level)
    }

    @Test
    fun `heavy dust or very high PM10 is heavy`() {
        assertEquals(DustLevel.HEAVY, HarmattanAdvisor.assess(reading(120.0, 90.0), kano, month = 1).level)
        assertEquals(DustLevel.HEAVY, HarmattanAdvisor.assess(reading(10.0, 160.0), kano, month = 1).level)
    }

    @Test
    fun `missing pollutants never produce a warning`() {
        assertEquals(DustLevel.NONE, HarmattanAdvisor.assess(reading(null, null), kano, month = 1).level)
    }

    @Test
    fun `the season is November to March in West Africa only`() {
        assertTrue(HarmattanAdvisor.inSeason(kano, 12))
        assertTrue(HarmattanAdvisor.inSeason(lagos, 2))
        assertFalse(HarmattanAdvisor.inSeason(kano, 7))
        assertFalse(HarmattanAdvisor.inSeason(nairobi, 12))
    }
}
