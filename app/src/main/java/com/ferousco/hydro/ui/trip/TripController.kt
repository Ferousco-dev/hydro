package com.ferousco.hydro.ui.trip

import com.ferousco.hydro.core.HydroEngine
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.preferences.SavedLocation
import com.ferousco.hydro.core.tools.ToolCallOutcome
import com.ferousco.hydro.core.trip.TripMode
import com.ferousco.hydro.core.trip.WearableAdvisor
import com.ferousco.hydro.ui.HydroUiState
import com.ferousco.hydro.ui.PinnedPlaceUi
import com.ferousco.hydro.ui.PlaceSuggestionUi
import com.ferousco.hydro.ui.TripAdviceUi
import com.ferousco.hydro.ui.TripUi
import com.ferousco.hydro.ui.jsonArgs
import com.ferousco.hydro.ui.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Owns the destination flow: search suggestions, the current trip, pinned places, and the assistant's
 * written briefing. It edits the shared screen state, so the map, the panel and the chat all see the
 * same trip at the same time.
 *
 * @param originProvider where the user is now (their device location, or the disclosed default)
 * @param writeBrief asks the assistant for a short briefing; returns null if it cannot answer
 */
internal class TripController(
    private val engine: HydroEngine,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<HydroUiState>,
    private val originProvider: () -> GeoLocation,
    private val writeBrief: suspend (prompt: String, destination: GeoLocation) -> String?
) {
    private val planner = TripPlanner(engine)
    private var suggestJob: Job? = null
    private var planJob: Job? = null
    private var briefJob: Job? = null

    // ----- Search -----

    /** Called as the user types; fetches suggestions after a short pause. */
    fun searchPlaces(query: String) {
        suggestJob?.cancel()
        val text = query.trim()
        if (text.length < MIN_QUERY_CHARS) {
            state.update { it.copy(suggestions = emptyList(), isSearching = false) }
            return
        }
        suggestJob = scope.launch {
            delay(SUGGEST_DEBOUNCE_MILLIS)
            state.update { it.copy(isSearching = true, searchError = null) }
            val places = lookup(text, MAX_SUGGESTIONS)
            state.update { it.copy(isSearching = false, suggestions = places ?: emptyList()) }
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        state.update { it.copy(suggestions = emptyList(), isSearching = false) }
    }

    fun clearSearchError() = state.update { it.copy(searchError = null) }

    /** Enter on the keyboard: go to the best match straight away. */
    fun submitSearch(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        suggestJob?.cancel()
        suggestJob = scope.launch {
            state.update { it.copy(isSearching = true, searchError = null) }
            val first = state.value.suggestions.firstOrNull() ?: lookup(text, 1)?.firstOrNull()
            state.update { it.copy(isSearching = false) }
            if (first == null) {
                state.update { it.copy(searchError = "Couldn't find that place. Try a nearby city or landmark.") }
            } else {
                selectPlace(first)
            }
        }
    }

    private suspend fun lookup(text: String, max: Int): List<PlaceSuggestionUi>? {
        return when (val outcome = engine.toolRegistry.executeJson("geocode_place", jsonArgs("query" to text, "max_results" to max))) {
            is ToolCallOutcome.Failure -> {
                state.update { it.copy(searchError = outcome.error.userMessage()) }
                null
            }
            is ToolCallOutcome.Success -> outcome.jsonResult.getArray("matches")?.items.orEmpty().mapNotNull { item ->
                val o = item as? JsonValue.ObjectValue ?: return@mapNotNull null
                val name = o.getString("name") ?: return@mapNotNull null
                PlaceSuggestionUi(
                    name = name.substringBefore(",").ifBlank { name },
                    detail = name.substringAfter(",", "").trim().ifBlank { o.getString("country") },
                    latitude = o.getDouble("latitude") ?: return@mapNotNull null,
                    longitude = o.getDouble("longitude") ?: return@mapNotNull null
                )
            }.distinctBy { it.name to it.detail }
        }
    }

    // ----- Trip -----

    fun selectPlace(place: PlaceSuggestionUi) {
        clearSuggestions()
        startTrip(place.latitude, place.longitude, place.name)
    }

    /** A long press on the map. */
    fun setDestination(latitude: Double, longitude: Double) = startTrip(latitude, longitude, "Dropped pin")

    fun openPin(pin: PinnedPlaceUi) = startTrip(pin.latitude, pin.longitude, pin.label)

    private fun startTrip(latitude: Double, longitude: Double, name: String) {
        val mode = state.value.trip?.mode ?: TripMode.WALK
        state.update {
            it.copy(trip = TripUi(name, latitude, longitude, mode, pinLabel = pinAt(latitude, longitude)?.label), searchError = null)
        }
        plan()
    }

    fun setMode(mode: TripMode) {
        val trip = state.value.trip ?: return
        if (trip.mode == mode) return
        state.update { it.copy(trip = trip.copy(mode = mode)) }
        plan()
    }

    /** The back button: leaves the trip and returns to the normal panel. */
    fun clearTrip() {
        planJob?.cancel()
        briefJob?.cancel()
        state.update { it.copy(trip = null) }
    }

    fun retry() = plan()

    private fun plan() {
        planJob?.cancel()
        briefJob?.cancel()
        val trip = state.value.trip ?: return
        val destination = GeoLocation(trip.destinationLat, trip.destinationLon, label = trip.destinationName)
        state.update { it.copy(trip = trip.copy(isLoading = true, error = null, aiBrief = null, aiWorking = false)) }

        planJob = scope.launch {
            when (val result = planner.plan(originProvider(), destination, trip.mode)) {
                is EngineResult.Failure -> state.update { st ->
                    st.copy(trip = st.trip?.takeIf { it.sameTrip(trip) }?.copy(isLoading = false, error = result.error.userMessage()) ?: st.trip)
                }
                is EngineResult.Success -> {
                    val plan = result.value
                    state.update { st ->
                        val current = st.trip?.takeIf { it.sameTrip(trip) } ?: return@update st
                        st.copy(
                            trip = current.copy(
                                isLoading = false,
                                error = null,
                                route = plan.route,
                                distanceKm = plan.distanceKm,
                                durationMinutes = plan.durationMinutes,
                                worstCategory = plan.worstCategory,
                                worstRank = plan.worstRank,
                                straightLine = plan.straightLine,
                                hours = plan.hours,
                                advice = plan.advice.let { a ->
                                    TripAdviceUi(a.urgency.name, a.headline, a.waterBottles, a.waterNote, a.clothing, a.tips)
                                }
                            )
                        )
                    }
                    refreshWearableNote()
                    startBrief(trip, destination)
                }
            }
        }
    }

    /** Asks the assistant for a short written briefing. If it cannot, the panel simply has none. */
    private fun startBrief(planned: TripUi, destination: GeoLocation) {
        briefJob?.cancel()
        val current = state.value.trip?.takeIf { it.sameTrip(planned) } ?: return
        state.update { it.copy(trip = current.copy(aiWorking = true)) }
        briefJob = scope.launch {
            val text = withTimeoutOrNull(BRIEF_TIMEOUT_MILLIS) {
                writeBrief(TripContext.briefPrompt(current, state.value.pins), destination)
            }
            state.update { st ->
                val now = st.trip?.takeIf { it.sameTrip(planned) } ?: return@update st
                st.copy(trip = now.copy(aiWorking = false, aiBrief = text?.trim()?.takeIf { it.isNotEmpty() }))
            }
        }
    }

    /** Re-evaluates the heart-rate suggestion, for example after a fresh reading arrives. */
    fun refreshWearableNote() {
        state.update { st ->
            val trip = st.trip ?: return@update st
            val w = st.wearables
            st.copy(trip = trip.copy(wearableNote = WearableAdvisor.advise(trip.mode, w.heartRateBpm, w.heartRateAgeMinutes, trip.worstRank)))
        }
    }

    private fun TripUi.sameTrip(other: TripUi) =
        destinationLat == other.destinationLat && destinationLon == other.destinationLon && mode == other.mode

    /** The text the assistant is given so its answers are about this trip. Null when there is none. */
    fun contextBlock(): String? = TripContext.describe(state.value.trip, state.value.pins)

    // ----- Pins -----

    fun loadPins() {
        scope.launch {
            val prefs = (engine.loadUserPreferences() as? EngineResult.Success)?.value ?: return@launch
            state.update { it.copy(pins = prefs.activity.savedLocations.map { s -> PinnedPlaceUi(s.label, s.location.latitude, s.location.longitude) }) }
        }
    }

    fun pinCurrent(label: String) {
        val trip = state.value.trip ?: return
        val name = label.trim().ifBlank { trip.destinationName }.take(MAX_LABEL_CHARS)
        if (state.value.pins.size >= MAX_PINS) return
        scope.launch {
            val place = (GeoLocation.validated(trip.destinationLat, trip.destinationLon, label = name) as? EngineResult.Success)?.value ?: return@launch
            val saved = engine.updateUserPreferences { prefs ->
                prefs.copy(activity = prefs.activity.copy(savedLocations = prefs.activity.savedLocations + SavedLocation(name, place)))
            }
            if (saved is EngineResult.Success) {
                loadPins()
                state.update { st -> st.copy(trip = st.trip?.copy(pinLabel = name)) }
            }
        }
    }

    fun unpinCurrent() {
        val trip = state.value.trip ?: return
        scope.launch {
            val saved = engine.updateUserPreferences { prefs ->
                prefs.copy(
                    activity = prefs.activity.copy(
                        savedLocations = prefs.activity.savedLocations.filterNot {
                            abs(it.location.latitude - trip.destinationLat) < PIN_MATCH_DEGREES &&
                                abs(it.location.longitude - trip.destinationLon) < PIN_MATCH_DEGREES
                        }
                    )
                )
            }
            if (saved is EngineResult.Success) {
                loadPins()
                state.update { st -> st.copy(trip = st.trip?.copy(pinLabel = null)) }
            }
        }
    }

    private fun pinAt(latitude: Double, longitude: Double): PinnedPlaceUi? =
        state.value.pins.firstOrNull { abs(it.latitude - latitude) < PIN_MATCH_DEGREES && abs(it.longitude - longitude) < PIN_MATCH_DEGREES }

    private companion object {
        const val MIN_QUERY_CHARS = 3
        const val SUGGEST_DEBOUNCE_MILLIS = 450L
        const val MAX_SUGGESTIONS = 5
        const val BRIEF_TIMEOUT_MILLIS = 45_000L
        const val MAX_PINS = 8
        const val MAX_LABEL_CHARS = 24
        /** About 55 m: close enough to count as the same place. */
        const val PIN_MATCH_DEGREES = 0.0005
    }
}
