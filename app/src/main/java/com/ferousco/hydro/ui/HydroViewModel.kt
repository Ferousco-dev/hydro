package com.ferousco.hydro.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ferousco.hydro.HydroApplication
import com.ferousco.hydro.core.ai.HydroAssistantOutcome
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.tools.ToolCallOutcome
import com.ferousco.hydro.core.integration.NotificationSeverity
import com.ferousco.hydro.core.memory.HistoricalComparator
import com.ferousco.hydro.core.memory.YesterdayComparison
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import java.time.Duration
import java.time.format.DateTimeFormatter
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.PersistenceWriteResult
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.inUnitOrNull
import com.ferousco.hydro.core.model.rawValueOrNull
import com.ferousco.hydro.location.DeviceLocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Immutable screen state for the current-conditions screen.
 *
 * Every nullable field means "not available from the provider"; the UI must never substitute a
 * zero or guessed value for a missing measurement.
 */
data class HydroUiState(
    val isLoading: Boolean = false,
    val locationLabel: String = "Locating…",
    val usingDeviceLocation: Boolean = false,
    val airTemperatureCelsius: Double? = null,
    val feelsLikeCelsius: Double? = null,
    val relativeHumidityPercent: Double? = null,
    val windSpeedMetersPerSecond: Double? = null,
    val uvIndex: Double? = null,
    val heatIndexCelsius: Double? = null,
    val heatIndexFahrenheit: Double? = null,
    val heatCategory: String? = null,
    val heatCategorySummary: String? = null,
    val uvCategory: String? = null,
    val assessmentExplanation: String? = null,
    val limitations: List<String> = emptyList(),
    val missingVariables: List<String> = emptyList(),
    val deliveryOriginLabel: String? = null,
    val freshnessLabel: String? = null,
    val isLiveAndFresh: Boolean = false,
    val observationTime: Instant? = null,
    val savedHistoryCount: Int = 0,
    val errorMessage: String? = null,
    val activity: ActivityCategory = ActivityCategory.WALKING,
    val recommendation: RecommendationUi? = null,
    val recommendationError: String? = null,
    val assistantEnabled: Boolean = false,
    val isAsking: Boolean = false,
    val mapLatitude: Double? = null,
    val mapLongitude: Double? = null,
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val isCustomLocation: Boolean = false,
    val trend: TrendUi? = null,
    val trip: TripUi? = null,
    val heatLayerOn: Boolean = false,
    val heatLayerLoading: Boolean = false,
    val heatLayer: HeatLayerUi? = null,
    val heatLayerError: String? = null,
    val alertPrefs: AlertPrefsUi = AlertPrefsUi(),
    val chat: List<ChatMessage> = emptyList()
)

data class TrendHour(
    val label: String,
    val heatIndexC: Double,
    val severity: Int,
    val categoryName: String,
    val isNow: Boolean
)

data class TrendUi(
    val hours: List<TrendHour>,
    val hottest: TrendHour?,
    val coolest: TrendHour?,
    val yesterday: String
)

enum class TripMode(val label: String, val apiName: String) {
    Walk("Walk", "WALKING"),
    Bike("Bike", "CYCLING"),
    Drive("Drive", "DRIVING")
}

data class TripPoint(val latitude: Double, val longitude: Double, val severity: Int)

data class TripUi(
    val destinationLat: Double,
    val destinationLon: Double,
    val mode: TripMode = TripMode.Walk,
    val isLoading: Boolean = true,
    val distanceKm: Double? = null,
    val durationMinutes: Int? = null,
    val worstCategory: String? = null,
    val advisories: List<String> = emptyList(),
    val points: List<TripPoint> = emptyList(),
    val method: String? = null,
    val error: String? = null
)

data class HeatCellUi(val latitude: Double, val longitude: Double, val value: Double, val severity: Int)

data class HeatLayerUi(
    val cells: List<HeatCellUi>,
    val cellRadiusMeters: Double,
    val note: String?
)

