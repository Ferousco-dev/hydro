package com.ferousco.hydro.core.proactive

import com.ferousco.hydro.core.tools.ToolCallOutcome

/** One alert's journey through the `create_notification` policy and into the sink. */
data class ProactiveDelivery(
    val alert: ProactiveAlert,
    val outcome: ToolCallOutcome<Any>
)

/**
 * Full result of a proactive pass: what was decided, and what happened to each alert.
 *
 * A delivery is not necessarily a delivered notification. Quiet hours, duplicate suppression, rate
 * limiting, low confidence, and user preferences all appear as a `SUPPRESSED` success with a
 * machine-readable reason, so the UI can explain a silent phone.
 */
data class ProactiveCheckResult(
    val decision: ProactiveDecision,
    val deliveries: List<ProactiveDelivery>
) {
    val submittedCount: Int
        get() = deliveries.count { delivery ->
            delivery.outcome is ToolCallOutcome.Success &&
                delivery.outcome.jsonResult.getString("status") == "SUBMITTED"
        }

    val suppressedCount: Int
        get() = deliveries.count { delivery ->
            delivery.outcome is ToolCallOutcome.Success &&
                delivery.outcome.jsonResult.getString("status") == "SUPPRESSED"
        }
}
