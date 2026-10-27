package com.ferousco.hydro.core

import com.ferousco.hydro.core.model.AfricaCoverage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AfricaCoverageTest {

    @Test
    fun `Nigerian and other African cities are inside`() {
        listOf(
            "Lagos" to (6.5244 to 3.3792), "Abuja" to (9.0765 to 7.3986), "Kano" to (12.0022 to 8.5920),
            "Maiduguri" to (11.8333 to 13.1500), "Accra" to (5.6037 to -0.1870), "Nairobi" to (-1.2921 to 36.8219),
            "Cairo" to (30.0444 to 31.2357), "Cape Town" to (-33.9249 to 18.4241), "Dakar" to (14.7167 to -17.4677),
            "Addis Ababa" to (9.0300 to 38.7400), "Antananarivo" to (-18.8792 to 47.5079), "Timbuktu" to (16.7666 to -3.0026)
        ).forEach { (name, point) ->
            assertTrue(AfricaCoverage.contains(point.first, point.second), "$name should be in Africa")
        }
    }

    @Test
    fun `places on other continents are outside`() {
        listOf(
            "Pyongyang" to (39.0392 to 125.7625), "London" to (51.5074 to -0.1278), "New York" to (40.7128 to -74.0060),
            "Riyadh" to (24.7136 to 46.6753), "Jeddah" to (21.4858 to 39.1925), "Tel Aviv" to (32.0853 to 34.7818),
            "Mumbai" to (19.0760 to 72.8777), "Rio de Janeiro" to (-22.9068 to -43.1729), "Palo Alto" to (37.4419 to -122.1430)
        ).forEach { (name, point) ->
            assertFalse(AfricaCoverage.contains(point.first, point.second), "$name should be outside Africa")
        }
    }
}