data class AlertPrefsUi(
    val enabled: Boolean = true,
    val minimumSeverity: NotificationSeverity = NotificationSeverity.WATCH,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7,
    val error: String? = null
)

data class RecommendationUi(
    val urgency: String,
    val headline: String,
    val summary: String,
    val actions: List<Pair<String, String>>,
    val bestWindow: String?,
    val limitations: List<String>,
    val isCurrentData: Boolean
)

data class ChatMessage(val fromUser: Boolean, val text: String, val note: String? = null)

/**
 * Loads one live environmental observation, runs the deterministic heat/UV assessment,
 * persists both to local history, and exposes the result as [HydroUiState].
 */
class HydroViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HydroApplication
    private val engine = app.engine
    private val locationProvider = DeviceLocationProvider(application)

    private val _state = MutableStateFlow(
        HydroUiState(assistantEnabled = com.ferousco.hydro.BuildConfig.GEMINI_API_KEY.isNotBlank())
    )
    val state: StateFlow<HydroUiState> = _state.asStateFlow()

    init {
        refresh()
        loadAlertPreferences()
    }

    fun refresh() {
        if (_state.value.isLoading) {
            // e.g. the location-permission result arriving mid-load: rerun so the grant is used.
            refreshQueued = true
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }

            val deviceLocation = locationProvider.lastKnownLocation()
            val location = selectedLocation ?: deviceLocation ?: DEFAULT_LOCATION

            when (val result = engine.weatherRepository.getCurrentObservation(location)) {
                is EngineResult.Failure -> {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            locationLabel = location.label ?: "Selected location",
                            usingDeviceLocation = deviceLocation != null && selectedLocation == null,
                            isCustomLocation = selectedLocation != null,
                            mapLatitude = location.latitude,
                            mapLongitude = location.longitude,
                            errorMessage = result.error.userMessage()
                        )
                    }
                }

                is EngineResult.Success -> {
                    val envelope = result.value
                    val observation = envelope.data
                    val assessment = engine.assessmentEngine.assessObservation(observation)

                    val historyCount = persist(observation, assessment)
                    lastLocation = location
                    loadRecommendation(location)
                    loadTrend(location)

                    _state.update { previous ->
                        previous.copy(
                            isLoading = false,
                            locationLabel = observation.effectiveLocation.label
                                ?: location.label
                                ?: "Selected location",
                            usingDeviceLocation = deviceLocation != null && selectedLocation == null,
                            isCustomLocation = selectedLocation != null,
                            mapLatitude = location.latitude,
                            mapLongitude = location.longitude,
                            airTemperatureCelsius = observation.variables.airTemperature
                                .inUnitOrNull(TemperatureUnit.CELSIUS),
                            feelsLikeCelsius = observation.variables.feelsLikeTemperature
                                .inUnitOrNull(TemperatureUnit.CELSIUS),
                            relativeHumidityPercent = observation.variables.relativeHumidity
                                .rawValueOrNull(),
                            windSpeedMetersPerSecond = observation.variables.windSpeed
                                .inUnitOrNull(SpeedUnit.METERS_PER_SECOND),
                            uvIndex = observation.variables.uvIndex.rawValueOrNull(),
                            heatIndexCelsius = (assessment as? HeatAssessment.Supported)
                                ?.heatIndexCelsius?.value,
                            heatIndexFahrenheit = (assessment as? HeatAssessment.Supported)
                                ?.heatIndexFahrenheit,
                            heatCategory = (assessment as? HeatAssessment.Supported)
                                ?.category?.displayName,
                            heatCategorySummary = (assessment as? HeatAssessment.Supported)
                                ?.category?.nwsPublicHealthSummary,
                            uvCategory = (assessment as? HeatAssessment.Supported)
                                ?.uvCategory?.displayName,
                            assessmentExplanation = assessment.explanation,
                            limitations = assessment.limitations,
                            missingVariables = assessment.missingInformation
                                .map { it.variableName },
                            deliveryOriginLabel = envelope.deliveryOrigin.name
                                .replace('_', ' '),
                            freshnessLabel = envelope.freshness.freshnessState.name
                                .replace('_', ' '),
                            isLiveAndFresh = envelope.isLiveAndFresh,
                            observationTime = observation.observedAt,
                            savedHistoryCount = historyCount,
                            errorMessage = null
                        )
                    }
                }
            }
            if (refreshQueued) {
                refreshQueued = false
                refresh()
            }
        }
    }

    private var lastLocation: GeoLocation = DEFAULT_LOCATION

    /** A place the user searched for; overrides the device and default locations until cleared. */
    private var selectedLocation: GeoLocation? = null

    /** Resolves a place name with the `geocode_place` tool and loads conditions for the first match. */
    fun search(query: String) {
        val text = query.trim()
        if (text.isEmpty() || _state.value.isSearching) return
        _state.update { it.copy(isSearching = true, searchError = null) }
        viewModelScope.launch {
            val args = SafeJsonCodec.stringify(
                JsonValue.ObjectValue(
                    linkedMapOf(
                        "query" to JsonValue.StringValue(text),
                        "max_results" to JsonValue.NumberValue("1", 1.0)
                    )
                )
            )
            when (val outcome = engine.toolRegistry.executeJson("geocode_place", args)) {
                is ToolCallOutcome.Failure ->
                    _state.update { it.copy(isSearching = false, searchError = outcome.error.userMessage()) }
                is ToolCallOutcome.Success -> {
                    val match = outcome.jsonResult.getArray("matches")?.items?.firstOrNull() as? JsonValue.ObjectValue
                    val lat = match?.getDouble("latitude")
                    val lon = match?.getDouble("longitude")
                    val resolved = if (match != null && lat != null && lon != null) {
                        (GeoLocation.validated(lat, lon, label = match.getString("name")) as? EngineResult.Success)?.value
                    } else {
                        null
                    }
                    if (resolved == null) {
                        _state.update { it.copy(isSearching = false, searchError = "Couldn't find that place. Try a nearby city or landmark.") }
                    } else {
                        selectedLocation = resolved
                        _state.update { it.copy(isSearching = false, searchError = null) }
                        refresh()
                    }
                }
            }
        }
    }

    fun clearSearchError() = _state.update { it.copy(searchError = null) }

    /** Drops any searched place and goes back to the device location (or the disclosed default). */
    fun useMyLocation() {
        selectedLocation = null
        refresh()
    }
    private var refreshQueued = false

    fun setActivity(activity: ActivityCategory) {
        if (activity == _state.value.activity) return
        _state.update { it.copy(activity = activity) }
        viewModelScope.launch { loadRecommendation(lastLocation) }
    }

    /** Next-24-hours heat bars and the "versus yesterday" line, all from deterministic engine calls. */
    private suspend fun loadTrend(location: GeoLocation) {
        val forecastResult = engine.weatherRepository.getHourlyForecast(location, 30)
        val envelope = (forecastResult as? EngineResult.Success)?.value ?: run {
            _state.update { it.copy(trend = null) }
            return
        }
        val forecast = envelope.data
        val now = java.time.Instant.now()
        val zone = forecast.timeZone
        val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        val hours = forecast.hourly
            .filter { !it.validAt.isBefore(now.minus(Duration.ofHours(1))) }
            .take(24)
            .mapNotNull { point ->
                val supported = engine.assessmentEngine.assessForecastPoint(point, forecast.quality) as? HeatAssessment.Supported
                    ?: return@mapNotNull null
                TrendHour(
                    label = fmt.format(point.validAt),
                    heatIndexC = supported.heatIndexCelsius.value,
                    severity = supported.category.severityRank,
                    categoryName = supported.category.displayName,
                    isNow = false
                )
            }
        val withNow = hours.mapIndexed { i, h -> if (i == 0) h.copy(isNow = true) else h }

        val records = engine.historyRepository.queryRecentRecords(location = location, limit = 200).getOrNull().orEmpty()
        val yesterday = when (val c = engine.compareLatestToYesterday(records)) {
            is YesterdayComparison.Available -> c.summary.deltaFor(HistoricalComparator.AIR_TEMPERATURE)?.let {
                val d = it.delta
                when {
                    kotlin.math.abs(d) < 0.5 -> "About the same temperature as this time yesterday."
                    d > 0 -> "%.1f° warmer than this time yesterday.".format(java.util.Locale.getDefault(), d)
                    else -> "%.1f° cooler than this time yesterday.".format(java.util.Locale.getDefault(), -d)
                }
            } ?: "No comparable reading from yesterday yet."
            is YesterdayComparison.Unavailable -> "Hydro will compare with yesterday once it has a saved reading from this time."
        }
        _state.update {
            it.copy(
                trend = if (withNow.isEmpty()) null else TrendUi(
                    hours = withNow,
                    hottest = withNow.maxByOrNull { h -> h.heatIndexC },
                    coolest = withNow.minByOrNull { h -> h.heatIndexC },
                    yesterday = yesterday
                )
            )
        }
    }

    // ----- Alerts preferences -----

    private fun loadAlertPreferences() {
        viewModelScope.launch {
            val prefs = (engine.loadUserPreferences() as? EngineResult.Success)?.value ?: return@launch
            _state.update { it.copy(alertPrefs = prefs.alerts.toUi()) }
        }
    }

    private fun com.ferousco.hydro.core.preferences.AlertPreferences.toUi() = AlertPrefsUi(
        enabled = enabled,
        minimumSeverity = minimumSeverity,
        quietStartHour = quietHoursStart.hour,
        quietEndHour = quietHoursEnd.hour
    )

    private fun updateAlerts(
        transform: (com.ferousco.hydro.core.preferences.AlertPreferences) -> com.ferousco.hydro.core.preferences.AlertPreferences
    ) {
        viewModelScope.launch {
            when (val r = engine.updateUserPreferences { it.copy(alerts = transform(it.alerts)) }) {
                is EngineResult.Success -> _state.update { it.copy(alertPrefs = r.value.alerts.toUi()) }
                is EngineResult.Failure -> _state.update { it.copy(alertPrefs = it.alertPrefs.copy(error = r.error.userMessage())) }
            }
        }
    }

    fun setAlertsEnabled(enabled: Boolean) = updateAlerts { it.copy(enabled = enabled) }

    fun setMinimumSeverity(severity: NotificationSeverity) = updateAlerts { it.copy(minimumSeverity = severity) }

    fun shiftQuietHour(start: Boolean, delta: Int) = updateAlerts {
        fun shift(t: java.time.LocalTime) = t.plusHours(delta.toLong())
        if (start) it.copy(quietHoursStart = shift(it.quietHoursStart)) else it.copy(quietHoursEnd = shift(it.quietHoursEnd))
    }

    // ----- Heat layer -----

    fun toggleHeatLayer() {
        if (_state.value.heatLayerOn) {
            _state.update { it.copy(heatLayerOn = false) }
            return
        }
        _state.update { it.copy(heatLayerOn = true, heatLayerLoading = true, heatLayerError = null) }
        viewModelScope.launch { loadHeatLayer() }
    }

    private suspend fun loadHeatLayer() {
        val center = lastLocation
        val args = jsonObject(
            "latitude" to center.latitude, "longitude" to center.longitude,
            "radius_km" to 24.0, "resolution_km" to 6.0, "metric" to "HEAT_INDEX_CELSIUS"
        )
        when (val outcome = engine.toolRegistry.executeJson("get_heat_map", args)) {
            is ToolCallOutcome.Failure -> _state.update {
                it.copy(heatLayerLoading = false, heatLayerOn = false, heatLayerError = outcome.error.userMessage())
            }
            is ToolCallOutcome.Success -> {
                val json = outcome.jsonResult
                val cells = json.getArray("cells")?.items.orEmpty().mapNotNull { c ->
                    val o = c as? JsonValue.ObjectValue ?: return@mapNotNull null
                    val value = o.getDouble("value") ?: return@mapNotNull null
                    HeatCellUi(
                        latitude = o.getDouble("latitude") ?: return@mapNotNull null,
                        longitude = o.getDouble("longitude") ?: return@mapNotNull null,
                        value = value,
                        severity = NwsHeatIndexCategory.fromHeatIndexFahrenheit(value * 9.0 / 5.0 + 32.0).severityRank
                    )
                }
                val spacingKm = json.getDouble("resolution_km") ?: 6.0
                _state.update {
                    it.copy(
                        heatLayerLoading = false,
                        heatLayer = HeatLayerUi(
                            cells = cells,
                            cellRadiusMeters = spacingKm * 1000.0 / 2.0,
                            note = json.getArray("limitations")?.items?.firstOrNull()?.let { l -> (l as? JsonValue.StringValue)?.value }
                        )
                    )
                }
            }
        }
    }

    // ----- Trip planning -----

    fun setDestination(latitude: Double, longitude: Double) {
        val mode = _state.value.trip?.mode ?: TripMode.Walk
        _state.update { it.copy(trip = TripUi(latitude, longitude, mode)) }
        viewModelScope.launch { planTrip() }
    }

    /** Uses the place the user searched for as the destination. */
    fun routeToSearchedPlace() {
        val place = selectedLocation ?: return
        setDestination(place.latitude, place.longitude)
    }

    fun setTripMode(mode: TripMode) {
        val trip = _state.value.trip ?: return
        _state.update { it.copy(trip = trip.copy(mode = mode, isLoading = true, error = null)) }
        viewModelScope.launch { planTrip() }
    }

    fun clearTrip() = _state.update { it.copy(trip = null) }

    private suspend fun planTrip() {
        val trip = _state.value.trip ?: return
        val origin = locationProvider.lastKnownLocation() ?: DEFAULT_LOCATION
        val args = jsonObject(
            "origin_latitude" to origin.latitude, "origin_longitude" to origin.longitude,
            "destination_latitude" to trip.destinationLat, "destination_longitude" to trip.destinationLon,
            "travel_mode" to trip.mode.apiName
        )
        when (val outcome = engine.toolRegistry.executeJson("get_route_conditions", args)) {
            is ToolCallOutcome.Failure -> _state.update {
                it.copy(trip = it.trip?.copy(isLoading = false, error = outcome.error.userMessage()))
            }
            is ToolCallOutcome.Success -> {
                val json = outcome.jsonResult
                val points = json.getArray("samples")?.items.orEmpty().mapNotNull { s ->
                    val o = s as? JsonValue.ObjectValue ?: return@mapNotNull null
                    TripPoint(
                        latitude = o.getDouble("latitude") ?: return@mapNotNull null,
                        longitude = o.getDouble("longitude") ?: return@mapNotNull null,
                        severity = NwsHeatIndexCategory.entries.firstOrNull { c -> c.name == o.getString("category") }?.severityRank ?: 0
                    )
                }
                _state.update {
                    it.copy(
                        trip = it.trip?.copy(
                            isLoading = false,
                            error = null,
                            distanceKm = json.getDouble("distance_km"),
                            durationMinutes = json.getInt("duration_minutes"),
                            worstCategory = NwsHeatIndexCategory.entries
                                .firstOrNull { c -> c.name == json.getString("worst_category") }?.displayName,
                            advisories = json.getArray("advisories")?.items.orEmpty()
                                .mapNotNull { a -> (a as? JsonValue.StringValue)?.value },
                            points = points,
                            method = json.getString("route_method")
                        )
                    )
                }
            }
        }
    }

    private fun jsonObject(vararg pairs: Pair<String, Any>): String = SafeJsonCodec.stringify(
        JsonValue.ObjectValue(
            LinkedHashMap<String, JsonValue>().apply {
                pairs.forEach { (k, v) ->
                    put(
                        k,
                        when (v) {
                            is Double -> JsonValue.NumberValue(v.toString(), v)
                            else -> JsonValue.StringValue(v.toString())
                        }
                    )
                }
            }
        )
    )

    /** Deterministic advice: urgency, ordered actions and the coolest forecast window. */
    private suspend fun loadRecommendation(location: GeoLocation) {
        val result = engine.recommendForLocation(
            location = location,
            activityCategory = _state.value.activity,
            plannedDuration = Duration.ofHours(2)
        )
        val zone = location.timeZone ?: ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        _state.update { current ->
            when (result) {
                is EngineResult.Failure -> current.copy(
                    recommendation = null,
                    recommendationError = result.error.userMessage()
                )
                is EngineResult.Success -> {
                    val r = result.value
                    current.copy(
                        recommendationError = null,
                        recommendation = RecommendationUi(
                            urgency = r.urgency.name,
                            headline = r.headline,
                            summary = r.summary,
                            actions = r.actions.map { it.title to it.detail },
                            bestWindow = r.bestWindow?.let {
                                "${fmt.format(it.start)}–${fmt.format(it.end)}" +
                                    if (it.peakCategory == com.ferousco.hydro.core.model.NwsHeatIndexCategory.BELOW_CAUTION_THRESHOLD) {
                                        ""
                                    } else {
                                        " (peak ${it.peakCategory.displayName})"
                                    }
                            },
                            limitations = r.limitations,
                            isCurrentData = r.isCurrentData
                        )
                    )
                }
            }
        }
    }

    /** Sends a question to the Gemini-backed orchestrator; every number comes from a tool. */
    fun ask(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || _state.value.isAsking) return
        _state.update {
            it.copy(isAsking = true, chat = it.chat + ChatMessage(fromUser = true, text = text))
        }
        viewModelScope.launch {
            val outcome = app.orchestrator.respond(
                userPrompt = text,
                userLocationContext = lastLocation
            )
            val reply = when (outcome) {
                is HydroAssistantOutcome.Responded -> ChatMessage(
                    fromUser = false,
                    text = outcome.responseText,
                    note = outcome.grounding.executedTools
                        .filter { it.succeeded }
                        .joinToString(" · ") { it.toolName }
                        .ifBlank { null }
                )
                is HydroAssistantOutcome.ModelUnavailable -> ChatMessage(
                    fromUser = false,
                    text = "The assistant isn't available right now."
                )
                is HydroAssistantOutcome.Failed -> ChatMessage(
                    fromUser = false,
                    text = "I couldn't finish that. ${outcome.error.userMessage()}"
                )
            }
            _state.update { it.copy(isAsking = false, chat = it.chat + reply) }
        }
    }

    /**
     * Writes the observation and its assessment to local Room history.
     * Persistence failure must not hide the live reading from the user.
     */
    private suspend fun persist(
        observation: EnvironmentalObservation,
        assessment: HeatAssessment
    ): Int = withContext(Dispatchers.IO) {
        val observationWrite = engine.historyRepository.recordObservation(observation)
        val linkedId = (observationWrite.getOrNull() as? PersistenceWriteResult.Stored)
            ?.record?.id

        engine.historyRepository.recordHeatAssessment(
            assessment = assessment,
            linkedEnvironmentalRecordId = linkedId
        )

        engine.historyRepository.queryRecentRecords(limit = 100).getOrNull()?.size ?: 0
    }

    private companion object {
        /** Explicit, disclosed fallback when the user has not granted location access. */
        val DEFAULT_LOCATION = GeoLocation(
            latitude = 7.3775,
            longitude = 3.9470,
            timeZone = ZoneId.of("Africa/Lagos"),
            label = "Ibadan, Nigeria (default)"
        )
    }
}
