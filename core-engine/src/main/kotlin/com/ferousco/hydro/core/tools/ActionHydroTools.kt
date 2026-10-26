package com.ferousco.hydro.core.tools

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.integration.NotificationCategory
import com.ferousco.hydro.core.integration.NotificationIntent
import com.ferousco.hydro.core.integration.NotificationPolicy
import com.ferousco.hydro.core.integration.NotificationReceipt
import com.ferousco.hydro.core.integration.NotificationSeverity
import com.ferousco.hydro.core.integration.NotificationSink
import com.ferousco.hydro.core.integration.NotificationSuppressionReason
import com.ferousco.hydro.core.integration.NotificationTrigger
import com.ferousco.hydro.core.integration.WearableDataSource
import com.ferousco.hydro.core.integration.WearableMetric
import com.ferousco.hydro.core.integration.WearableReadRequest
import com.ferousco.hydro.core.integration.WearableReadResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.ActivityIntensity
import com.ferousco.hydro.core.model.DataQualityAndFreshness
import com.ferousco.hydro.core.model.DeliveryOrigin
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.HourlyForecastPoint
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.WeatherForecast
import com.ferousco.hydro.core.model.rawValueOrNull
import com.ferousco.hydro.core.preferences.UserPreferences
import com.ferousco.hydro.core.preferences.UserPreferencesStore
import com.ferousco.hydro.core.provider.CachePolicy
import com.ferousco.hydro.core.provider.WeatherRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

// ============================================================================
// 11. get_wearable_data
// ============================================================================

data class GetWearableDataParams(
    val metrics: Set<WearableMetric>,
    val since: Instant,
    val until: Instant,
    val maxSamples: Int
)

class GetWearableDataTool(
    private val wearableDataSource: WearableDataSource,
    private val clock: Clock = Clock.systemUTC()
) : HydroTool<GetWearableDataParams, WearableReadResult> {

    override val descriptor = ToolDescriptor(
        name = "get_wearable_data",
        description = "Read only supported wearable/Health Connect measurements the user has authorised, " +
            "with source, timestamps, units, and quality flags. Never estimates a missing reading.",
        requiredCapabilities = setOf(ToolCapability.WEARABLE_READ),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema(
                    "metrics",
                    ToolSchemaType.ARRAY,
                    "Metrics to read: ${WearableMetric.entries.joinToString(", ") { it.name }}.",
                    required = true,
                    arrayItemType = ToolSchemaType.STRING,
                    maxItems = WearableMetric.entries.size
                ),
                ToolPropertySchema(
                    "lookback_minutes",
                    ToolSchemaType.INTEGER,
                    "Window ending at `until`, in minutes [1, 1440]. Ignored when `since` is supplied.",
                    required = false,
                    minimum = 1.0,
                    maximum = 1440.0
                ),
                ToolPropertySchema("since", ToolSchemaType.STRING, "Optional ISO-8601 window start.", required = false, maxLength = 40),
                ToolPropertySchema("until", ToolSchemaType.STRING, "Optional ISO-8601 window end; defaults to now.", required = false, maxLength = 40),
                ToolPropertySchema("max_samples", ToolSchemaType.INTEGER, "Maximum samples per metric [1, 500].", required = false, minimum = 1.0, maximum = 500.0)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("permission_granted", ToolSchemaType.BOOLEAN, "Whether the user granted wearable access."),
                ToolPropertySchema("data_source", ToolSchemaType.STRING, "Integration that served the readings."),
                ToolPropertySchema("sample_count", ToolSchemaType.INTEGER, "Number of returned samples."),
                ToolPropertySchema("samples", ToolSchemaType.ARRAY, "Authorised measurements.", arrayItemType = ToolSchemaType.OBJECT),
                ToolPropertySchema("unavailable_metrics", ToolSchemaType.ARRAY, "Requested metrics that could not be supplied.", arrayItemType = ToolSchemaType.OBJECT),
                ToolPropertySchema("limitations", ToolSchemaType.ARRAY, "Explicit data limitations.", arrayItemType = ToolSchemaType.STRING)
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(15),
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "UNSUPPORTED_CALCULATION", "TOOL_TIMEOUT")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<GetWearableDataParams> {
        val metricItems = rawArgs.getArray("metrics")?.items
            ?: return EngineResult.Failure(
                EngineError.MissingRequiredInputs("get_wearable_data", listOf("metrics"), "At least one wearable metric is required.")
            )
        if (metricItems.isEmpty()) {
            return EngineResult.Failure(
                EngineError.InvalidInput("metrics", "empty", "At least one wearable metric is required.")
            )
        }
        val metrics = LinkedHashSet<WearableMetric>()
        for (item in metricItems) {
            val name = (item as? JsonValue.StringValue)?.value
                ?: return EngineResult.Failure(
                    EngineError.InvalidInput("metrics", "invalid_item", "Each metric must be a string.")
                )
            val metric = WearableMetric.entries.firstOrNull { it.name == name }
                ?: return EngineResult.Failure(
                    EngineError.InvalidInput("metrics", name, "Unsupported wearable metric '$name'.")
                )
            metrics.add(metric)
        }

        val until = rawArgs.getString("until")?.let {
            when (val parsed = ToolEncodingSupport.parseInstant(it, "until")) {
                is EngineResult.Failure -> return parsed
                is EngineResult.Success -> parsed.value
            }
        } ?: clock.instant()

        val since = rawArgs.getString("since")?.let {
            when (val parsed = ToolEncodingSupport.parseInstant(it, "since")) {
                is EngineResult.Failure -> return parsed
                is EngineResult.Success -> parsed.value
            }
        } ?: until.minus(Duration.ofMinutes((rawArgs.getInt("lookback_minutes") ?: 60).toLong()))

        if (until.isBefore(since)) {
            return EngineResult.Failure(
                EngineError.InvalidInput("until", until.toString(), "until cannot precede since.")
            )
        }
        if (Duration.between(since, until) > MAX_WINDOW) {
            return EngineResult.Failure(
                EngineError.InvalidInput("since", since.toString(), "Wearable window cannot exceed 24 hours.")
            )
        }

        return EngineResult.Success(
            GetWearableDataParams(
                metrics = metrics,
                since = since,
                until = until,
                maxSamples = rawArgs.getInt("max_samples") ?: 100
            )
        )
    }

    override suspend fun executeTyped(params: GetWearableDataParams): EngineResult<WearableReadResult> =
        wearableDataSource.read(
            WearableReadRequest(
                metrics = params.metrics,
                since = params.since,
                until = params.until,
                maxSamples = params.maxSamples
            )
        )

    override fun encodeResult(result: WearableReadResult): JsonValue.ObjectValue {
        val samples = result.samples.map { sample ->
            JsonValue.ObjectValue(
                linkedMapOf<String, JsonValue>().apply {
                    put("metric", JsonValue.StringValue(sample.metric.name))
                    put("recorded_at", JsonValue.StringValue(sample.recordedAt.toString()))
                    put("value", SafeJsonCodec.number(sample.value))
                    put("unit", JsonValue.StringValue(sample.unit))
                    sample.sourceDevice?.let { put("source_device", JsonValue.StringValue(it)) }
                    put("quality", JsonValue.StringValue(sample.quality))
                }
            )
        }
        val unavailable = result.unavailableMetrics.map { (metric, reason) ->
            JsonValue.ObjectValue(
                linkedMapOf(
                    "metric" to JsonValue.StringValue(metric.name),
                    "reason" to JsonValue.StringValue(reason)
                )
            )
        }
        return JsonValue.ObjectValue(
            linkedMapOf(
                "data_nature" to JsonValue.StringValue(ValueNature.OBSERVED.name),
                "permission_granted" to JsonValue.BooleanValue(result.permissionGranted),
                "data_source" to JsonValue.StringValue(result.dataSource),
                "sample_count" to SafeJsonCodec.number(result.samples.size),
                "samples" to JsonValue.ArrayValue(samples),
                "unavailable_metrics" to JsonValue.ArrayValue(unavailable),
                "limitations" to JsonValue.ArrayValue(result.limitations.map(JsonValue::StringValue))
            )
        )
    }

    companion object {
        val MAX_WINDOW: Duration = Duration.ofHours(24)
    }
}

