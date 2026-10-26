package com.ferousco.hydro.monitoring

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the scheduling contract without instantiating WorkManager.
 *
 * WorkManager rejects a periodic interval below 15 minutes at runtime, so a regression here would
 * fail on a device rather than in CI. The flex window must also be shorter than the interval.
 */
class ProactiveMonitorSchedulerTest {

    @Test
    fun `periodic interval respects the WorkManager floor`() {
        assertTrue(
            ProactiveMonitorScheduler.INTERVAL >= Duration.ofMinutes(15),
            "WorkManager periodic work cannot run more often than every 15 minutes."
        )
    }

    @Test
    fun `flex window is shorter than the interval`() {
        assertTrue(ProactiveMonitorScheduler.FLEX < ProactiveMonitorScheduler.INTERVAL)
        assertTrue(!ProactiveMonitorScheduler.FLEX.isNegative && !ProactiveMonitorScheduler.FLEX.isZero)
    }

    @Test
    fun `unique work name is stable`() {
        assertEquals("hydro_proactive_monitor", ProactiveMonitorScheduler.UNIQUE_WORK_NAME)
    }
}
