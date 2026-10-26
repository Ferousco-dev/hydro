package com.ferousco.hydro.core.preferences

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.integration.NotificationSeverity
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.ActivityIntensity
import com.ferousco.hydro.core.model.GeoLocation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

// ============================================================================
// Models
// ============================================================================

enum class TemperatureUnitPreference { CELSIUS, FAHRENHEIT }

enum class DistanceUnitPreference { KILOMETERS, MILES }

enum class VolumeUnitPreference { MILLILITRES, FLUID_OUNCES }

/**
 * User-controlled notification settings.
 *
 * These are the values Hydro applies *in addition to* its hard safety limits. A user can disable
 * alerts or widen quiet hours, but cannot disable deduplication entirely.
 */
data class AlertPreferences(
    val enabled: Boolean = true,
    val minimumSeverity: NotificationSeverity = NotificationSeverity.WATCH,
    val quietHoursStart: LocalTime = LocalTime.of(22, 0),
    val quietHoursEnd: LocalTime = LocalTime.of(7, 0),
    val allowCriticalDuringQuietHours: Boolean = true,
    val maxNotificationsPerHour: Int = 3,
    val dedupWindowHours: Int = 6
) {
    init {
        require(maxNotificationsPerHour in 1..20) {
            "maxNotificationsPerHour must be within [1, 20]."
        }
        require(dedupWindowHours in 1..72) {
            "dedupWindowHours must be within [1, 72]."
        }
    }
}

data class SavedLocation(
    val label: String,
    val location: GeoLocation
) {
    init {
        require(label.isNotBlank()) { "SavedLocation label must not be blank." }
    }
}

/**
 * Activity defaults and consented personalisation context.
 *
 * [bodyMassKg], [homeLocation], and [savedLocations] are personal data. They are only exposed to
 * the language model when [UserPreferences.consentedPersonalization] is `true`.
 */
data class ActivityPreferences(
    val preferredActivities: Set<ActivityCategory> = emptySet(),
    val defaultIntensity: ActivityIntensity = ActivityIntensity.MODERATE,
    val bodyMassKg: Double? = null,
    val homeLocation: GeoLocation? = null,
    val savedLocations: List<SavedLocation> = emptyList()
) {
    init {
        bodyMassKg?.let {
            require(it.isFinite() && it in 30.0..250.0) {
                "bodyMassKg must be finite and within [30, 250]."
            }
        }
    }

    val hasPersonalisationData: Boolean
        get() = bodyMassKg != null || homeLocation != null || savedLocations.isNotEmpty()
}

/**
 * Local, queryable user preferences.
 *
 * This is deliberately a data record rather than prompt text: the orchestrator can read units,
 * alert settings, and consented activity defaults through `query_memory`, and deterministic code
 * can apply them without asking the model to remember anything.
 */
