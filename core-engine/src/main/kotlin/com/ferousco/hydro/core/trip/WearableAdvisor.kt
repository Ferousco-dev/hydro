package com.ferousco.hydro.core.trip

/**
 * Turns one recent heart-rate reading plus the heat on a trip into a gentle suggestion.
 *
 * This is deliberately conservative and never a diagnosis. A raised heart rate has many causes
 * (effort, caffeine, stress, a loose watch strap), and Hydro does not know the person's age, fitness
 * or resting rate, so it only compares against broad thresholds and only suggests slowing down.
 * It stays silent when there is no fresh reading, so it never reports a number it does not have.
 */
object WearableAdvisor {
    /** A reading older than this is not about "now" any more. */
    const val MAX_SAMPLE_AGE_MINUTES = 30

    fun advise(
        mode: TripMode,
        heartRateBpm: Int?,
        sampleAgeMinutes: Int?,
        heatRank: Int?
    ): String? {
        if (heartRateBpm == null || sampleAgeMinutes == null) return null
        if (sampleAgeMinutes !in 0..MAX_SAMPLE_AGE_MINUTES) return null
        // Heart rate says little about a trip made sitting in a car.
        if (mode == TripMode.DRIVE) return null
        val rank = heatRank ?: 0
        return when {
            heartRateBpm >= VERY_HIGH ->
                "Your heart rate is very high. Stop and rest in the shade. If you feel unwell, get help."
            heartRateBpm >= HIGH && rank >= 1 ->
                "Your heart rate is high for this heat. Slow down and rest in the shade."
            heartRateBpm >= RAISED && rank >= 2 ->
                "Your heart rate is raised in this heat. Take it easier and drink some water."
            else -> null
        }
    }

    private const val VERY_HIGH = 170
    private const val HIGH = 150
    private const val RAISED = 130
}
