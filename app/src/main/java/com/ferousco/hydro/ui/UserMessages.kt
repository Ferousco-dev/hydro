package com.ferousco.hydro.ui

import com.ferousco.hydro.core.error.EngineError

/**
 * Plain-language text for people. Engine errors carry provider ids and codes meant for logs; those
 * never reach the screen.
 */
internal fun EngineError.userMessage(): String = when (this) {
    is EngineError.NetworkUnavailable ->
        "You seem to be offline. Check your connection and try again."
    is EngineError.ProviderError ->
        if (isTransient) "The service is busy right now. Try again in a moment."
        else "Couldn't get data for this place right now."
    is EngineError.InvalidInput ->
        if (field == "query") "Couldn't find that place. Try a nearby city or landmark."
        else "That didn't look right. Please try again."
    is EngineError.StaleData -> "The latest data is out of date. Try again when you're online."
    is EngineError.UnsupportedCalculation ->
        when (reason) {
            "no_route" -> "No road or path connects you to that place. Pick somewhere you can travel to."
            "outside_africa" -> "Hydro covers Africa for now, starting with Nigeria. Try a city in Africa."
            "origin_outside_africa" -> "You seem to be outside Africa. Hydro plans trips inside Africa for now."
            else -> "That isn't available right now."
        }
    is EngineError.MissingRequiredInputs -> "Some information is missing. Please try again."
    is EngineError.InvalidResponse -> "Got an unexpected answer. Try again in a moment."
    is EngineError.PolicyDenied -> "Hydro doesn't have permission for that."
    is EngineError.PersistenceFailure -> "Couldn't save that change."
    is EngineError.Cancelled -> "Cancelled."
}