// ============================================================================
// 12. create_notification
// ============================================================================

data class CreateNotificationParams(
    val title: String,
    val body: String,
    val severity: NotificationSeverity,
    val category: NotificationCategory,
    val dedupKey: String,
    val trigger: NotificationTrigger,
    val confidence: Double,
    val evidence: List<String>,
    val expiresInMinutes: Int?,
    val timeZone: ZoneId
)

data class NotificationPolicySummary(
    val maxPerHour: Int,
    val dedupWindowMinutes: Long,
    val quietHoursStart: String,
    val quietHoursEnd: String,
    val criticalAllowedDuringQuietHours: Boolean,
    val minimumSeverity: String,
    val userPreferencesApplied: Boolean
)

data class CreateNotificationResult(
    val receipt: NotificationReceipt,
    val intent: NotificationIntent,
    val policy: NotificationPolicySummary
)

/**
 * Submits an approved alert to the platform, after deterministic policy enforcement.
 *
 * The model may propose an alert, but it cannot choose to ignore deduplication, quiet hours, or the
 * hourly rate limit: those are checked here, in application code, before the sink is called. A
 * suppressed alert is a successful tool call with an explicit reason, not an error the model should
 * retry.
 */
class CreateNotificationTool(
    private val notificationSink: NotificationSink,
    private val policy: NotificationPolicy = NotificationPolicy(),
    private val preferencesStore: UserPreferencesStore? = null,
    private val clock: Clock = Clock.systemUTC()
) : HydroTool<CreateNotificationParams, CreateNotificationResult> {

    private val mutex = Mutex()
    private val lastSubmittedByKey = mutableMapOf<String, Instant>()
    private val submissionTimes = ArrayDeque<Instant>()

    override val descriptor = ToolDescriptor(
        name = "create_notification",
        description = "Submit a deduplicated, policy-checked alert to the Android notification layer. " +
            "Quiet hours, an hourly rate limit, and confidence thresholds are enforced deterministically.",
        requiredCapabilities = setOf(ToolCapability.NOTIFICATION_WRITE),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("title", ToolSchemaType.STRING, "Short alert title.", required = true, maxLength = 80),
                ToolPropertySchema("body", ToolSchemaType.STRING, "One- or two-sentence actionable body.", required = true, maxLength = 280),
                ToolPropertySchema("severity", ToolSchemaType.ENUM, "Alert severity.", required = true, enumValues = NotificationSeverity.entries.map { it.name }),
                ToolPropertySchema("category", ToolSchemaType.ENUM, "Alert category.", required = true, enumValues = NotificationCategory.entries.map { it.name }),
                ToolPropertySchema("dedup_key", ToolSchemaType.STRING, "Stable key identifying this alert condition, e.g. 'heat:ibadan:2026-10-26'.", required = true, maxLength = 128),
                ToolPropertySchema("trigger", ToolSchemaType.ENUM, "What produced the alert.", required = false, enumValues = NotificationTrigger.entries.map { it.name }),
                ToolPropertySchema("confidence", ToolSchemaType.NUMBER, "Caller confidence in [0, 1] that the alert is warranted.", required = true, minimum = 0.0, maximum = 1.0),
                ToolPropertySchema("evidence", ToolSchemaType.ARRAY, "Short grounded evidence strings.", required = false, arrayItemType = ToolSchemaType.STRING, maxItems = 5),
                ToolPropertySchema("expires_in_minutes", ToolSchemaType.INTEGER, "Alert expiry in minutes [1, 1440]; omit for no expiry.", required = false, minimum = 1.0, maximum = 1440.0),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "IANA timezone used for quiet hours.", required = false, maxLength = 64)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("status", ToolSchemaType.STRING, "SUBMITTED, SUPPRESSED, or REJECTED."),
                ToolPropertySchema("notification_id", ToolSchemaType.STRING, "Platform notification id when submitted.", required = false),
                ToolPropertySchema("reason", ToolSchemaType.STRING, "Suppression reason when not submitted.", required = false),
                ToolPropertySchema("explanation", ToolSchemaType.STRING, "Human-readable outcome."),
                ToolPropertySchema("retry_after", ToolSchemaType.STRING, "Earliest sensible retry instant, when applicable.", required = false),
                ToolPropertySchema("policy", ToolSchemaType.OBJECT, "Policy that was applied.")
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(10),
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "UNSUPPORTED_CALCULATION", "TOOL_TIMEOUT")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<CreateNotificationParams> {
        val title = rawArgs.getString("title")?.trim().orEmpty()
        if (title.isEmpty()) {
            return EngineResult.Failure(EngineError.InvalidInput("title", "blank", "Notification title must not be blank."))
        }
        val body = rawArgs.getString("body")?.trim().orEmpty()
        if (body.isEmpty()) {
            return EngineResult.Failure(EngineError.InvalidInput("body", "blank", "Notification body must not be blank."))
        }
        val dedupKey = rawArgs.getString("dedup_key")?.trim().orEmpty()
        if (dedupKey.isEmpty()) {
            return EngineResult.Failure(
                EngineError.InvalidInput("dedup_key", "blank", "dedup_key is required so Hydro can suppress duplicates.")
            )
        }
        val evidence = rawArgs.getArray("evidence")?.items.orEmpty().mapNotNull { item ->
            (item as? JsonValue.StringValue)?.value?.take(MAX_EVIDENCE_CHARS)
        }
        val timeZone = rawArgs.getString("timezone")?.let {
            try {
                ZoneId.of(it)
            } catch (_: Exception) {
                return EngineResult.Failure(EngineError.InvalidInput("timezone", it, "Unrecognized IANA timezone '$it'."))
            }
        } ?: policy.defaultTimeZone

        return EngineResult.Success(
            CreateNotificationParams(
                title = title,
                body = body,
                severity = NotificationSeverity.valueOf(rawArgs.getString("severity")!!),
                category = NotificationCategory.valueOf(rawArgs.getString("category")!!),
                dedupKey = dedupKey,
                trigger = rawArgs.getString("trigger")?.let { NotificationTrigger.valueOf(it) }
                    ?: NotificationTrigger.USER_REQUEST,
                confidence = rawArgs.getDouble("confidence")!!,
                evidence = evidence,
                expiresInMinutes = rawArgs.getInt("expires_in_minutes"),
                timeZone = timeZone
            )
        )
    }

    override suspend fun executeTyped(params: CreateNotificationParams): EngineResult<CreateNotificationResult> {
        // A preferences read failure must neither block nor silently loosen an alert: fall back to
        // the engine policy and report that user preferences were not applied.
        val userPreferences = preferencesStore?.load()?.getOrNull()
        val effective = effectivePolicy(userPreferences)

        return mutex.withLock {
            val now = clock.instant()
            val expiresAt = params.expiresInMinutes?.let { now.plus(Duration.ofMinutes(it.toLong())) }
            val intent = NotificationIntent(
                title = params.title,
                body = params.body,
                severity = params.severity,
                category = params.category,
                dedupKey = params.dedupKey,
                createdAt = now,
                expiresAt = expiresAt,
                trigger = params.trigger,
                confidence = params.confidence,
                evidence = params.evidence,
                location = null
            )

            val suppression = evaluatePolicy(params, now, effective, userPreferences)
            if (suppression != null) {
                return@withLock EngineResult.Success(
                    CreateNotificationResult(
                        receipt = suppression,
                        intent = intent,
                        policy = policySummary(effective, userPreferences)
                    )
                )
            }

            val submitted = when (val result = notificationSink.submit(intent)) {
                is EngineResult.Failure -> return@withLock result
                is EngineResult.Success -> result.value
            }

            if (submitted is NotificationReceipt.Submitted) {
                lastSubmittedByKey[params.dedupKey] = now
                submissionTimes.addLast(now)
                pruneRateWindow(now)
                pruneDedupWindow(now, effective)
            }

            EngineResult.Success(
                CreateNotificationResult(
                    receipt = submitted,
                    intent = intent,
                    policy = policySummary(effective, userPreferences)
                )
            )
        }
    }

    override fun encodeResult(result: CreateNotificationResult): JsonValue.ObjectValue {
        val entries = linkedMapOf<String, JsonValue>(
            "policy" to JsonValue.ObjectValue(
                linkedMapOf(
                    "max_per_hour" to SafeJsonCodec.number(result.policy.maxPerHour),
                    "dedup_window_minutes" to SafeJsonCodec.number(result.policy.dedupWindowMinutes),
                    "quiet_hours_start" to JsonValue.StringValue(result.policy.quietHoursStart),
                    "quiet_hours_end" to JsonValue.StringValue(result.policy.quietHoursEnd),
                    "critical_allowed_during_quiet_hours" to JsonValue.BooleanValue(
                        result.policy.criticalAllowedDuringQuietHours
                    ),
                    "minimum_severity" to JsonValue.StringValue(result.policy.minimumSeverity),
                    "user_preferences_applied" to JsonValue.BooleanValue(
                        result.policy.userPreferencesApplied
                    )
                )
            ),
            "severity" to JsonValue.StringValue(result.intent.severity.name),
            "category" to JsonValue.StringValue(result.intent.category.name),
            "dedup_key" to JsonValue.StringValue(result.intent.dedupKey)
        )

        when (val receipt = result.receipt) {
            is NotificationReceipt.Submitted -> {
                entries["status"] = JsonValue.StringValue("SUBMITTED")
                entries["notification_id"] = JsonValue.StringValue(receipt.notificationId)
                entries["submitted_at"] = JsonValue.StringValue(receipt.submittedAt.toString())
                entries["explanation"] = JsonValue.StringValue(
                    "Alert submitted to the notification layer as '${result.intent.title}'."
                )
            }
            is NotificationReceipt.Suppressed -> {
                entries["status"] = JsonValue.StringValue("SUPPRESSED")
                entries["reason"] = JsonValue.StringValue(receipt.reason.name)
                entries["explanation"] = JsonValue.StringValue(receipt.explanation)
                receipt.retryAfter?.let { entries["retry_after"] = JsonValue.StringValue(it.toString()) }
            }
            is NotificationReceipt.Rejected -> {
                entries["status"] = JsonValue.StringValue("REJECTED")
                entries["explanation"] = JsonValue.StringValue(receipt.explanation)
            }
        }
        return JsonValue.ObjectValue(entries)
    }

    /** Returns a suppression receipt, or `null` when the alert may proceed. */
    private fun evaluatePolicy(
        params: CreateNotificationParams,
        now: Instant,
        effective: NotificationPolicy,
        userPreferences: UserPreferences?
    ): NotificationReceipt.Suppressed? {
        val userAlerts = userPreferences?.alerts
        if (userAlerts != null) {
            if (!userAlerts.enabled) {
                return NotificationReceipt.Suppressed(
                    reason = NotificationSuppressionReason.USER_DISABLED,
                    explanation = "Alerts are turned off in your saved Hydro preferences, so this alert " +
                        "was not delivered."
                )
            }
            if (params.severity.ordinal < userAlerts.minimumSeverity.ordinal) {
                return NotificationReceipt.Suppressed(
                    reason = NotificationSuppressionReason.BELOW_MINIMUM_SEVERITY,
                    explanation = "This is a ${params.severity.name} alert, but your saved minimum " +
                        "severity is ${userAlerts.minimumSeverity.name}."
                )
            }
        }

        val expiry = params.expiresInMinutes?.let { now.plus(Duration.ofMinutes(it.toLong())) }
        if (expiry != null && !expiry.isAfter(now)) {
            return NotificationReceipt.Suppressed(
                reason = NotificationSuppressionReason.EXPIRED,
                explanation = "The alert had already expired before it could be delivered."
            )
        }

        val requiredConfidence = if (params.severity == NotificationSeverity.CRITICAL) {
            policy.minimumCriticalConfidence
        } else {
            policy.minimumConfidence
        }
        if (params.confidence < requiredConfidence) {
            return NotificationReceipt.Suppressed(
                reason = NotificationSuppressionReason.LOW_CONFIDENCE,
                explanation = "Confidence ${params.confidence} is below the ${requiredConfidence} threshold " +
                    "for ${params.severity.name} alerts. Hydro will not send an alert it is unsure about."
            )
        }

        val localNow = now.atZone(params.timeZone)
        val quiet = effective.isQuietHours(localNow.toLocalTime())
        val criticalOverride = params.severity == NotificationSeverity.CRITICAL &&
            effective.allowCriticalDuringQuietHours
        if (quiet && !criticalOverride) {
            return NotificationReceipt.Suppressed(
                reason = NotificationSuppressionReason.QUIET_HOURS,
                explanation = "Local time ${localNow.toLocalTime()} falls inside quiet hours " +
                    "(${effective.quietHoursStart}–${effective.quietHoursEnd}). Non-critical alerts are held.",
                retryAfter = nextQuietHoursEnd(localNow, effective)
            )
        }

        lastSubmittedByKey[params.dedupKey]?.let { last ->
            val elapsed = Duration.between(last, now)
            if (elapsed < effective.dedupWindow) {
                return NotificationReceipt.Suppressed(
                    reason = NotificationSuppressionReason.DUPLICATE,
                    explanation = "An alert with dedup key '${params.dedupKey}' was already delivered " +
                        "${elapsed.toMinutes()} minutes ago; the dedup window is " +
                        "${effective.dedupWindow.toMinutes()} minutes.",
                    retryAfter = last.plus(effective.dedupWindow)
                )
            }
        }

        pruneRateWindow(now)
        if (submissionTimes.size >= effective.maxNotificationsPerHour) {
            val retryAfter = submissionTimes.firstOrNull()?.plus(Duration.ofHours(1))
            return NotificationReceipt.Suppressed(
                reason = NotificationSuppressionReason.RATE_LIMITED,
                explanation = "Hydro already delivered ${submissionTimes.size} alerts in the last hour " +
                    "(limit ${effective.maxNotificationsPerHour}).",
                retryAfter = retryAfter
            )
        }

        return null
    }

    private fun pruneRateWindow(now: Instant) {
        while (submissionTimes.isNotEmpty() &&
            Duration.between(submissionTimes.first(), now) >= Duration.ofHours(1)
        ) {
            submissionTimes.removeFirst()
        }
    }

    /** Keeps the dedup index bounded; entries older than the dedup window can never suppress again. */
    private fun pruneDedupWindow(now: Instant, effective: NotificationPolicy) {
        lastSubmittedByKey.keys.removeAll { key ->
            val last = lastSubmittedByKey[key] ?: return@removeAll true
            Duration.between(last, now) >= effective.dedupWindow
        }
    }

    private fun nextQuietHoursEnd(localNow: ZonedDateTime, effective: NotificationPolicy): Instant {
        var candidate = localNow.with(effective.quietHoursEnd)
        if (!candidate.toInstant().isAfter(localNow.toInstant())) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant()
    }

    /**
     * User preferences may tighten, but never loosen, the engine's delivery envelope.
     *
     * The user chooses whether alerts are on, what severity is worth interrupting them for, and when
     * their quiet hours are. They cannot raise the hourly ceiling or shrink deduplication below the
     * engine floor — that floor is what stops Hydro becoming a notification firehose.
     */
    private fun effectivePolicy(preferences: UserPreferences?): NotificationPolicy {
        if (preferences == null) return policy
        val alerts = preferences.alerts
        return policy.copy(
            maxNotificationsPerHour = minOf(policy.maxNotificationsPerHour, alerts.maxNotificationsPerHour),
            dedupWindow = maxOf(policy.dedupWindow, Duration.ofHours(alerts.dedupWindowHours.toLong())),
            quietHoursStart = alerts.quietHoursStart,
            quietHoursEnd = alerts.quietHoursEnd,
            allowCriticalDuringQuietHours = policy.allowCriticalDuringQuietHours &&
                alerts.allowCriticalDuringQuietHours
        )
    }

    private fun policySummary(
        effective: NotificationPolicy,
        userPreferences: UserPreferences?
    ): NotificationPolicySummary = NotificationPolicySummary(
        maxPerHour = effective.maxNotificationsPerHour,
        dedupWindowMinutes = effective.dedupWindow.toMinutes(),
        quietHoursStart = effective.quietHoursStart.toString(),
        quietHoursEnd = effective.quietHoursEnd.toString(),
        criticalAllowedDuringQuietHours = effective.allowCriticalDuringQuietHours,
        minimumSeverity = (userPreferences?.alerts?.minimumSeverity ?: NotificationSeverity.WATCH).name,
        userPreferencesApplied = userPreferences != null
    )

    private companion object {
        const val MAX_EVIDENCE_CHARS = 200
    }
}

