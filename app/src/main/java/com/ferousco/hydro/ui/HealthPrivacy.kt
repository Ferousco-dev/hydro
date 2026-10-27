package com.ferousco.hydro.ui

import android.content.Context
import com.ferousco.hydro.core.tools.ToolCapability
import com.ferousco.hydro.core.tools.ToolPermissionGate

/**
 * Health numbers stay on the phone unless the user says otherwise.
 *
 * Anything the assistant reads is sent to Google's Gemini to write the answer, so the assistant is
 * blocked from the wearable tool until the user turns the switch on. The on-device trip suggestion
 * does not use the assistant, so it works either way.
 */
internal class HealthPrivacy(context: Context) {
    private val prefs = context.getSharedPreferences("hydro_privacy", Context.MODE_PRIVATE)

    var assistantMayUseHealthData: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) = prefs.edit().putBoolean(KEY, value).apply()

    val assistantGate = ToolPermissionGate { capability ->
        capability != ToolCapability.WEARABLE_READ || assistantMayUseHealthData
    }

    private companion object {
        const val KEY = "assistant_may_use_health_data"
    }
}
