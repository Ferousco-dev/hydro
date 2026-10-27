package com.ferousco.hydro.core

import com.ferousco.hydro.core.trip.TripMode
import com.ferousco.hydro.core.trip.WearableAdvisor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearableAdvisorTest {
    @Test
    fun `no reading or a stale reading says nothing`() {
        assertNull(WearableAdvisor.advise(TripMode.WALK, null, 5, 3))
        assertNull(WearableAdvisor.advise(TripMode.WALK, 160, null, 3))
        assertNull(WearableAdvisor.advise(TripMode.WALK, 160, 45, 3), "a 45 minute old reading is not about now")
        assertNull(WearableAdvisor.advise(TripMode.WALK, 160, -1, 3))
    }

    @Test
    fun `a normal heart rate says nothing even in heat`() {
        assertNull(WearableAdvisor.advise(TripMode.WALK, 95, 3, 3))
        assertNull(WearableAdvisor.advise(TripMode.BIKE, 125, 3, 4))
    }

    @Test
    fun `heat and heart rate together produce a gentle suggestion`() {
        assertNotNull(WearableAdvisor.advise(TripMode.WALK, 135, 5, 2))
        assertNull(WearableAdvisor.advise(TripMode.WALK, 135, 5, 0), "raised rate in mild weather is just effort")
        assertNotNull(WearableAdvisor.advise(TripMode.BIKE, 155, 5, 1))
    }

    @Test
    fun `a very high rate is flagged whatever the weather`() {
        val m = WearableAdvisor.advise(TripMode.WALK, 175, 2, 0)
        assertNotNull(m)
        assertTrue(m.contains("rest", ignoreCase = true))
    }

    @Test
    fun `driving never gets a heart rate message`() {
        assertNull(WearableAdvisor.advise(TripMode.DRIVE, 180, 1, 4))
    }

    @Test
    fun `wording never diagnoses or sounds technical`() {
        val all = listOf(
            WearableAdvisor.advise(TripMode.WALK, 175, 2, 0),
            WearableAdvisor.advise(TripMode.WALK, 155, 2, 2),
            WearableAdvisor.advise(TripMode.WALK, 135, 2, 3)
        ).filterNotNull().joinToString(" ")
        listOf("dehydrat", "heat stroke", "exhaustion", "diagnos", "bpm", "Health Connect").forEach {
            assertFalse(all.contains(it, ignoreCase = true), "found '$it'")
        }
    }
}