// ============================================================================
// 13. plan_hydration
// ============================================================================

data class PlanHydrationParams(
    val location: GeoLocation,
    val departAt: Instant,
    val activityCategory: ActivityCategory,
    val durationMinutes: Int,
    val intensity: ActivityIntensity,
    val bodyMassKg: Double?
)

data class HydrationScheduleEntry(
    val atMinute: Int,
    val fluidMl: Int,
    val note: String
)

data class HydrationPlanResult(
    val targetFluidMl: Int,
    val minFluidMl: Int,
    val maxFluidMl: Int,
    val preHydrationMl: Int,
    val sweatRateLitersPerHour: Double,
    val assumedBodyMassKg: Double,
    val bodyMassProvided: Boolean,
    val heatBasis: String,
    val heatIndexCelsius: Double?,
    val heatCategory: String?,
    val sodiumMgMin: Int,
    val sodiumMgMax: Int,
    val schedule: List<HydrationScheduleEntry>,
    val preparation: List<String>,
    val limitations: List<String>,
    val assessedAt: Instant,
    val forecastValidAt: Instant
)

/**
 * Deterministic hydration and refill plan.
 *
 * Every number is computed here from a heat assessment plus caller-supplied duration, intensity, and
 * optional body mass. The model supplies assumptions; it never supplies the arithmetic. The output is
 * explicitly a planning estimate, not medical advice, and is capped to avoid encouraging overdrinking.
 */
