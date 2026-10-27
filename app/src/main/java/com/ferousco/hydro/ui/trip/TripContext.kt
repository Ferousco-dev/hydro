package com.ferousco.hydro.ui.trip

import com.ferousco.hydro.ui.PinnedPlaceUi
import com.ferousco.hydro.ui.TripUi
import com.ferousco.hydro.ui.pages.shortCategory
import java.util.Locale

/**
 * The "context manager": turns what the user is currently looking at into a short briefing for the
 * assistant, so a question like "how much water?" is answered about *this* trip without the user
 * having to explain it. The wording is plain on purpose; it is also what the model reads.
 */
internal object TripContext {

    /** A few factual lines about the trip, or null while there is nothing reliable to say. */
    fun describe(trip: TripUi?, pins: List<PinnedPlaceUi>): String? {
        if (trip == null || trip.isLoading || trip.error != null) {
            return if (pins.isEmpty()) null else "Pinned places: ${pins.joinToString { it.label }}."
        }
        return buildList {
            add("The user is looking at a trip from where they are now to ${trip.destinationName}, by ${trip.mode.label.lowercase(Locale.US)}.")
            val facts = listOfNotNull(
                trip.distanceKm?.let { String.format(Locale.US, "%.1f km", it) },
                trip.durationMinutes?.let { "about $it minutes" }
            )
            if (facts.isNotEmpty()) add("The route is ${facts.joinToString(", ")}." + if (trip.straightLine) " (This is a straight-line estimate, not a road route.)" else "")
            trip.worstCategory?.let { add("The worst heat expected along the way is: ${shortCategory(it).lowercase(Locale.US)}.") }
            trip.advice?.let { a ->
                a.waterBottles?.let { add("Advice already shown: carry about $it bottles of water (500 ml each).") }
                if (a.clothing.isNotEmpty()) add("Clothing advice already shown: ${a.clothing.joinToString("; ")}.")
                if (a.tips.isNotEmpty()) add("Other advice already shown: ${a.tips.joinToString("; ")}.")
            }
            trip.pinLabel?.let { add("The user has pinned this place as \"$it\".") }
            if (pins.isNotEmpty()) add("Pinned places: ${pins.joinToString { it.label }}.")
        }.joinToString("\n")
    }

    /** The request used to write the short briefing shown in the trip panel. */
    fun briefPrompt(trip: TripUi, pins: List<PinnedPlaceUi>): String =
        "Write a friendly briefing for this trip in at most three short sentences. " +
            "Mention the most useful thing to know about the heat or sun, and one practical tip. " +
            "Use plain words, no lists and no headings. Use the facts below first and only look up " +
            "more information if you really need it.\n\n" + describe(trip, pins).orEmpty()
}
