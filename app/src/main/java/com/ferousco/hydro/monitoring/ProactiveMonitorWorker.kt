package com.ferousco.hydro.monitoring

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ferousco.hydro.HydroApplication
import com.ferousco.hydro.core.HydroEngine
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.PersistenceWriteResult
import com.ferousco.hydro.core.model.WeatherForecast
import com.ferousco.hydro.core.preferences.UserPreferences
import com.ferousco.hydro.core.proactive.ProactiveEvaluationRequest
import com.ferousco.hydro.location.DeviceLocationProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException

/**
 * One background monitoring pass: resolve a place, fetch conditions, evaluate, deliver.
 *
 * The worker contains no thresholds and no notification wording. It only assembles a
 * [ProactiveEvaluationRequest] from what the providers actually returned and hands it to
 * [com.ferousco.hydro.core.HydroEngine.runProactiveCheck]. That keeps the decision auditable and
 * identical to a foreground check.
 *
 * Failure philosophy:
 * - No location and no saved home location → nothing to monitor; finish quietly.
 * - Both providers failed → retry with backoff.
 * - Only one provider failed → evaluate what exists; a missing forecast is not an error.
 */
class ProactiveMonitorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val deviceLocation: DeviceLocationProvider = DeviceLocationProvider(appContext)

    override suspend fun doWork(): Result {
        val app = applicationContext as? HydroApplication
            ?: return Result.failure(workDataOf(KEY_STATUS to STATUS_NO_APP))

        return try {
            runCheck(app)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ex: Exception) {
            Log.w(TAG, "Proactive monitoring pass failed; will retry with backoff.", ex)
            Result.retry()
        }
    }

    private suspend fun runCheck(app: HydroApplication): Result {
        val engine = app.engine
        val evaluatedAt = Clock.systemUTC().instant()

        val preferences = (engine.loadUserPreferences() as? EngineResult.Success)?.value

        // A user who turned alerts off should not cost a network round-trip every 30 minutes.
        if (preferences != null && !preferences.alerts.enabled) {
            return Result.success(workDataOf(KEY_STATUS to STATUS_ALERTS_DISABLED))
        }

        val location = resolveLocation(preferences)
            ?: return Result.success(workDataOf(KEY_STATUS to STATUS_NO_LOCATION))
        val zone = location.timeZone ?: ZoneId.systemDefault()

        val observationResult = engine.weatherRepository.getCurrentObservation(location)
        val forecastResult = engine.weatherRepository.getHourlyForecast(
            location = location,
            forecastHours = FORECAST_HOURS
        )

        val observation = (observationResult as? EngineResult.Success)?.value
        val forecast = (forecastResult as? EngineResult.Success)?.value

        if (observation == null && forecast == null) {
            Log.i(TAG, "No observation or forecast available; retrying later.")
            return Result.retry()
        }

        // `allowStaleInput = true` is deliberate: a stale reading still produces a Supported
        // assessment whose freshness is STALE, and the notification policy suppresses it on
        // confidence. Rejecting it here would make the pass indistinguishable from "no data".
        val currentAssessment: HeatAssessment? = observation?.data?.let {
            engine.assessmentEngine.assessObservation(it, allowStaleInput = true)
        }

        val nearTerm = forecast?.data?.let {
            ProactiveMonitorLogic.nearTermAssessments(
                forecast = it,
                assessmentEngine = engine.assessmentEngine,
                evaluatedAt = evaluatedAt,
                allowStaleInput = true
            )
        }.orEmpty()
        val forecastPeak = ProactiveMonitorLogic.peak(nearTerm)

        val storage = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val categoryKey = ProactiveMonitorLogic.forecastCategoryKey(location, evaluatedAt, zone)
        val previousCategory = ProactiveMonitorLogic.parseCategory(storage.getString(categoryKey, null))

        // Feed local memory even when the pass raises no alert. Without this, forecast verification
        // and the same-hour anomaly baseline would only ever see data from app-opened sessions.
        persistSnapshot(
            engine = engine,
            observation = observation?.data,
            assessment = currentAssessment,
            forecast = forecast?.data,
            evaluatedAt = evaluatedAt
        )
        maybeEnforceRetention(engine = engine, storage = storage, now = evaluatedAt)

        val result = engine.runProactiveCheck(
            ProactiveEvaluationRequest(
                location = location,
                evaluatedAt = evaluatedAt,
                timeZone = zone,
                currentAssessment = currentAssessment,
                forecastAssessment = forecastPeak,
                previousForecastCategory = previousCategory,
                // A saved preference is not a plan. Deterioration alerts stay off until the user
                // records an actual outdoor activity, which is a UI concern.
                activityCategory = null,
                plannedOutdoorMinutes = null
            )
        )

        // Record the forecast's current category only after a successful evaluation. If the
        // provider failed this pass, keeping the older value is more honest than clearing it.
        if (forecastPeak != null) {
            storage.edit()
                .putString(categoryKey, forecastPeak.category.name)
                .apply()
        }

        val decision = result.decision
        Log.i(
            TAG,
            "Proactive pass: ${decision.alerts.size} alert(s), ${result.submittedCount} submitted, " +
                "${result.suppressedCount} suppressed. ${decision.rationale.take(2).joinToString(" ")}"
        )

        return Result.success(
            workDataOf(
                KEY_STATUS to STATUS_COMPLETED,
                KEY_ALERTS to decision.alerts.size,
                KEY_SUBMITTED to result.submittedCount,
                KEY_SUPPRESSED to result.suppressedCount,
                KEY_LAST_RATIONALE to decision.rationale.take(2).joinToString(" ").take(MAX_RATIONALE_CHARS)
            )
        )
    }

    /**
     * Best-effort local memory write.
     *
     * Persistence failures are logged and ignored: a full disk or a retention policy must never turn
     * a successful weather fetch into a failed monitoring pass. Observation and forecast IDs are
     * deterministic, so a repeated fetch of the same timestamp upserts instead of duplicating.
     */
    private suspend fun persistSnapshot(
        engine: HydroEngine,
        observation: EnvironmentalObservation?,
        assessment: HeatAssessment?,
        forecast: WeatherForecast?,
        evaluatedAt: Instant
    ) {
        var linkedObservationId: String? = null

        if (observation != null) {
            when (val write = engine.historyRepository.recordObservation(observation)) {
                is EngineResult.Success ->
                    linkedObservationId = (write.value as? PersistenceWriteResult.Stored)?.record?.id

                is EngineResult.Failure ->
                    Log.w(TAG, "Could not persist observation: ${write.error.message}")
            }
        }

        if (assessment != null) {
            // Reuse the observation id so the same reading is not assessed twice across passes.
            val assessmentId = linkedObservationId?.let { "assessment:$it" }
            val write = if (assessmentId != null) {
                engine.historyRepository.recordHeatAssessment(
                    assessment = assessment,
                    linkedEnvironmentalRecordId = linkedObservationId,
                    recordId = assessmentId
                )
            } else {
                engine.historyRepository.recordHeatAssessment(
                    assessment = assessment,
                    linkedEnvironmentalRecordId = linkedObservationId
                )
            }
            if (write is EngineResult.Failure) {
                Log.w(TAG, "Could not persist assessment: ${write.error.message}")
            }
        }

        if (forecast != null) {
            val horizonEnd = evaluatedAt.plus(ProactiveMonitorLogic.NEAR_TERM_HORIZON)
            forecast.hourly
                .filter { !it.validAt.isBefore(evaluatedAt) && !it.validAt.isAfter(horizonEnd) }
                .forEach { point ->
                    val write = engine.historyRepository.recordIssuedForecastPoint(
                        point = point,
                        timeZone = forecast.timeZone
                    )
                    if (write is EngineResult.Failure) {
                        Log.w(TAG, "Could not persist forecast point: ${write.error.message}")
                    }
                }
        }
    }

    /**
     * Runs retention at most once a day.
     *
     * Background passes now write history every 30 minutes, so an unbounded store would grow quietly.
     * The prune itself lists all records, which is too expensive for every pass; a daily guard keeps
     * it bounded without making the user wait for a separate cleanup job.
     */
    private suspend fun maybeEnforceRetention(
        engine: HydroEngine,
        storage: SharedPreferences,
        now: Instant
    ) {
        val lastRun = storage.getLong(KEY_LAST_RETENTION_EPOCH_SECONDS, 0L)
        val elapsed = now.epochSecond - lastRun
        if (lastRun > 0L && elapsed < RETENTION_INTERVAL.seconds) return

        when (val pruned = engine.historyRepository.enforceRetentionPolicy(now)) {
            is EngineResult.Success -> {
                storage.edit().putLong(KEY_LAST_RETENTION_EPOCH_SECONDS, now.epochSecond).apply()
                Log.i(
                    TAG,
                    "Retention pruned ${pruned.value.prunedEnvironmentalRecords} records and " +
                        "${pruned.value.prunedAssessmentRecords} assessments."
                )
            }

            is EngineResult.Failure ->
                Log.w(TAG, "Retention pass failed: ${pruned.error.message}")
        }
    }

    /**
     * Prefers a recent device fix, then the user's saved home location.
     *
     * A stale fix is not used as a fallback because it may describe a place the user left days ago;
     * alerting for the wrong city is worse than staying silent.
     */
    private fun resolveLocation(preferences: UserPreferences?): GeoLocation? {
        val fix = deviceLocation.lastKnownFix()
        val now = Clock.systemUTC().instant()
        val isRecent = fix != null &&
            !Duration.between(fix.capturedAt, now).isNegative &&
            Duration.between(fix.capturedAt, now) <= MAX_FIX_AGE

        return when {
            isRecent -> fix.location
            preferences?.activity?.homeLocation != null -> preferences.activity.homeLocation
            else -> null
        }
    }

    companion object {
        private const val TAG = "HydroProactive"

        private const val PREFS_NAME = "hydro_proactive_monitor"

        /** Long enough to cover a full forecast deterioration, short enough to stay one request. */
        private const val FORECAST_HOURS = 12

        /** Beyond this, a cached fix is treated as a different place rather than a location. */
        private val MAX_FIX_AGE: Duration = Duration.ofHours(6)

        private const val MAX_RATIONALE_CHARS = 2_000

        /** At most one retention prune per day; the pass itself runs every 30 minutes. */
        private val RETENTION_INTERVAL: Duration = Duration.ofHours(24)

        private const val KEY_LAST_RETENTION_EPOCH_SECONDS = "last_retention_epoch_seconds"

        const val KEY_STATUS = "status"
        const val KEY_ALERTS = "alerts"
        const val KEY_SUBMITTED = "submitted"
        const val KEY_SUPPRESSED = "suppressed"
        const val KEY_LAST_RATIONALE = "rationale"

        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_ALERTS_DISABLED = "ALERTS_DISABLED"
        const val STATUS_NO_LOCATION = "NO_LOCATION"
        const val STATUS_NO_APP = "NO_APPLICATION"
    }
}