data class UserPreferences(
    val temperatureUnit: TemperatureUnitPreference = TemperatureUnitPreference.CELSIUS,
    val distanceUnit: DistanceUnitPreference = DistanceUnitPreference.KILOMETERS,
    val volumeUnit: VolumeUnitPreference = VolumeUnitPreference.MILLILITRES,
    val alerts: AlertPreferences = AlertPreferences(),
    val activity: ActivityPreferences = ActivityPreferences(),
    val consentedPersonalization: Boolean = false,
    val updatedAt: Instant? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

// ============================================================================
// Store boundary
// ============================================================================

interface UserPreferencesStore {
    suspend fun load(): EngineResult<UserPreferences>

    suspend fun save(preferences: UserPreferences): EngineResult<UserPreferences>

    suspend fun update(transform: (UserPreferences) -> UserPreferences): EngineResult<UserPreferences> =
        when (val loaded = load()) {
            is EngineResult.Failure -> loaded
            is EngineResult.Success -> save(transform(loaded.value))
        }
}

/**
 * Process-local store used by the JVM engine and tests.
 *
 * The `:app` module supplies a Room-backed implementation so preferences survive process death.
 */
class InMemoryUserPreferencesStore(
    initial: UserPreferences = UserPreferences(),
    private val clock: Clock = Clock.systemUTC()
) : UserPreferencesStore {

    private val mutex = Mutex()
    private var current: UserPreferences = initial

    override suspend fun load(): EngineResult<UserPreferences> =
        mutex.withLock { EngineResult.Success(current) }

    override suspend fun save(preferences: UserPreferences): EngineResult<UserPreferences> =
        mutex.withLock {
            val stamped = preferences.copy(
                updatedAt = clock.instant(),
                schemaVersion = UserPreferences.CURRENT_SCHEMA_VERSION
            )
            current = stamped
            EngineResult.Success(stamped)
        }
}

// ============================================================================
// JSON codec
// ============================================================================

/**
 * Versioned JSON encoding for durable preference storage.
 *
 * Decoding is strict about ranges and enum names but tolerant of unknown fields, so a future
 * schema addition does not brick an older build. A corrupt payload is reported as a persistence
 * failure rather than silently replaced with defaults.
 */
object UserPreferencesCodec {

    fun encodeToString(preferences: UserPreferences): String =
        SafeJsonCodec.stringify(encode(preferences))

    fun encode(preferences: UserPreferences): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf(
            "schema_version" to SafeJsonCodec.number(preferences.schemaVersion),
            "units" to JsonValue.ObjectValue(
                linkedMapOf(
                    "temperature" to JsonValue.StringValue(preferences.temperatureUnit.name),
                    "distance" to JsonValue.StringValue(preferences.distanceUnit.name),
                    "volume" to JsonValue.StringValue(preferences.volumeUnit.name)
                )
            ),
            "alerts" to JsonValue.ObjectValue(
                linkedMapOf(
                    "enabled" to JsonValue.BooleanValue(preferences.alerts.enabled),
                    "minimum_severity" to JsonValue.StringValue(preferences.alerts.minimumSeverity.name),
                    "quiet_hours_start" to JsonValue.StringValue(preferences.alerts.quietHoursStart.toString()),
                    "quiet_hours_end" to JsonValue.StringValue(preferences.alerts.quietHoursEnd.toString()),
                    "allow_critical_during_quiet_hours" to JsonValue.BooleanValue(
                        preferences.alerts.allowCriticalDuringQuietHours
                    ),
                    "max_notifications_per_hour" to SafeJsonCodec.number(
                        preferences.alerts.maxNotificationsPerHour
                    ),
                    "dedup_window_hours" to SafeJsonCodec.number(preferences.alerts.dedupWindowHours)
                )
            ),
            "activity" to JsonValue.ObjectValue(
                linkedMapOf<String, JsonValue>().apply {
                    put(
                        "preferred_activities",
                        JsonValue.ArrayValue(
                            preferences.activity.preferredActivities
                                .sortedBy { it.name }
                                .map { JsonValue.StringValue(it.name) }
                        )
                    )
                    put(
                        "default_intensity",
                        JsonValue.StringValue(preferences.activity.defaultIntensity.name)
                    )
                    preferences.activity.bodyMassKg?.let {
                        put("body_mass_kg", SafeJsonCodec.number(it))
                    }
                    preferences.activity.homeLocation?.let {
                        put("home_location", encodeLocation(it))
                    }
                    if (preferences.activity.savedLocations.isNotEmpty()) {
                        put(
                            "saved_locations",
                            JsonValue.ArrayValue(
                                preferences.activity.savedLocations.map { saved ->
                                    JsonValue.ObjectValue(
                                        linkedMapOf(
                                            "label" to JsonValue.StringValue(saved.label),
                                            "location" to encodeLocation(saved.location)
                                        )
                                    )
                                }
                            )
                        )
                    }
                }
            ),
            "consented_personalization" to JsonValue.BooleanValue(
                preferences.consentedPersonalization
            ),
            "updated_at" to (preferences.updatedAt?.let { JsonValue.StringValue(it.toString()) }
                ?: JsonValue.NullValue)
        )
    )

    fun decodeFromString(raw: String): EngineResult<UserPreferences> {
        val parsed = SafeJsonCodec.parseObject(
            input = raw,
            sourceName = "user_preferences",
            maxChars = MAX_PREFERENCES_JSON_CHARS,
            maxDepth = MAX_PREFERENCES_JSON_DEPTH
        )
        return when (parsed) {
            is EngineResult.Failure -> EngineResult.Failure(
                EngineError.PersistenceFailure(
                    operation = "decode_user_preferences",
                    message = "Stored preferences are not valid JSON: ${parsed.error.message}",
                    causeSummary = parsed.error::class.simpleName
                )
            )
            is EngineResult.Success -> decode(parsed.value)
        }
    }

    fun decode(json: JsonValue.ObjectValue): EngineResult<UserPreferences> {
        return try {
            val units = json.getObject("units")
            val alerts = json.getObject("alerts")
            val activity = json.getObject("activity")

            val updatedAt = json.getString("updated_at")?.let { Instant.parse(it) }
            val schemaVersion = json.getInt("schema_version") ?: UserPreferences.CURRENT_SCHEMA_VERSION
            if (schemaVersion > UserPreferences.CURRENT_SCHEMA_VERSION) {
                return EngineResult.Failure(
                    EngineError.PersistenceFailure(
                        operation = "decode_user_preferences",
                        message = "Preferences schema version $schemaVersion is newer than this build " +
                            "supports (${UserPreferences.CURRENT_SCHEMA_VERSION})."
                    )
                )
            }

            EngineResult.Success(
                UserPreferences(
                    temperatureUnit = units.enumOrDefault(
                        "temperature",
                        TemperatureUnitPreference.CELSIUS
                    ),
                    distanceUnit = units.enumOrDefault(
                        "distance",
                        DistanceUnitPreference.KILOMETERS
                    ),
                    volumeUnit = units.enumOrDefault(
                        "volume",
                        VolumeUnitPreference.MILLILITRES
                    ),
                    alerts = decodeAlerts(alerts),
                    activity = decodeActivity(activity),
                    consentedPersonalization = json.getBoolean("consented_personalization") ?: false,
                    updatedAt = updatedAt,
                    schemaVersion = schemaVersion
                )
            )
        } catch (ex: Exception) {
            EngineResult.Failure(
                EngineError.PersistenceFailure(
                    operation = "decode_user_preferences",
                    message = "Stored preferences could not be decoded: ${ex.message ?: ex::class.simpleName}.",
                    causeSummary = ex::class.simpleName
                )
            )
        }
    }

    /**
     * Model-facing projection. Personalisation fields are withheld unless the user explicitly
     * consented, and the projection says so rather than pretending the data does not exist.
     */
    fun encodeForTool(preferences: UserPreferences): JsonValue.ObjectValue {
        val consented = preferences.consentedPersonalization
        val activity = linkedMapOf<String, JsonValue>(
            "preferred_activities" to JsonValue.ArrayValue(
                preferences.activity.preferredActivities.sortedBy { it.name }
                    .map { JsonValue.StringValue(it.name) }
            ),
            "default_intensity" to JsonValue.StringValue(preferences.activity.defaultIntensity.name)
        )
        if (consented) {
            preferences.activity.bodyMassKg?.let {
                activity["body_mass_kg"] = SafeJsonCodec.number(it)
            }
            preferences.activity.homeLocation?.let {
                activity["home_location"] = encodeLocation(it)
            }
            if (preferences.activity.savedLocations.isNotEmpty()) {
                activity["saved_locations"] = JsonValue.ArrayValue(
                    preferences.activity.savedLocations.map { saved ->
                        JsonValue.ObjectValue(
                            linkedMapOf(
                                "label" to JsonValue.StringValue(saved.label),
                                "location" to encodeLocation(saved.location)
                            )
                        )
                    }
                )
            }
        }

        return JsonValue.ObjectValue(
            linkedMapOf(
                "units" to JsonValue.ObjectValue(
                    linkedMapOf(
                        "temperature" to JsonValue.StringValue(preferences.temperatureUnit.name),
                        "distance" to JsonValue.StringValue(preferences.distanceUnit.name),
                        "volume" to JsonValue.StringValue(preferences.volumeUnit.name)
                    )
                ),
                "alerts" to JsonValue.ObjectValue(
                    linkedMapOf(
                        "enabled" to JsonValue.BooleanValue(preferences.alerts.enabled),
                        "minimum_severity" to JsonValue.StringValue(
                            preferences.alerts.minimumSeverity.name
                        ),
                        "quiet_hours_start" to JsonValue.StringValue(
                            preferences.alerts.quietHoursStart.toString()
                        ),
                        "quiet_hours_end" to JsonValue.StringValue(
                            preferences.alerts.quietHoursEnd.toString()
                        ),
                        "allow_critical_during_quiet_hours" to JsonValue.BooleanValue(
                            preferences.alerts.allowCriticalDuringQuietHours
                        ),
                        "max_notifications_per_hour" to SafeJsonCodec.number(
                            preferences.alerts.maxNotificationsPerHour
                        ),
                        "dedup_window_hours" to SafeJsonCodec.number(
                            preferences.alerts.dedupWindowHours
                        )
                    )
                ),
                "activity" to JsonValue.ObjectValue(activity),
                "consented_personalization" to JsonValue.BooleanValue(consented),
                "personalization_withheld" to JsonValue.BooleanValue(
                    !consented && preferences.activity.hasPersonalisationData
                ),
                "updated_at" to (preferences.updatedAt?.let { JsonValue.StringValue(it.toString()) }
                    ?: JsonValue.NullValue),
                "schema_version" to SafeJsonCodec.number(preferences.schemaVersion)
            )
        )
    }

    private fun decodeAlerts(json: JsonValue.ObjectValue?): AlertPreferences {
        if (json == null) return AlertPreferences()
        return AlertPreferences(
            enabled = json.getBoolean("enabled") ?: true,
            minimumSeverity = json.enumOrDefault("minimum_severity", NotificationSeverity.WATCH),
            quietHoursStart = json.getString("quiet_hours_start")
                ?.let { LocalTime.parse(it) } ?: LocalTime.of(22, 0),
            quietHoursEnd = json.getString("quiet_hours_end")
                ?.let { LocalTime.parse(it) } ?: LocalTime.of(7, 0),
            allowCriticalDuringQuietHours = json.getBoolean("allow_critical_during_quiet_hours") ?: true,
            maxNotificationsPerHour = json.getInt("max_notifications_per_hour") ?: 3,
            dedupWindowHours = json.getInt("dedup_window_hours") ?: 6
        )
    }

    private fun decodeActivity(json: JsonValue.ObjectValue?): ActivityPreferences {
        if (json == null) return ActivityPreferences()
        val preferred = json.getArray("preferred_activities")?.items.orEmpty()
            .mapNotNull { item ->
                (item as? JsonValue.StringValue)?.value?.let { name ->
                    ActivityCategory.entries.firstOrNull { it.name == name }
                }
            }
            .toSet()
        val saved = json.getArray("saved_locations")?.items.orEmpty().mapNotNull { item ->
            val obj = item as? JsonValue.ObjectValue ?: return@mapNotNull null
            val label = obj.getString("label") ?: return@mapNotNull null
            val location = decodeLocation(obj.getObject("location")) ?: return@mapNotNull null
            SavedLocation(label, location)
        }
        return ActivityPreferences(
            preferredActivities = preferred,
            defaultIntensity = json.enumOrDefault("default_intensity", ActivityIntensity.MODERATE),
            bodyMassKg = json.getDouble("body_mass_kg"),
            homeLocation = decodeLocation(json.getObject("home_location")),
            savedLocations = saved
        )
    }

    private fun encodeLocation(location: GeoLocation): JsonValue.ObjectValue =
        JsonValue.ObjectValue(
            linkedMapOf<String, JsonValue>().apply {
                put("latitude", SafeJsonCodec.number(location.latitude))
                put("longitude", SafeJsonCodec.number(location.longitude))
                location.elevationMeters?.let { put("elevation_meters", SafeJsonCodec.number(it)) }
                location.timeZone?.let { put("timezone", JsonValue.StringValue(it.id)) }
                location.label?.let { put("label", JsonValue.StringValue(it)) }
            }
        )

    private fun decodeLocation(json: JsonValue.ObjectValue?): GeoLocation? {
        if (json == null) return null
        val latitude = json.getDouble("latitude") ?: return null
        val longitude = json.getDouble("longitude") ?: return null
        val zone = json.getString("timezone")?.let { ZoneId.of(it) }
        return GeoLocation.validated(
            latitude = latitude,
            longitude = longitude,
            elevationMeters = json.getDouble("elevation_meters"),
            timeZone = zone,
            label = json.getString("label")
        ).getOrNull()
    }

    private inline fun <reified E : Enum<E>> JsonValue.ObjectValue?.enumOrDefault(
        key: String,
        default: E
    ): E {
        val raw = this?.getString(key) ?: return default
        return enumValues<E>().firstOrNull { it.name == raw } ?: default
    }

    private const val MAX_PREFERENCES_JSON_CHARS = 64_000
    private const val MAX_PREFERENCES_JSON_DEPTH = 8
}
