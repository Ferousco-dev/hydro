package com.ferousco.hydro.ui

import com.ferousco.hydro.core.trip.TripMode

/** A place the user can pick from the search results. */
data class PlaceSuggestionUi(
    val name: String,
    val detail: String?,
    val latitude: Double,
    val longitude: Double
)

/** A place the user pinned, such as "Work". Stored in the saved-locations preference. */
data class PinnedPlaceUi(val label: String, val latitude: Double, val longitude: Double)

/** A point on the route with the heat risk (0 none to 4 extreme) expected there. */
data class TripPoint(val latitude: Double, val longitude: Double, val severity: Int)

data class TripAdviceUi(
    val urgency: String,
    val headline: String,
    val waterBottles: Int?,
    val waterNote: String?,
    val clothing: List<String>,
    val tips: List<String>
)

/**
 * Everything about the trip the user is looking at. The panel, the map, the advice and the chat all
 * read this one record, which is what keeps them in step with each other.
 */
data class TripUi(
    val destinationName: String,
    val destinationLat: Double,
    val destinationLon: Double,
    val mode: TripMode = TripMode.WALK,
    val isLoading: Boolean = true,
    val route: List<TripPoint> = emptyList(),
    val distanceKm: Double? = null,
    val durationMinutes: Int? = null,
    val worstCategory: String? = null,
    val straightLine: Boolean = false,
    val advice: TripAdviceUi? = null,
    val hours: List<TrendHour> = emptyList(),
    /** A short written briefing from the assistant; absent until it arrives or if it never does. */
    val aiBrief: String? = null,
    val aiWorking: Boolean = false,
    val error: String? = null,
    val pinLabel: String? = null,
    /** Worst heat risk along the route, 0 (none) to 4. */
    val worstRank: Int? = null,
    /** A gentle suggestion from a fresh heart-rate reading, when the user connected their health data. */
    val wearableNote: String? = null
)

/** How far the user has got in connecting their watch data (via Android Health Connect). */
enum class WearableLinkUi { Checking, NotSupported, NeedsInstall, NeedsPermission, Connected }

data class WearablesUi(
    val link: WearableLinkUi = WearableLinkUi.Checking,
    val heartRateBpm: Int? = null,
    val heartRateAgeMinutes: Int? = null,
    val stepsToday: Int? = null,
    /** Off unless the user turns it on: whether the assistant may read these numbers. */
    val assistantMayUse: Boolean = false
)
