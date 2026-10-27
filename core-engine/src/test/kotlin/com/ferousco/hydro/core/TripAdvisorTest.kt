package com.ferousco.hydro.core

import com.ferousco.hydro.core.recommendation.RecommendationUrgency
import com.ferousco.hydro.core.trip.TripAdviceInput
import com.ferousco.hydro.core.trip.TripAdvisor
import com.ferousco.hydro.core.trip.TripMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripAdvisorTest {
    private fun input(
        mode: TripMode = TripMode.WALK,
        minutes: Int = 60,
        rank: Int? = 0,
        uv: Double? = 3.0,
        hydrationMl: Int? = null,
        cooler: String? = null
    ) = TripAdviceInput(mode, distanceKm = 4.0, durationMinutes = minutes, worstHeatRank = rank,
        peakHeatIndexCelsius = null, maxUvIndex = uv, hydrationTargetMl = hydrationMl, coolerWindow = cooler)

    @Test
    fun `urgency follows heat for walkers but a car shelters drivers`() {
        assertEquals(RecommendationUrgency.LOW, TripAdvisor.advise(input(rank = 0)).urgency)
        assertEquals(RecommendationUrgency.HIGH, TripAdvisor.advise(input(rank = 2)).urgency)
        assertEquals(RecommendationUrgency.CRITICAL, TripAdvisor.advise(input(rank = 3)).urgency)
        assertEquals(RecommendationUrgency.LOW, TripAdvisor.advise(input(TripMode.DRIVE, rank = 2)).urgency)
        assertEquals(RecommendationUrgency.MODERATE, TripAdvisor.advise(input(TripMode.DRIVE, rank = 3)).urgency)
        assertEquals(RecommendationUrgency.UNKNOWN, TripAdvisor.advise(input(rank = null)).urgency)
    }

    @Test
    fun `water uses the planner target when there is one and rounds up to whole bottles`() {
        assertEquals(3, TripAdvisor.advise(input(hydrationMl = 1200)).waterBottles)
        assertEquals(2, TripAdvisor.advise(input(hydrationMl = 1000)).waterBottles)
    }

    @Test
    fun `water falls back to a heat scaled estimate and drivers need little`() {
        val cool = TripAdvisor.advise(input(minutes = 120, rank = 0)).waterBottles!!
        val hot = TripAdvisor.advise(input(minutes = 120, rank = 3)).waterBottles!!
        assertTrue(hot > cool, "a hotter trip needs more water")
        assertNull(TripAdvisor.advise(input(TripMode.DRIVE, minutes = 20)).waterBottles)
        assertEquals(1, TripAdvisor.advise(input(TripMode.DRIVE, minutes = 60)).waterBottles)
        assertNull(TripAdvisor.advise(input(minutes = 10)).waterBottles)
    }

    @Test
    fun `clothing reflects sun and heat and drivers never get sunscreen advice`() {
        val hotSunny = TripAdvisor.advise(input(rank = 2, uv = 8.0)).clothing.joinToString()
        assertTrue(hotSunny.contains("hat", ignoreCase = true))
        assertTrue(hotSunny.contains("bags", ignoreCase = true))
        val driving = TripAdvisor.advise(input(TripMode.DRIVE, rank = 2, uv = 9.0)).clothing.joinToString()
        assertFalse(driving.contains("hat", ignoreCase = true))
    }

    @Test
    fun `tips suggest a cooler window, a rest on long trips and postponing in danger`() {
        val tips = TripAdvisor.advise(input(minutes = 120, rank = 3, cooler = "07:00–09:00")).tips.joinToString(" ")
        assertTrue(tips.contains("07:00–09:00"))
        assertTrue(tips.contains("rest", ignoreCase = true))
        assertTrue(tips.contains("postpone", ignoreCase = true))
        val easy = TripAdvisor.advise(input(rank = 0, cooler = "07:00–09:00")).tips.joinToString(" ")
        assertFalse(easy.contains("07:00"), "no cooler-time tip when it is already fine")
    }

    @Test
    fun `an enormous trip caps the bottles, asks you to plan refills and suggests driving`() {
        val a = TripAdvisor.advise(input(minutes = 2_000, rank = 2))
        assertEquals(8, a.waterBottles)
        assertTrue(a.waterNote!!.contains("refill", ignoreCase = true))
        assertTrue(a.tips.joinToString(" ").contains("driving", ignoreCase = true))
    }

    @Test
    fun `advice never uses technical wording`() {
        val all = TripMode.entries.flatMap { mode ->
            (0..4).flatMap { rank ->
                val a = TripAdvisor.advise(input(mode, minutes = 100, rank = rank, uv = 9.0, cooler = "07:00–09:00"))
                listOf(a.headline, a.waterNote.orEmpty()) + a.clothing + a.tips
            }
        }.joinToString(" ")
        listOf("NWS", "WHO", "heat index", "Open-Meteo", "tool", "API").forEach {
            assertFalse(all.contains(it, ignoreCase = true), "found '$it' in advice text")
        }
    }
}
