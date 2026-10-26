package com.ferousco.hydro.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ferousco.hydro.HydroApplication
import com.ferousco.hydro.core.error.EngineResult
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
    val errorMessage: String? = null
)

/**
 * Loads one live environmental observation, runs the deterministic heat/UV assessment,
 * persists both to local history, and exposes the result as [HydroUiState].
 */
class HydroViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HydroApplication
    private val engine = app.engine
    private val locationProvider = DeviceLocationProvider(application)

    private val _state = MutableStateFlow(HydroUiState())
    val state: StateFlow<HydroUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.isLoading) return
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