class PlanHydrationTool(
    private val weatherRepository: WeatherRepository,
    private val assessmentEngine: HeatAssessmentEngine,
    private val clock: Clock = Clock.systemUTC()
) : HydroTool<PlanHydrationParams, HydrationPlanResult> {

    override val descriptor = ToolDescriptor(
        name = "plan_hydration",
        description = "Create a qualified hydration and refill plan from a forecast heat assessment, " +
            "planned duration, activity intensity, and optional body mass.",
        requiredCapabilities = setOf(ToolCapability.WEATHER_PROVIDER_READ, ToolCapability.DETERMINISTIC_ANALYSIS),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("latitude", ToolSchemaType.NUMBER, "Latitude in degrees [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("longitude", ToolSchemaType.NUMBER, "Longitude in degrees [-180, 180].", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("activity_category", ToolSchemaType.ENUM, "Planned activity.", required = true, enumValues = ActivityCategory.entries.map { it.name }),
                ToolPropertySchema("duration_minutes", ToolSchemaType.INTEGER, "Planned duration in minutes [15, 720].", required = true, minimum = 15.0, maximum = 720.0),
                ToolPropertySchema("intensity", ToolSchemaType.ENUM, "Activity intensity.", required = false, enumValues = ActivityIntensity.entries.map { it.name }),
                ToolPropertySchema("body_mass_kg", ToolSchemaType.NUMBER, "Optional body mass in kg [30, 250]; improves the estimate.", required = false, minimum = 30.0, maximum = 250.0),
                ToolPropertySchema("depart_at", ToolSchemaType.STRING, "ISO-8601 departure instant; defaults to now.", required = false, maxLength = 40),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Optional IANA timezone ID.", required = false, maxLength = 64)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("status", ToolSchemaType.STRING, "ESTIMATED."),
                ToolPropertySchema("target_fluid_ml", ToolSchemaType.INTEGER, "Estimated fluid target for the activity."),
                ToolPropertySchema("range_ml", ToolSchemaType.OBJECT, "Plausible low/high range."),
                ToolPropertySchema("sweat_rate_liters_per_hour", ToolSchemaType.NUMBER, "Assumed sweat rate."),
                ToolPropertySchema("heat_basis", ToolSchemaType.STRING, "Which environmental signal drove the estimate."),
                ToolPropertySchema("sodium_mg_range", ToolSchemaType.OBJECT, "Estimated electrolyte replacement range."),
                ToolPropertySchema("schedule", ToolSchemaType.ARRAY, "Pre-activity and during-activity fluid schedule.", arrayItemType = ToolSchemaType.OBJECT),
                ToolPropertySchema("preparation", ToolSchemaType.ARRAY, "Grounded preparation notes.", arrayItemType = ToolSchemaType.STRING),
                ToolPropertySchema("limitations", ToolSchemaType.ARRAY, "Explicit estimate limitations.", arrayItemType = ToolSchemaType.STRING)
            )
        ),
        failureSpec = ToolFailureSpec(
            executionTimeout = Duration.ofSeconds(20),
            documentedErrorCodes = listOf("INVALID_ARGUMENTS", "PERMISSION_DENIED", "NETWORK_UNAVAILABLE", "PROVIDER_ERROR", "UNSUPPORTED_CALCULATION", "TOOL_TIMEOUT")
        )
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<PlanHydrationParams> {
        val location = when (val parsed = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> return parsed
            is EngineResult.Success -> parsed.value
        }
        val duration = rawArgs.getInt("duration_minutes")!!
        if (duration !in 15..720) {
            return EngineResult.Failure(
                EngineError.InvalidInput("duration_minutes", duration.toString(), "duration_minutes must be between 15 and 720.")
            )
        }
        val bodyMass = rawArgs.getDouble("body_mass_kg")
        if (bodyMass != null && bodyMass !in 30.0..250.0) {
            return EngineResult.Failure(
                EngineError.InvalidInput("body_mass_kg", bodyMass.toString(), "body_mass_kg must be between 30 and 250.")
            )
        }
        val departAt = rawArgs.getString("depart_at")?.let {
            when (val parsed = ToolEncodingSupport.parseInstant(it, "depart_at")) {
                is EngineResult.Failure -> return parsed
                is EngineResult.Success -> parsed.value
            }
        } ?: clock.instant()

        return EngineResult.Success(
            PlanHydrationParams(
                location = location,
                departAt = departAt,
                activityCategory = ActivityCategory.valueOf(rawArgs.getString("activity_category")!!),
                durationMinutes = duration,
                intensity = rawArgs.getString("intensity")?.let { ActivityIntensity.valueOf(it) }
                    ?: ActivityIntensity.MODERATE,
                bodyMassKg = bodyMass
            )
        )
    }

    override suspend fun executeTyped(params: PlanHydrationParams): EngineResult<HydrationPlanResult> {
        val hours = params.durationMinutes / 60.0
        val forecastHours = (ceil(hours).toInt() + FORECAST_PADDING_HOURS)
            .coerceIn(MIN_FORECAST_HOURS, MAX_FORECAST_HOURS)

        val forecast: WeatherForecast = when (
            val result = weatherRepository.getHourlyForecast(params.location, forecastHours)
        ) {
            is EngineResult.Failure -> return result
            is EngineResult.Success -> result.value.data
        }

        val point = nearestForecastPointForHydration(forecast, params.departAt)
            ?: return EngineResult.Failure(
                EngineError.UnsupportedCalculation(
                    methodId = "plan_hydration",
                    reason = "INSUFFICIENT_FORECAST_COVERAGE",
                    message = "The forecast does not cover ${params.departAt}; cannot ground a hydration plan."
                )
            )

        val quality = DataQualityAndFreshness.evaluateForecast(
            retrievedAt = point.issuedOrRetrievedAt,
            evaluatedAt = clock.instant(),
            anchorTime = point.validAt,
            deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
            completenessRatio = point.variables.completenessRatio()
        )
        val assessment = assessmentEngine.assessSnapshot(
            location = params.location,
            validAt = point.validAt,
            inputNature = ValueNature.FORECAST,
            inputSource = point.source,
            variables = point.variables,
            quality = quality
        )

        val supported = assessment as? HeatAssessment.Supported
        val airTemperature = point.variables.airTemperature.rawValueOrNull()
        val heatMultiplier = when {
            supported != null -> heatMultiplierFor(supported.category)
            airTemperature != null -> heatMultiplierForTemperature(airTemperature)
            else -> return EngineResult.Failure(
                EngineError.UnsupportedCalculation(
                    methodId = "plan_hydration",
                    reason = "INSUFFICIENT_ENVIRONMENTAL_INPUT",
                    message = "Neither Heat Index nor air temperature is available for ${point.validAt}; " +
                        "a hydration estimate would be ungrounded."
                )
            )
        }

        val baseSweatRate = baseSweatRateFor(params.intensity) * activityFactorFor(params.activityCategory)
        val massFactor = params.bodyMassKg?.let { (it / REFERENCE_BODY_MASS_KG).coerceIn(0.7, 1.5) } ?: 1.0
        val sweatRate = (baseSweatRate * heatMultiplier * massFactor).coerceIn(0.2, 2.0)
        val targetMl = roundTo((sweatRate * hours * 1000.0).roundToInt(), 50).coerceAtLeast(100)
        val minMl = roundTo((targetMl * 0.85).roundToInt(), 50)
        val maxMl = roundTo((targetMl * 1.15).roundToInt(), 50)
        val preHydrationMl = 300
        val targetLiters = targetMl / 1000.0
        val sodiumMin = roundTo((targetLiters * 300).roundToInt(), 10)
        val sodiumMax = roundTo((targetLiters * 700).roundToInt(), 10)

        return EngineResult.Success(
            HydrationPlanResult(
                targetFluidMl = targetMl,
                minFluidMl = minMl,
                maxFluidMl = maxMl,
                preHydrationMl = preHydrationMl,
                sweatRateLitersPerHour = round1(sweatRate),
                assumedBodyMassKg = params.bodyMassKg ?: REFERENCE_BODY_MASS_KG,
                bodyMassProvided = params.bodyMassKg != null,
                heatBasis = if (supported != null) "HEAT_INDEX" else "AIR_TEMPERATURE_ONLY",
                heatIndexCelsius = supported?.heatIndexCelsius?.value,
                heatCategory = supported?.category?.name,
                sodiumMgMin = sodiumMin,
                sodiumMgMax = sodiumMax,
                schedule = buildSchedule(params.durationMinutes, targetMl, preHydrationMl),
                preparation = buildPreparation(targetMl, preHydrationMl),
                limitations = buildList {
                    add(
                        "This is a planning estimate, not medical advice. Individual sweat rates vary " +
                            "roughly 0.5–2.0 L/h with fitness, acclimatisation, clothing, and health."
                    )
                    if (params.bodyMassKg == null) {
                        add("Body mass was not supplied, so ${REFERENCE_BODY_MASS_KG.toInt()} kg was assumed.")
                    }
                    add("Heat Index describes ambient conditions, not core body temperature or sweat loss.")
                    add(
                        "Do not exceed about 1 L/h of plain water during prolonged activity; overdrinking " +
                            "can cause exercise-associated hyponatraemia."
                    )
                    add("Adjust for medication, pregnancy, cardiovascular or kidney conditions, and clinician advice.")
                    if (supported == null) {
                        add("Heat Index could not be computed; air temperature alone drove the heat multiplier.")
                    }
                },
                assessedAt = clock.instant(),
                forecastValidAt = point.validAt
            )
        )
    }

    override fun encodeResult(result: HydrationPlanResult): JsonValue.ObjectValue {
        val schedule = result.schedule.map { entry ->
            JsonValue.ObjectValue(
                linkedMapOf(
                    "at_minute" to SafeJsonCodec.number(entry.atMinute),
                    "fluid_ml" to SafeJsonCodec.number(entry.fluidMl),
                    "note" to JsonValue.StringValue(entry.note)
                )
            )
        }
        return JsonValue.ObjectValue(
            linkedMapOf<String, JsonValue>().apply {
                put("status", JsonValue.StringValue("ESTIMATED"))
                put("data_nature", JsonValue.StringValue(ValueNature.FORECAST.name))
                put("target_fluid_ml", SafeJsonCodec.number(result.targetFluidMl))
                put(
                    "range_ml",
                    JsonValue.ObjectValue(
                        linkedMapOf(
                            "min" to SafeJsonCodec.number(result.minFluidMl),
                            "max" to SafeJsonCodec.number(result.maxFluidMl)
                        )
                    )
                )
                put("pre_hydration_ml", SafeJsonCodec.number(result.preHydrationMl))
                put("sweat_rate_liters_per_hour", SafeJsonCodec.number(result.sweatRateLitersPerHour))
                put("assumed_body_mass_kg", SafeJsonCodec.number(result.assumedBodyMassKg))
                put("body_mass_provided", JsonValue.BooleanValue(result.bodyMassProvided))
                put("heat_basis", JsonValue.StringValue(result.heatBasis))
                result.heatIndexCelsius?.let { put("heat_index_celsius", SafeJsonCodec.number(round1(it))) }
                result.heatCategory?.let { put("heat_category", JsonValue.StringValue(it)) }
                put(
                    "sodium_mg_range",
                    JsonValue.ObjectValue(
                        linkedMapOf(
                            "min" to SafeJsonCodec.number(result.sodiumMgMin),
                            "max" to SafeJsonCodec.number(result.sodiumMgMax)
                        )
                    )
                )
                put("schedule", JsonValue.ArrayValue(schedule))
                put("preparation", JsonValue.ArrayValue(result.preparation.map(JsonValue::StringValue)))
                put("limitations", JsonValue.ArrayValue(result.limitations.map(JsonValue::StringValue)))
                put("assessed_at", JsonValue.StringValue(result.assessedAt.toString()))
                put("forecast_valid_at", JsonValue.StringValue(result.forecastValidAt.toString()))
            }
        )
    }

    private fun buildSchedule(durationMinutes: Int, targetMl: Int, preHydrationMl: Int): List<HydrationScheduleEntry> {
        val entries = mutableListOf(
            HydrationScheduleEntry(
                atMinute = -120,
                fluidMl = preHydrationMl,
                note = "Pre-hydration about two hours before departure, if convenient."
            )
        )
        val segments = (durationMinutes / SEGMENT_MINUTES).coerceAtLeast(1)
        val perSegment = (targetMl.toDouble() / segments).roundToInt().coerceIn(100, 250)
        for (index in 1..segments) {
            val minute = index * SEGMENT_MINUTES
            entries.add(
                HydrationScheduleEntry(
                    atMinute = minute,
                    fluidMl = perSegment,
                    note = when {
                        minute >= durationMinutes -> "Final top-up near the end of the activity."
                        minute == SEGMENT_MINUTES -> "Start drinking early; thirst lags dehydration."
                        else -> "Scheduled drinking interval."
                    }
                )
            )
        }
        return entries
    }

    private fun buildPreparation(targetMl: Int, preHydrationMl: Int): List<String> = listOf(
        "Carry at least ${formatLitres(targetMl + preHydrationMl)} of fluid for this activity.",
        "For efforts longer than 90 minutes, include an electrolyte source and plan a refill point.",
        "Drink ${preHydrationMl}ml before departure rather than starting dehydrated.",
        "Use urine colour as a practical check: pale straw is a reasonable target."
    )

    private fun baseSweatRateFor(intensity: ActivityIntensity): Double = when (intensity) {
        ActivityIntensity.LIGHT -> 0.40
        ActivityIntensity.MODERATE -> 0.70
        ActivityIntensity.VIGOROUS -> 1.00
    }

    private fun activityFactorFor(category: ActivityCategory): Double = when (category) {
        ActivityCategory.RUNNING -> 1.10
        ActivityCategory.OUTDOOR_WORK -> 1.10
        ActivityCategory.CYCLING -> 1.05
        ActivityCategory.WALKING,
        ActivityCategory.COMMUTING,
        ActivityCategory.GENERAL_OUTDOOR -> 1.0
    }

    private fun heatMultiplierFor(category: NwsHeatIndexCategory): Double = when (category) {
        NwsHeatIndexCategory.BELOW_CAUTION_THRESHOLD -> 1.0
        NwsHeatIndexCategory.CAUTION -> 1.15
        NwsHeatIndexCategory.EXTREME_CAUTION -> 1.35
        NwsHeatIndexCategory.DANGER -> 1.55
        NwsHeatIndexCategory.EXTREME_DANGER -> 1.75
    }

    private fun heatMultiplierForTemperature(airTemperatureCelsius: Double): Double = when {
        airTemperatureCelsius >= 40.0 -> 1.55
        airTemperatureCelsius >= 35.0 -> 1.45
        airTemperatureCelsius >= 30.0 -> 1.25
        airTemperatureCelsius >= 25.0 -> 1.10
        else -> 1.0
    }

    private fun nearestForecastPointForHydration(
        forecast: WeatherForecast,
        instant: Instant
    ): HourlyForecastPoint? =
        forecast.hourly
            .minByOrNull { abs(Duration.between(it.validAt, instant).toMinutes()) }
            ?.takeIf { abs(Duration.between(it.validAt, instant).toMinutes()) <= MAX_FORECAST_SNAP_MINUTES }

    private fun roundTo(value: Int, step: Int): Int = ((value.toDouble() / step).roundToInt()) * step

    private fun round1(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0

    private fun formatLitres(millilitres: Int): String =
        if (millilitres % 1000 == 0) "${millilitres / 1000} L" else String.format(java.util.Locale.US, "%.1f L", millilitres / 1000.0)

    companion object {
        const val SEGMENT_MINUTES = 20
        const val MIN_FORECAST_HOURS = 6
        const val MAX_FORECAST_HOURS = 168
        const val FORECAST_PADDING_HOURS = 2
        const val MAX_FORECAST_SNAP_MINUTES = 90L
        const val REFERENCE_BODY_MASS_KG = 70.0
    }
}
