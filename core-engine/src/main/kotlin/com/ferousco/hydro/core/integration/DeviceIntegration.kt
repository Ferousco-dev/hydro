package com.ferousco.hydro.core.integration

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.model.EnvironmentalDataSource
import com.ferousco.hydro.core.model.GeoLocation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

// ============================================================================
// Location
// ============================================================================

/**
 * A single device location fix, with the metadata Hydro needs to be honest about it.
 *
 * [capturedAt] is the platform fix time, **not** the time the tool happened to read it. Hydro never
 * presents a cached fix as a fresh measurement; [LocationSource] callers decide what age is
 * acceptable via `maxAge`.
 */
data class LocationFix(
    val location: GeoLocation,
    val capturedAt: Instant,
    val accuracyMeters: Double?,
    val provider: String,
    val isMock: Boolean = false
)

/**
 * Host-supplied location boundary.
 *
 * The core engine cannot read Android location itself, so `:app` supplies an adapter backed by
 * `LocationManager`/Fused Location. A missing adapter is an explicit failure rather than a
 * fabricated coordinate.
 */
interface LocationSource {
    suspend fun currentLocation(maxAge: Duration): EngineResult<LocationFix>
}

object UnavailableLocationSource : LocationSource {
    override suspend fun currentLocation(maxAge: Duration): EngineResult<LocationFix> =
        EngineResult.Failure(
            EngineError.UnsupportedCalculation(
                methodId = "get_current_location",
                reason = "LOCATION_SOURCE_NOT_CONFIGURED",
                message = "No location source is configured. Grant location permission and wire a " +
                    "LocationSource, or pass explicit coordinates to the weather tools instead."
            )
        )
}

// ============================================================================
// Routing
// ============================================================================

enum class TravelMode(val displayName: String, val assumedSpeedKmh: Double) {
    WALKING("Walking", 4.8),
    CYCLING("Cycling", 15.0),
    DRIVING("Driving", 35.0),
    TRANSIT("Public transport", 22.0)
}

data class RouteRequest(
    val origin: GeoLocation,
    val destination: GeoLocation,
    val travelMode: TravelMode,
    val departAt: Instant
)

/**
 * Deterministic route geometry and duration.
 *
 * [geometry] must contain the origin first, the destination last, and at least one intermediate
 * sample for any route longer than a kilometre. It is deliberately a list of points rather than an
 * encoded polyline: Hydro only needs to sample conditions, and a point list keeps the contract free
 * of a map-SDK dependency.
 */
data class RoutePlan(
    val distanceKm: Double,
    val duration: Duration,
    val geometry: List<GeoLocation>,
    val method: String,
    val limitations: List<String>
) {
    init {
        require(distanceKm.isFinite() && distanceKm > 0.0) { "distanceKm must be finite and positive." }
        require(!duration.isNegative && !duration.isZero) { "duration must be positive." }
        require(geometry.size >= 2) { "geometry must contain at least an origin and a destination." }
        require(method.isNotBlank()) { "method must not be blank." }
    }
}

interface RoutingProvider {
    suspend fun planRoute(request: RouteRequest): EngineResult<RoutePlan>
}

/**
 * Straight-line fallback used when no map SDK is wired.
 *
 * It is a *distance and duration estimate*, not a road route. The returned [RoutePlan] says so in
 * [RoutePlan.method] and [RoutePlan.limitations] so the orchestrator can repeat the caveat instead
 * of presenting it as a real navigation route.
 */
class GreatCircleRoutingProvider(
    private val detourFactor: Double = 1.0,
    private val sampleCount: Int = 5
) : RoutingProvider {

    init {
        require(detourFactor >= 1.0 && detourFactor.isFinite()) { "detourFactor must be finite and >= 1.0." }
        require(sampleCount in 2..25) { "sampleCount must be in 2..25." }
    }

    override suspend fun planRoute(request: RouteRequest): EngineResult<RoutePlan> {
        val straightLineKm = request.origin.distanceKilometersTo(request.destination)
        if (!straightLineKm.isFinite() || straightLineKm <= 0.0) {
            return EngineResult.Failure(
                EngineError.InvalidInput(
                    field = "destination",
                    rejectedValueSummary = "same_as_origin",
                    message = "Origin and destination are the same point; there is no route to evaluate."
                )
            )
        }
        val distanceKm = straightLineKm * detourFactor
        val hours = distanceKm / request.travelMode.assumedSpeedKmh
        val duration = Duration.ofSeconds((hours * 3600.0).toLong().coerceAtLeast(60L))

        val geometry = (0 until sampleCount).map { index ->
            interpolate(request.origin, request.destination, index.toDouble() / (sampleCount - 1))
        }

        return EngineResult.Success(
            RoutePlan(
                distanceKm = distanceKm,
                duration = duration,
                geometry = geometry,
                method = "GREAT_CIRCLE_ESTIMATE",
                limitations = listOf(
                    "Distance is a great-circle estimate (x$detourFactor), not a road-network route.",
                    "Duration assumes a constant ${request.travelMode.assumedSpeedKmh} km/h and ignores " +
                        "traffic, terrain, stops, and accessibility constraints.",
                    "Wire a routing provider (e.g. a map SDK) for road-accurate geometry and ETA."
                )
            )
        )
    }

    private fun interpolate(from: GeoLocation, to: GeoLocation, fraction: Double): GeoLocation =
        GeoLocation(
            latitude = from.latitude + (to.latitude - from.latitude) * fraction,
            longitude = from.longitude + (to.longitude - from.longitude) * fraction,
            elevationMeters = null,
            timeZone = from.timeZone ?: to.timeZone,
            label = when {
                fraction == 0.0 -> from.label
                fraction == 1.0 -> to.label
                else -> null
            }
        )
}

