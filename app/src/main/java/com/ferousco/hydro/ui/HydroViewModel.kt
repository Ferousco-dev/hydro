package com.ferousco.hydro.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ferousco.hydro.HydroApplication
import com.ferousco.hydro.core.ai.HydroAssistantOutcome
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.tools.ToolCallOutcome
import com.ferousco.hydro.core.integration.HarmattanAdvisor
import com.ferousco.hydro.core.integration.NotificationSeverity
import com.ferousco.hydro.core.integration.WearableMetric
import com.ferousco.hydro.core.trip.TripMode
import com.ferousco.hydro.core.trip.WearableAdvisor
import com.ferousco.hydro.wearables.HealthConnectWearableDataSource
import com.ferousco.hydro.ui.trip.ForecastBars
import com.ferousco.hydro.ui.trip.TripController
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
import kotlinx.coroutines.delay
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
    /** What the assistant is doing right now, in plain words; null when idle. */
    val askStatus: String? = null,
    val mapLatitude: Double? = null,
    val mapLongitude: Double? = null,
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val suggestions: List<PlaceSuggestionUi> = emptyList(),
    val pins: List<PinnedPlaceUi> = emptyList(),
    val wearables: WearablesUi = WearablesUi(),
    val trend: TrendUi? = null,
    val sky: SkyUi? = null,
    val animationsOn: Boolean = true,
    val language: com.ferousco.hydro.ui.i18n.AppLanguage = com.ferousco.hydro.ui.i18n.AppLanguage.English,
    val air: AirUi? = null,
    /** True when the numbers on screen come from the saved copy, not a fresh reading. */
    val showingSaved: Boolean = false,
    val savedAt: Instant? = null,
    /** True while the map is following the phone's live position. */
    val following: Boolean = false,
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
    val isNow: Boolean,
    val tempC: Double? = null,
    val weatherCode: Int? = null,
    val isDay: Boolean? = null
)

data class TrendUi(
    val hours: List<TrendHour>,
    val hottest: TrendHour?,
    val coolest: TrendHour?
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

/**
 * One chat message. [sources] are plain-language names of what an answer used ("weather", "air
 * quality"); [animate] is true only for a reply that should be typed out once when it arrives.
 */
data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val sources: List<String> = emptyList(),
    val isError: Boolean = false,
    val animate: Boolean = false
)

/**
 * Loads one live environmental observation, runs the deterministic heat/UV assessment,
 * persists both to local history, and exposes the result as [HydroUiState].
 */
class HydroViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HydroApplication
    private val engine = app.engine
    private val locationProvider = DeviceLocationProvider(application)

    private val cache = SnapshotCache(application)

    // Start from the last saved answer, if any, so the app is useful before (or without) a signal.
    private val _state = MutableStateFlow(
        (cache.load() ?: HydroUiState()).copy(
            assistantEnabled = com.ferousco.hydro.BuildConfig.GEMINI_API_KEY.isNotBlank(),
            animationsOn = application.getSharedPreferences("hydro_display", android.content.Context.MODE_PRIVATE)
                .getBoolean("animations_on", true),
            language = com.ferousco.hydro.ui.i18n.AppLanguage.fromCode(
                application.getSharedPreferences("hydro_display", android.content.Context.MODE_PRIVATE)
                    .getString("language", null)
            )
        )
    )
    val state: StateFlow<HydroUiState> = _state.asStateFlow()

    private val privacy = HealthPrivacy(application)
    private val displayPrefs = application.getSharedPreferences("hydro_display", android.content.Context.MODE_PRIVATE)
    private var lastLocation: GeoLocation = DEFAULT_LOCATION
    private var deviceLocationNow: GeoLocation? = null
    private var refreshQueued = false

    private val trips = TripController(
        engine = engine,
        scope = viewModelScope,
        state = _state,
        originProvider = { deviceLocationNow ?: locationProvider.lastKnownLocation() ?: DEFAULT_LOCATION },
        writeBrief = { prompt, destination ->
            (app.orchestrator.respond(userPrompt = prompt, userLocationContext = destination, permissionGate = privacy.assistantGate) as? HydroAssistantOutcome.Responded)?.responseText
        }
    )

    init {
        refresh()
        loadAlertPreferences()
        trips.loadPins()
        _state.update { it.copy(wearables = it.wearables.copy(assistantMayUse = privacy.assistantMayUseHealthData)) }
        refreshWearables()
    }

    fun refresh() {
        if (_state.value.isLoading) {
            // e.g. the location-permission result arriving mid-load: rerun so the grant is used.
            refreshQueued = true
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }

            val deviceLocation = locationProvider.freshFix()?.location
            deviceLocationNow = deviceLocation
            val location = deviceLocation ?: DEFAULT_LOCATION

            when (val result = engine.weatherRepository.getCurrentObservation(location)) {
                is EngineResult.Failure -> {
                    _state.update {
                        val hasData = it.airTemperatureCelsius != null || it.heatIndexCelsius != null
                        if (hasData) {
                            // No signal, but there is something to show: keep it and say so, not an error.
                            it.copy(
                                isLoading = false,
                                usingDeviceLocation = deviceLocation != null,
                                errorMessage = null,
                                showingSaved = true,
                                savedAt = it.savedAt ?: it.observationTime
                            )
                        } else {
                            it.copy(
                                isLoading = false,
                                locationLabel = location.label ?: "Selected location",
                                usingDeviceLocation = deviceLocation != null,
                                mapLatitude = location.latitude,
                                mapLongitude = location.longitude,
                                errorMessage = result.error.userMessage()
                            )
                        }
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
                    loadAir(location)

                    _state.update { previous ->
                        previous.copy(
                            isLoading = false,
                            locationLabel = observation.effectiveLocation.label
                                ?: location.label
                                ?: "Selected location",
                            usingDeviceLocation = deviceLocation != null,
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
                            errorMessage = null,
                            showingSaved = false,
                            savedAt = null
                        )
                    }
                    cache.save(_state.value)
                }
            }
            if (refreshQueued) {
                refreshQueued = false
                refresh()
            }
        }
    }


    // ----- Destination flow (search, trip, pins) lives in TripController -----

    fun searchPlaces(query: String) = trips.searchPlaces(query)
    fun clearSuggestions() = trips.clearSuggestions()
    fun submitSearch(query: String) = trips.submitSearch(query)
    fun clearSearchError() = trips.clearSearchError()
    fun selectPlace(place: PlaceSuggestionUi) = trips.selectPlace(place)
    fun setDestination(latitude: Double, longitude: Double) = trips.setDestination(latitude, longitude)
    fun openPin(pin: PinnedPlaceUi) = trips.openPin(pin)
    fun setTripMode(mode: TripMode) = trips.setMode(mode)
    fun clearTrip() = trips.clearTrip()
    fun retryTrip() = trips.retry()
    fun pinCurrent(label: String) = trips.pinCurrent(label)
    fun unpinCurrent() = trips.unpinCurrent()

    // ----- Wearables (Android Health Connect) -----

    /** Checks the connection and reads the latest heart rate and steps, if the user allowed them. */
    fun refreshWearables() {
        viewModelScope.launch {
            val source = app.wearables
            val link = source.connection()
            val wearables = when (link) {
                HealthConnectWearableDataSource.Connection.NotSupported -> WearablesUi(WearableLinkUi.NotSupported)
                HealthConnectWearableDataSource.Connection.NeedsInstall -> WearablesUi(WearableLinkUi.NeedsInstall)
                HealthConnectWearableDataSource.Connection.NeedsPermission -> WearablesUi(WearableLinkUi.NeedsPermission)
                is HealthConnectWearableDataSource.Connection.Connected -> {
                    val now = Instant.now()
                    val heartRate = if (WearableMetric.HEART_RATE_BPM in link.metrics) {
                        source.latest(WearableMetric.HEART_RATE_BPM, Duration.ofMinutes(WearableAdvisor.MAX_SAMPLE_AGE_MINUTES.toLong()), now)
                    } else {
                        null
                    }
                    val steps = if (WearableMetric.STEPS in link.metrics) {
                        source.stepsSince(java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant(), now)
                    } else {
                        null
                    }
                    WearablesUi(
                        link = WearableLinkUi.Connected,
                        heartRateBpm = heartRate?.value?.toInt(),
                        heartRateAgeMinutes = heartRate?.let { Duration.between(it.recordedAt, now).toMinutes().toInt() },
                        stepsToday = steps
                    )
                }
            }
            _state.update { it.copy(wearables = wearables.copy(assistantMayUse = privacy.assistantMayUseHealthData)) }
            trips.refreshWearableNote()
        }
    }

    fun disconnectWearables() {
        viewModelScope.launch {
            app.wearables.disconnect()
            privacy.assistantMayUseHealthData = false
            refreshWearables()
        }
    }

    private var followJob: kotlinx.coroutines.Job? = null

    /** Follow the phone's live position: the dot moves, and the weather refreshes after a real move. */
    fun setFollowing(on: Boolean) {
        followJob?.cancel()
        followJob = null
        _state.update { it.copy(following = on) }
        if (!on) return
        followJob = viewModelScope.launch {
            locationProvider.updates().collect { position ->
                deviceLocationNow = position
                _state.update {
                    it.copy(mapLatitude = position.latitude, mapLongitude = position.longitude, usingDeviceLocation = true)
                }
                if (lastLocation.distanceKilometersTo(position) > FOLLOW_REFRESH_KM) refresh()
            }
            // The stream ended by itself (location off or not allowed), so stop claiming to follow.
            _state.update { it.copy(following = false) }
        }
    }

    fun setAnimationsOn(on: Boolean) {
        displayPrefs.edit().putBoolean("animations_on", on).apply()
        _state.update { it.copy(animationsOn = on) }
    }

    fun setAssistantMayUseHealthData(allowed: Boolean) {
        privacy.assistantMayUseHealthData = allowed
        _state.update { it.copy(wearables = it.wearables.copy(assistantMayUse = allowed)) }
    }

    /** Re-reads conditions for where the user is now. */
    fun useMyLocation() = refresh()

    fun setActivity(activity: ActivityCategory) {
        if (activity == _state.value.activity) return
        _state.update { it.copy(activity = activity) }
        viewModelScope.launch { loadRecommendation(lastLocation) }
    }

    /** Next-24-hours heat bars, from deterministic engine calls. */
    /** Air quality and dust for the Now page. A failure leaves whatever was there (even the saved copy). */
    private suspend fun loadAir(location: GeoLocation) {
        val reading = (app.airQualityProvider.current(location) as? EngineResult.Success)?.value ?: return
        val advice = HarmattanAdvisor.assess(reading, location, java.time.LocalDate.now().monthValue)
        val air = AirUi(
            usAqi = reading.usAqi?.toInt(),
            categoryName = reading.category?.displayName,
            pm25 = reading.pollutantsMicrogramsPerM3["pm2_5"],
            pm10 = reading.pollutantsMicrogramsPerM3["pm10"],
            dust = reading.pollutantsMicrogramsPerM3["dust"],
            dustLevel = advice.level.name,
            inHarmattanSeason = advice.inHarmattanSeason,
            source = if (reading.providerId.contains("google", ignoreCase = true)) "Google" else "Open-Meteo"
        )
        _state.update { it.copy(air = air) }
    }

    fun setLanguage(language: com.ferousco.hydro.ui.i18n.AppLanguage) {
        displayPrefs.edit().putString("language", language.code).apply()
        _state.update { it.copy(language = language) }
    }

    private suspend fun loadTrend(location: GeoLocation) {
        val hours = ForecastBars.load(engine, location, count = 24, emphasizeFirst = 1)
        _state.update {
            it.copy(
                sky = hours.firstOrNull()?.let { h -> SkyUi(skyKindOf(h.weatherCode), h.isDay ?: true, h.severity, moonPhaseAt(System.currentTimeMillis())) },
                trend = if (hours.isEmpty()) null else TrendUi(
                    hours = hours,
                    hottest = hours.maxByOrNull { h -> h.heatIndexC },
                    coolest = hours.minByOrNull { h -> h.heatIndexC }
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
        val args = jsonArgs(
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

    private var askJob: kotlinx.coroutines.Job? = null
    private var nextMessageId = 1L

    private fun newMessage(
        fromUser: Boolean,
        text: String,
        sources: List<String> = emptyList(),
        isError: Boolean = false,
        animate: Boolean = false
    ) = ChatMessage(nextMessageId++, fromUser, text, sources, isError, animate)

    /** Sends a question to the Gemini-backed orchestrator; every number comes from a tool. */
    fun ask(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || _state.value.isAsking) return
        send(text, addUserMessage = true)
    }

    /** Re-sends the last question after a failed answer. */
    fun retryAsk() {
        if (_state.value.isAsking) return
        val chat = _state.value.chat
        val lastQuestion = chat.lastOrNull { it.fromUser }?.text ?: return
        _state.update { it.copy(chat = it.chat.dropLastWhile { m -> m.isError }) }
        send(lastQuestion, addUserMessage = false)
    }

    /** Stops the answer that is being prepared. */
    fun stopAsking() {
        askJob?.cancel()
        _state.update { it.copy(isAsking = false, askStatus = null) }
    }

    /** Clears the conversation. */
    fun newChat() {
        askJob?.cancel()
        _state.update { it.copy(chat = emptyList(), isAsking = false, askStatus = null) }
    }

    /** Called once a reply has finished typing out, so it never types out again. */
    fun markRevealed(id: Long) {
        _state.update { st -> st.copy(chat = st.chat.map { if (it.id == id) it.copy(animate = false) else it }) }
    }

    private fun send(text: String, addUserMessage: Boolean) {
        val history = _state.value.chat
            .filter { !it.isError }
            .takeLast(MAX_HISTORY_MESSAGES)
            .joinToString("\n") { (if (it.fromUser) "User: " else "Hydro: ") + it.text.take(MAX_HISTORY_CHARS) }
        val context = trips.contextBlock()
        val prompt = buildString {
            if (context != null) append("What the user is looking at right now:\n$context\n\n")
            if (history.isNotBlank()) append("Earlier in this chat:\n$history\n\n")
            if (isNotEmpty()) append("New question: ")
            append(text)
        }

        _state.update {
            it.copy(
                isAsking = true,
                askStatus = "Thinking",
                chat = if (addUserMessage) it.chat + newMessage(fromUser = true, text = text) else it.chat
            )
        }
        val steps = LinkedHashSet<String>()
        askJob = viewModelScope.launch {
            // If nothing has happened for a while, say so instead of sitting on "Thinking".
            val slowHint = launch {
                delay(SLOW_ANSWER_HINT_MILLIS)
                _state.update { if (it.isAsking && it.askStatus == "Thinking") it.copy(askStatus = "Still working on it") else it }
            }
            try {
                val outcome = kotlinx.coroutines.withTimeout(ANSWER_TIMEOUT_MILLIS) { app.orchestrator.respond(
                    userPrompt = prompt,
                    userLocationContext = _state.value.trip?.let { GeoLocation(it.destinationLat, it.destinationLon) } ?: lastLocation,
                    permissionGate = privacy.assistantGate,
                    onToolStarted = { tool ->
                        AssistantLabels.source(tool)?.let(steps::add)
                        _state.update { it.copy(askStatus = AssistantLabels.activity(tool)) }
                    }
                ) }
                val reply = when (outcome) {
                    is HydroAssistantOutcome.Responded ->
                        newMessage(fromUser = false, text = outcome.responseText, sources = steps.toList(), animate = true)
                    is HydroAssistantOutcome.ModelUnavailable ->
                        newMessage(fromUser = false, text = "The assistant isn't available right now.", isError = true)
                    is HydroAssistantOutcome.Failed ->
                        newMessage(fromUser = false, text = "I couldn't finish that. ${outcome.error.userMessage()}", isError = true)
                }
                _state.update { it.copy(isAsking = false, askStatus = null, chat = it.chat + reply) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _state.update {
                    it.copy(
                        isAsking = false,
                        askStatus = null,
                        chat = it.chat + newMessage(
                            fromUser = false,
                            text = "That's taking too long. Check your connection and try again.",
                            isError = true
                        )
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isAsking = false,
                        askStatus = null,
                        chat = it.chat + newMessage(fromUser = false, text = "Something went wrong. Please try again.", isError = true)
                    )
                }
            } finally {
                slowHint.cancel()
            }
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
        const val MAX_HISTORY_MESSAGES = 6
        const val MAX_HISTORY_CHARS = 300
        const val SLOW_ANSWER_HINT_MILLIS = 15_000L
        const val ANSWER_TIMEOUT_MILLIS = 90_000L

        /** Explicit, disclosed fallback when the user has not granted location access. */
        /** How far the phone must move before the weather is fetched again. */
        const val FOLLOW_REFRESH_KM = 2.0

        val DEFAULT_LOCATION = GeoLocation(
            latitude = 7.3775,
            longitude = 3.9470,
            timeZone = ZoneId.of("Africa/Lagos"),
            label = "Ibadan, Nigeria (default)"
        )
    }
}
