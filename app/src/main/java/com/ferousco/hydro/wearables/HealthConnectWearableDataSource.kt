package com.ferousco.hydro.wearables

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.integration.WearableDataSource
import com.ferousco.hydro.core.integration.WearableMetric
import com.ferousco.hydro.core.integration.WearableReadRequest
import com.ferousco.hydro.core.integration.WearableReadResult
import com.ferousco.hydro.core.integration.WearableSample
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Reads authorised measurements from Android Health Connect (Wear OS, Fitbit, Samsung Health, Oura
 * and others sync into it).
 *
 * Hydro only ever *reads*, only the metrics the user granted, and never estimates a missing value.
 * Skin temperature and sweat rate have no stable Health Connect record, so they are reported as
 * unavailable rather than approximated.
 */
class HealthConnectWearableDataSource(private val context: Context) : WearableDataSource {

    companion object {
        /** Record types Hydro can read; the permission request flow should ask for exactly these. */
        val READABLE_RECORDS: Map<WearableMetric, KClass<out Record>> = mapOf(
            WearableMetric.HEART_RATE_BPM to HeartRateRecord::class,
            WearableMetric.HEART_RATE_VARIABILITY_MS to HeartRateVariabilityRmssdRecord::class,
            WearableMetric.BODY_TEMPERATURE_CELSIUS to BodyTemperatureRecord::class,
            WearableMetric.BLOOD_OXYGEN_PERCENT to OxygenSaturationRecord::class,
            WearableMetric.RESPIRATORY_RATE_BREATHS_PER_MIN to RespiratoryRateRecord::class,
            WearableMetric.STEPS to StepsRecord::class,
            WearableMetric.ACTIVE_ENERGY_KCAL to ActiveCaloriesBurnedRecord::class
        )

        val REQUIRED_PERMISSIONS: Set<String> =
            READABLE_RECORDS.values.map { HealthPermission.getReadPermission(it) }.toSet()

        private const val SOURCE = "android-health-connect"

        /** Contract for the system screen where the user allows or denies each metric. */
        fun permissionContract() = PermissionController.createRequestPermissionResultContract()
    }

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** Where the user is in connecting their health data. */
    sealed interface Connection {
        /** This phone cannot run Health Connect. */
        data object NotSupported : Connection

        /** Health Connect is missing or needs updating from Google Play. */
        data object NeedsInstall : Connection

        /** Available, but the user has not allowed Hydro to read any metric yet. */
        data object NeedsPermission : Connection

        data class Connected(val metrics: Set<WearableMetric>) : Connection
    }

    suspend fun connection(): Connection {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_UNAVAILABLE -> return Connection.NotSupported
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> return Connection.NeedsInstall
        }
        return try {
            val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
            val metrics = READABLE_RECORDS.filterValues { HealthPermission.getReadPermission(it) in granted }.keys
            if (metrics.isEmpty()) Connection.NeedsPermission else Connection.Connected(metrics)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            Connection.NeedsPermission
        }
    }

    /** The newest reading of [metric] from the last [within], or null if there is none to show. */
    suspend fun latest(metric: WearableMetric, within: Duration, now: Instant = Instant.now()): WearableSample? {
        val result = read(WearableReadRequest(setOf(metric), now.minus(within), now, maxSamples = 200))
        return (result as? EngineResult.Success)?.value?.samples?.maxByOrNull { it.recordedAt }
    }

    /** Sum of step counts since [since]; null when steps are not allowed or there are none. */
    suspend fun stepsSince(since: Instant, now: Instant = Instant.now()): Int? {
        val result = read(WearableReadRequest(setOf(WearableMetric.STEPS), since, now, maxSamples = 1000))
        val samples = (result as? EngineResult.Success)?.value?.samples?.filter { it.metric == WearableMetric.STEPS }.orEmpty()
        return if (samples.isEmpty()) null else samples.sumOf { it.value }.toInt()
    }

    /** Removes every permission the user gave Hydro. Their data in Health Connect is untouched. */
    suspend fun disconnect() {
        try {
            HealthConnectClient.getOrCreate(context).permissionController.revokeAllPermissions()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Nothing to revoke, or Health Connect is unavailable.
        }
    }

    override suspend fun read(request: WearableReadRequest): EngineResult<WearableReadResult> {
        if (!isAvailable()) {
            return EngineResult.Failure(
                EngineError.UnsupportedCalculation(
                    methodId = "get_wearable_data",
                    reason = "HEALTH_CONNECT_NOT_AVAILABLE",
                    message = "Health Connect is not installed or not supported on this device."
                )
            )
        }
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val unavailable = linkedMapOf<WearableMetric, String>()
            val samples = ArrayList<WearableSample>()

            for (metric in request.metrics) {
                val type = READABLE_RECORDS[metric]
                if (type == null) {
                    unavailable[metric] = "Health Connect has no stable record type for this metric."
                    continue
                }
                if (HealthPermission.getReadPermission(type) !in granted) {
                    unavailable[metric] = "The user has not granted read access to this metric."
                    continue
                }
                samples += read(client, metric, type, request)
            }

            EngineResult.Success(
                WearableReadResult(
                    samples = samples.sortedByDescending { it.recordedAt }.take(request.maxSamples),
                    unavailableMetrics = unavailable,
                    permissionGranted = unavailable.size < request.metrics.size,
                    dataSource = SOURCE,
                    limitations = listOf(
                        "Values are whatever the user's devices synced into Health Connect; they may be delayed or sparse.",
                        "Wearable data cannot establish dehydration, core body temperature or a medical diagnosis."
                    )
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            EngineResult.Failure(
                EngineError.PolicyDenied("WEARABLE_READ", "Health Connect permission was denied or revoked.")
            )
        } catch (t: Throwable) {
            EngineResult.Failure(
                EngineError.ProviderError(
                    providerId = SOURCE,
                    statusCode = null,
                    providerReason = t::class.simpleName,
                    isTransient = true,
                    message = "Health Connect read failed: ${t::class.simpleName}."
                )
            )
        }
    }

    private suspend fun read(
        client: HealthConnectClient,
        metric: WearableMetric,
        type: KClass<out Record>,
        request: WearableReadRequest
    ): List<WearableSample> {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = TimeRangeFilter.between(request.since, request.until),
                pageSize = request.maxSamples.coerceAtMost(1000)
            )
        )
        return response.records.flatMap { record -> samplesFor(metric, record) }
    }

    private fun samplesFor(metric: WearableMetric, record: Record): List<WearableSample> {
        val device = record.metadata.device?.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ") }
        fun sample(at: java.time.Instant, value: Double) =
            WearableSample(metric, at, value, metric.canonicalUnit, device?.ifBlank { null }, "UNKNOWN")
        return when (record) {
            is HeartRateRecord -> record.samples.map { sample(it.time, it.beatsPerMinute.toDouble()) }
            is HeartRateVariabilityRmssdRecord -> listOf(sample(record.time, record.heartRateVariabilityMillis))
            is BodyTemperatureRecord -> listOf(sample(record.time, record.temperature.inCelsius))
            is OxygenSaturationRecord -> listOf(sample(record.time, record.percentage.value))
            is RespiratoryRateRecord -> listOf(sample(record.time, record.rate))
            is StepsRecord -> listOf(sample(record.endTime, record.count.toDouble()))
            is ActiveCaloriesBurnedRecord -> listOf(sample(record.endTime, record.energy.inKilocalories))
            else -> emptyList()
        }
    }
}