// ============================================================================
// Heat map
// ============================================================================

enum class HeatMapMetric(val canonicalUnit: String) {
    HEAT_INDEX_CELSIUS("°C"),
    AIR_TEMPERATURE_CELSIUS("°C"),
    UV_INDEX("WHO index")
}

data class HeatMapRequest(
    val center: GeoLocation,
    val radiusKm: Double,
    val resolutionKm: Double,
    val validAt: Instant,
    val metric: HeatMapMetric
)

data class HeatMapCell(
    val location: GeoLocation,
    val value: Double,
    val category: String?
)

data class HeatMapLegendEntry(
    val label: String,
    val minValue: Double,
    val maxValueExclusive: Double?,
    val colorHex: String
)

data class HeatMapLayer(
    val metric: HeatMapMetric,
    val validAt: Instant,
    val issuedOrRetrievedAt: Instant,
    val resolutionKm: Double,
    val cells: List<HeatMapCell>,
    val legend: List<HeatMapLegendEntry>,
    val source: EnvironmentalDataSource,
    val limitations: List<String>
) {
    init {
        require(cells.isNotEmpty()) { "A heat-map layer must contain at least one cell." }
        require(legend.isNotEmpty()) { "A heat-map layer must carry legend metadata." }
        require(resolutionKm > 0.0) { "resolutionKm must be positive." }
    }
}

interface HeatMapProvider {
    suspend fun heatMap(request: HeatMapRequest): EngineResult<HeatMapLayer>
}

object UnavailableHeatMapProvider : HeatMapProvider {
    override suspend fun heatMap(request: HeatMapRequest): EngineResult<HeatMapLayer> =
        EngineResult.Failure(
            EngineError.UnsupportedCalculation(
                methodId = "get_heat_map",
                reason = "HEAT_MAP_PROVIDER_NOT_CONFIGURED",
                message = "No gridded heat-map provider is configured. Hydro will not interpolate a " +
                    "map from scattered stations and present it as measured coverage."
            )
        )
}

// ============================================================================
// Wearables
// ============================================================================

enum class WearableMetric(val canonicalUnit: String) {
    HEART_RATE_BPM("bpm"),
    HEART_RATE_VARIABILITY_MS("ms"),
    SKIN_TEMPERATURE_CELSIUS("°C"),
    BODY_TEMPERATURE_CELSIUS("°C"),
    BLOOD_OXYGEN_PERCENT("%"),
    RESPIRATORY_RATE_BREATHS_PER_MIN("breaths/min"),
    STEPS("count"),
    ACTIVE_ENERGY_KCAL("kcal"),
    SWEAT_RATE_LITERS_PER_HOUR("L/h")
}

data class WearableReadRequest(
    val metrics: Set<WearableMetric>,
    val since: Instant,
    val until: Instant,
    val maxSamples: Int
) {
    init {
        require(metrics.isNotEmpty()) { "At least one wearable metric must be requested." }
        require(!until.isBefore(since)) { "until cannot precede since." }
        require(maxSamples > 0) { "maxSamples must be positive." }
    }
}

data class WearableSample(
    val metric: WearableMetric,
    val recordedAt: Instant,
    val value: Double,
    val unit: String,
    val sourceDevice: String?,
    /** Provider-reported quality flag, e.g. `HIGH`, `LOW`, `SLEEPING`, `UNKNOWN`. */
    val quality: String
)

data class WearableReadResult(
    val samples: List<WearableSample>,
    /** Requested metrics that the source cannot supply, with a human-readable reason. */
    val unavailableMetrics: Map<WearableMetric, String>,
    val permissionGranted: Boolean,
    val dataSource: String,
    val limitations: List<String>
)

interface WearableDataSource {
    suspend fun read(request: WearableReadRequest): EngineResult<WearableReadResult>
}

object UnavailableWearableDataSource : WearableDataSource {
    override suspend fun read(request: WearableReadRequest): EngineResult<WearableReadResult> =
        EngineResult.Failure(
            EngineError.UnsupportedCalculation(
                methodId = "get_wearable_data",
                reason = "WEARABLE_SOURCE_NOT_CONFIGURED",
                message = "No wearable/Health Connect source is configured or permitted. Hydro never " +
                    "estimates physiological readings."
            )
        )
}

