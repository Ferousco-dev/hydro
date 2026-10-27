package com.ferousco.hydro.core.trip

import com.ferousco.hydro.core.integration.TravelMode
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.core.model.ActivityIntensity
import com.ferousco.hydro.core.recommendation.RecommendationUrgency
import kotlin.math.ceil

/** How the user will make the trip, and how that maps onto Hydro's routing and exertion models. */
enum class TripMode(
    val label: String,
    val travelMode: TravelMode,
    val activity: ActivityCategory,
    val intensity: ActivityIntensity
) {
    WALK("Walk", TravelMode.WALKING, ActivityCategory.WALKING, ActivityIntensity.MODERATE),
    BIKE("Bike", TravelMode.CYCLING, ActivityCategory.CYCLING, ActivityIntensity.VIGOROUS),
    DRIVE("Drive", TravelMode.DRIVING, ActivityCategory.COMMUTING, ActivityIntensity.LIGHT)
}

/** Everything the advisor needs. Missing readings are `null`; the advisor never guesses them. */
data class TripAdviceInput(
    val mode: TripMode,
    val distanceKm: Double,
    val durationMinutes: Int,
    /** NWS heat category rank (0 none, 1 caution ... 4 extreme danger) of the worst point on the route. */
    val worstHeatRank: Int?,
    val peakHeatIndexCelsius: Double?,
    val maxUvIndex: Double?,
    /** Fluid target from the hydration planner, when available. */
    val hydrationTargetMl: Int?,
    /** A cooler time to travel, already formatted for people (for example "07:00–09:00"). */
    val coolerWindow: String?
)

data class TripAdvice(
    val urgency: RecommendationUrgency,
    val headline: String,
    /** Whole bottles of [BOTTLE_ML] ml to carry, or `null` when no water is needed. */
    val waterBottles: Int?,
    val waterNote: String?,
    val clothing: List<String>,
    val tips: List<String>
) {
    companion object {
        const val BOTTLE_ML = 500
    }
}

/**
 * Turns a planned trip into plain-language advice: how much water, what to wear, and when to go.
 * It is deterministic (no language model), so the advice is instant, repeatable and works offline.
 */
object TripAdvisor {
    private const val MAX_BOTTLES = 8
    private const val LONG_ON_FOOT_MINUTES = 6 * 60

    fun advise(input: TripAdviceInput): TripAdvice {
        val rank = input.worstHeatRank
        val urgency = urgencyFor(input.mode, rank)
        return TripAdvice(
            urgency = urgency,
            headline = headlineFor(input.mode, urgency),
            waterBottles = bottlesFor(input),
            waterNote = waterNoteFor(input),
            clothing = clothingFor(input),
            tips = tipsFor(input, urgency)
        )
    }

    private fun urgencyFor(mode: TripMode, rank: Int?): RecommendationUrgency = when {
        rank == null -> RecommendationUrgency.UNKNOWN
        // Being in a car shelters you from most of the heat, so only the worst conditions matter.
        mode == TripMode.DRIVE -> if (rank >= 3) RecommendationUrgency.MODERATE else RecommendationUrgency.LOW
        rank <= 0 -> RecommendationUrgency.LOW
        rank == 1 -> RecommendationUrgency.MODERATE
        rank == 2 -> RecommendationUrgency.HIGH
        else -> RecommendationUrgency.CRITICAL
    }

    private fun headlineFor(mode: TripMode, urgency: RecommendationUrgency): String = when (mode) {
        TripMode.WALK -> when (urgency) {
            RecommendationUrgency.LOW -> "Good conditions for the walk"
            RecommendationUrgency.MODERATE -> "Take the walk steady"
            RecommendationUrgency.HIGH -> "The heat will be hard on foot"
            RecommendationUrgency.CRITICAL -> "Too hot to walk safely"
            RecommendationUrgency.UNKNOWN -> "Walking plan ready"
        }
        TripMode.BIKE -> when (urgency) {
            RecommendationUrgency.LOW -> "Good conditions for the ride"
            RecommendationUrgency.MODERATE -> "Ride at an easy pace"
            RecommendationUrgency.HIGH -> "The heat will be hard on a bike"
            RecommendationUrgency.CRITICAL -> "Too hot to ride safely"
            RecommendationUrgency.UNKNOWN -> "Riding plan ready"
        }
        TripMode.DRIVE -> when (urgency) {
            RecommendationUrgency.LOW -> "An easy drive"
            RecommendationUrgency.MODERATE -> "Hot out there, keep the car cool"
            else -> "Drive plan ready"
        }
    }

