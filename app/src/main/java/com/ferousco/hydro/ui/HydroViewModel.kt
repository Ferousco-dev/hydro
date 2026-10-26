package com.ferousco.hydro.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ferousco.hydro.HydroApplication
import com.ferousco.hydro.core.ai.HydroAssistantOutcome
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.model.ActivityCategory
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
    val chat: List<ChatMessage> = emptyList()
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
            val location = deviceLocation ?: DEFAULT_LOCATION

            when (val result = engine.weatherRepository.getCurrentObservation(location)) {
                is EngineResult.Failure -> {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            locationLabel = location.label ?: "Selected location",
                            usingDeviceLocation = deviceLocation != null,
                            errorMessage = result.error.message
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

                    _state.update { previous ->
                        previous.copy(
                            isLoading = false,
                            locationLabel = observation.effectiveLocation.label
                                ?: location.label
                                ?: "Selected location",
                            usingDeviceLocation = deviceLocation != null,
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
    private var refreshQueued = false

    fun setActivity(activity: ActivityCategory) {
        if (activity == _state.value.activity) return
        _state.update { it.copy(activity = activity) }
        viewModelScope.launch { loadRecommendation(lastLocation) }
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
                    recommendationError = result.error.message
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
                                "${fmt.format(it.start)}–${fmt.format(it.end)} " +
                                    "(peak ${it.peakCategory.displayName})"
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
                    text = "Assistant is offline: ${outcome.message}"
                )
                is HydroAssistantOutcome.Failed -> ChatMessage(
                    fromUser = false,
                    text = "I couldn't finish that: ${outcome.error.message}"
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