// ============================================================================
// Notifications
// ============================================================================

enum class NotificationSeverity {
    INFO,
    WATCH,
    WARNING,
    CRITICAL
}

enum class NotificationCategory {
    HEAT,
    UV,
    AIR_QUALITY,
    WEATHER_CHANGE,
    HYDRATION,
    ROUTINE
}

/** What produced the alert. Recorded so the UI and diagnostics can explain the trigger. */
enum class NotificationTrigger {
    USER_REQUEST,
    HEAT_THRESHOLD,
    FORECAST_CHANGE,
    WEARABLE_ANOMALY,
    ROUTINE
}

data class NotificationIntent(
    val title: String,
    val body: String,
    val severity: NotificationSeverity,
    val category: NotificationCategory,
    val dedupKey: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val trigger: NotificationTrigger,
    val confidence: Double,
    val evidence: List<String>,
    val location: GeoLocation?
)

enum class NotificationSuppressionReason {
    /** The user turned alerts off in their saved preferences. */
    USER_DISABLED,

    /** The alert's severity is below the minimum the user asked to be told about. */
    BELOW_MINIMUM_SEVERITY,
    DUPLICATE,
    RATE_LIMITED,
    QUIET_HOURS,
    EXPIRED,
    LOW_CONFIDENCE
}

sealed interface NotificationReceipt {
    data class Submitted(
        val notificationId: String,
        val submittedAt: Instant
    ) : NotificationReceipt

    data class Suppressed(
        val reason: NotificationSuppressionReason,
        val explanation: String,
        val retryAfter: Instant? = null
    ) : NotificationReceipt

    data class Rejected(val explanation: String) : NotificationReceipt
}

/**
 * Host-supplied bridge to the Android notification layer.
 *
 * The sink only *delivers* an intent. Deduplication, quiet hours, rate limiting, and confidence
 * thresholds are enforced in `CreateNotificationTool` before the sink is called, so a language model
 * cannot bypass policy by crafting arguments.
 */
interface NotificationSink {
    suspend fun submit(intent: NotificationIntent): EngineResult<NotificationReceipt>
}

object UnavailableNotificationSink : NotificationSink {
    override suspend fun submit(intent: NotificationIntent): EngineResult<NotificationReceipt> =
        EngineResult.Failure(
            EngineError.UnsupportedCalculation(
                methodId = "create_notification",
                reason = "NOTIFICATION_SINK_NOT_CONFIGURED",
                message = "No Android notification sink is configured. The alert was not delivered."
            )
        )
}

/**
 * Deterministic delivery policy applied before any alert reaches the platform.
 *
 * Defaults are deliberately conservative: at most three alerts an hour, six-hour deduplication, and
 * no non-critical alerts overnight. Hydro must not become a source of notification spam.
 */
data class NotificationPolicy(
    val maxNotificationsPerHour: Int = 3,
    val dedupWindow: Duration = Duration.ofHours(6),
    val quietHoursStart: LocalTime = LocalTime.of(22, 0),
    val quietHoursEnd: LocalTime = LocalTime.of(7, 0),
    val allowCriticalDuringQuietHours: Boolean = true,
    val minimumConfidence: Double = 0.5,
    val minimumCriticalConfidence: Double = 0.8,
    val defaultTimeZone: ZoneId = ZoneOffset.UTC
) {
    init {
        require(maxNotificationsPerHour > 0) { "maxNotificationsPerHour must be positive." }
        require(!dedupWindow.isNegative && !dedupWindow.isZero) { "dedupWindow must be positive." }
        require(minimumConfidence in 0.0..1.0) { "minimumConfidence must be in [0, 1]." }
        require(minimumCriticalConfidence in 0.0..1.0) {
            "minimumCriticalConfidence must be in [0, 1]."
        }
    }

    /** Handles windows that cross midnight (the default 22:00 → 07:00 does). */
    fun isQuietHours(localTime: LocalTime): Boolean =
        if (quietHoursStart <= quietHoursEnd) {
            localTime >= quietHoursStart && localTime < quietHoursEnd
        } else {
            localTime >= quietHoursStart || localTime < quietHoursEnd
        }
}

/**
 * Process-local sink used by tests and by the JVM core engine.
 *
 * It records what *would* have been posted. It does not touch the Android notification tray.
 */
class InMemoryNotificationSink(
    private val clock: Clock = Clock.systemUTC()
) : NotificationSink {
    private val mutex = Mutex()
    private val delivered = mutableListOf<NotificationIntent>()

    suspend fun deliveredIntents(): List<NotificationIntent> = mutex.withLock { delivered.toList() }

    override suspend fun submit(intent: NotificationIntent): EngineResult<NotificationReceipt> =
        mutex.withLock {
            delivered.add(intent)
            EngineResult.Success(
                NotificationReceipt.Submitted(
                    notificationId = UUID.randomUUID().toString(),
                    submittedAt = clock.instant()
                )
            )
        }
}