    private fun bottlesFor(input: TripAdviceInput): Int? {
        val ml = input.hydrationTargetMl ?: estimateMl(input)
        if (ml <= 0) return null
        return ceil(ml / TripAdvice.BOTTLE_ML.toDouble()).toInt().coerceIn(1, MAX_BOTTLES)
    }

    private fun needsRefill(input: TripAdviceInput): Boolean {
        val ml = input.hydrationTargetMl ?: estimateMl(input)
        return ceil(ml / TripAdvice.BOTTLE_ML.toDouble()).toInt() > MAX_BOTTLES
    }

    /** Fallback when the hydration planner has no answer: a modest amount scaled by heat and effort. */
    private fun estimateMl(input: TripAdviceInput): Int {
        if (input.mode == TripMode.DRIVE) return if (input.durationMinutes >= 45) TripAdvice.BOTTLE_ML else 0
        if (input.durationMinutes < 15) return 0
        val perHour = when (input.mode) {
            TripMode.BIKE -> 600
            else -> 450
        }
        val heatFactor = when (input.worstHeatRank ?: 0) {
            0 -> 1.0
            1 -> 1.25
            2 -> 1.5
            3 -> 2.0
            else -> 2.5
        }
        return (perHour * heatFactor * input.durationMinutes / 60.0).toInt()
    }

    private fun waterNoteFor(input: TripAdviceInput): String? {
        if (bottlesFor(input) == null) return null
        if (needsRefill(input)) return "That is more than you can comfortably carry. Plan places to refill along the way."
        return if (input.mode == TripMode.DRIVE) {
            "Keep a bottle within reach, especially if you get stuck in traffic."
        } else {
            "Sip often. Don't wait until you feel thirsty."
        }
    }

    private fun clothingFor(input: TripAdviceInput): List<String> = buildList {
        val rank = input.worstHeatRank ?: 0
        if (input.mode == TripMode.DRIVE) {
            add("Light, comfortable clothes")
            if (rank >= 2) add("A light layer in case the air conditioning is strong")
        } else {
            add("Light, loose clothes")
            if (rank >= 2) add("Light colours to reflect the sun")
        }
        if (input.mode != TripMode.DRIVE && (input.maxUvIndex ?: 0.0) >= 6.0) {
            add("A hat or cap, and sunscreen")
        }
        if (input.mode != TripMode.DRIVE && rank >= 2) add("Leave heavy bags and loads behind if you can")
    }

    private fun tipsFor(input: TripAdviceInput, urgency: RecommendationUrgency): List<String> = buildList {
        val rank = input.worstHeatRank ?: 0
        if (input.mode != TripMode.DRIVE && rank >= 1 && input.coolerWindow != null) {
            add("It is cooler between ${input.coolerWindow}. Going then will feel easier.")
        }
        if (input.mode != TripMode.DRIVE && input.durationMinutes >= 90) {
            add("Plan a rest in the shade about halfway.")
        }
        if (input.mode != TripMode.DRIVE && urgency == RecommendationUrgency.CRITICAL) {
            add("If you can, postpone the trip or go by car instead.")
        }
        if (input.mode == TripMode.DRIVE && input.durationMinutes >= 60) {
            add("Take a short break at least every two hours.")
        }
        if (input.mode != TripMode.DRIVE && input.durationMinutes > LONG_ON_FOOT_MINUTES) {
            add("This is a very long way to go this way. Consider driving instead.")
        }
        if (input.mode != TripMode.DRIVE) {
            add("If you feel dizzy, stop, find shade and drink water.")
        }
    }
}
