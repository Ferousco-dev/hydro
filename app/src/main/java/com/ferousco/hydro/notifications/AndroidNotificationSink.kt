package com.ferousco.hydro.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ferousco.hydro.R
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.integration.NotificationCategory
import com.ferousco.hydro.core.integration.NotificationIntent
import com.ferousco.hydro.core.integration.NotificationReceipt
import com.ferousco.hydro.core.integration.NotificationSeverity
import com.ferousco.hydro.core.integration.NotificationSink
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android bridge for the `create_notification` tool.
 *
 * The sink only delivers. Deduplication, quiet hours, rate limiting, confidence thresholds, and
 * user preferences are enforced by `CreateNotificationTool` before [submit] is called. If the
 * platform permission is missing, the sink returns [NotificationReceipt.Rejected] rather than
 * pretending the alert was delivered.
 *
 * Two channels are used so a `CRITICAL` alert can bypass the default channel's reduced interruption
 * level on Android 8+. Channel importance is a user-controlled setting; Hydro never changes it after
 * creation.
 */
class AndroidNotificationSink(
    private val context: Context,
    private val clock: Clock = Clock.systemUTC()
) : NotificationSink {

    private val notificationIds = AtomicInteger(INITIAL_NOTIFICATION_ID)

    init {
        ensureChannels()
    }

    @SuppressLint("MissingPermission")
    override suspend fun submit(intent: NotificationIntent): EngineResult<NotificationReceipt> {
        if (!hasPostNotificationPermission()) {
            return EngineResult.Success(
                NotificationReceipt.Rejected(
                    explanation = "Android notification permission has not been granted, so the alert " +
                        "was not posted."
                )
            )
        }

        val id = notificationIds.incrementAndGet()
        val channelId = channelFor(intent.severity)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_hydro_notification)
            .setContentTitle(intent.title)
            .setContentText(intent.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(intent.body))
            .setPriority(priorityFor(intent.severity))
            .setCategory(androidCategoryFor(intent.category))
            .setAutoCancel(true)
            .setWhen(intent.createdAt.toEpochMilli())
            .setShowWhen(true)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            EngineResult.Success(
                NotificationReceipt.Submitted(
                    notificationId = id.toString(),
                    submittedAt = clock.instant()
                )
            )
        } catch (security: SecurityException) {
            // Permission can be revoked between the check and notify().
            EngineResult.Success(
                NotificationReceipt.Rejected(
                    explanation = "Android rejected the notification because permission is no longer granted."
                )
            )
        } catch (ex: Exception) {
            EngineResult.Failure(
                EngineError.ProviderError(
                    providerId = "android_notification_manager",
                    statusCode = null,
                    providerReason = ex::class.simpleName,
                    isTransient = false,
                    message = "Android could not post the notification: ${ex.message}"
                )
            )
        }
    }

    private fun hasPostNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val standard = NotificationChannel(
            STANDARD_CHANNEL_ID,
            "Hydro environmental alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Heat, UV, and weather-change alerts from Hydro."
            enableVibration(true)
        }
        val critical = NotificationChannel(
            CRITICAL_CHANNEL_ID,
            "Hydro critical heat alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Severe heat alerts that may need immediate attention."
            enableVibration(true)
        }
        manager.createNotificationChannels(listOf(standard, critical))
    }

    private fun channelFor(severity: NotificationSeverity): String =
        if (severity == NotificationSeverity.CRITICAL) CRITICAL_CHANNEL_ID else STANDARD_CHANNEL_ID

    private fun priorityFor(severity: NotificationSeverity): Int = when (severity) {
        NotificationSeverity.INFO -> NotificationCompat.PRIORITY_LOW
        NotificationSeverity.WATCH -> NotificationCompat.PRIORITY_DEFAULT
        NotificationSeverity.WARNING -> NotificationCompat.PRIORITY_DEFAULT
        NotificationSeverity.CRITICAL -> NotificationCompat.PRIORITY_HIGH
    }

    private fun androidCategoryFor(category: NotificationCategory): String = when (category) {
        NotificationCategory.HEAT,
        NotificationCategory.UV,
        NotificationCategory.AIR_QUALITY -> NotificationCompat.CATEGORY_REMINDER

        NotificationCategory.WEATHER_CHANGE -> NotificationCompat.CATEGORY_STATUS
        NotificationCategory.HYDRATION -> NotificationCompat.CATEGORY_REMINDER
        NotificationCategory.ROUTINE -> NotificationCompat.CATEGORY_STATUS
    }

    companion object {
        const val STANDARD_CHANNEL_ID = "hydro_alerts"
        const val CRITICAL_CHANNEL_ID = "hydro_alerts_critical"

        /** Leaves room for other app notifications without colliding. */
        private const val INITIAL_NOTIFICATION_ID = 1_000
    }
}
