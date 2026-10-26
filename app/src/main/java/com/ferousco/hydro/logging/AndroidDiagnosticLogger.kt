package com.ferousco.hydro.logging

import android.util.Log
import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.LogLevel
import com.ferousco.hydro.core.logging.SanitizingDiagnosticLogger

/**
 * Android Logcat implementation of [DiagnosticLogger] wrapped with [SanitizingDiagnosticLogger]
 * so precise GPS coordinates and sensitive tokens are redacted before leaving the engine.
 */
class AndroidDiagnosticLogger(
    private val tagPrefix: String = "HydroEngine"
) : DiagnosticLogger {

    private val sanitized = SanitizingDiagnosticLogger(
        object : DiagnosticLogger {
            override fun log(
                level: LogLevel,
                component: String,
                event: String,
                attributes: Map<String, String>
            ) {
                val tag = "$tagPrefix:$component".take(23)
                val formatted = if (attributes.isEmpty()) {
                    event
                } else {
                    "$event | ${attributes.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
                }
                when (level) {
                    LogLevel.DEBUG -> Log.d(tag, formatted)
                    LogLevel.INFO -> Log.i(tag, formatted)
                    LogLevel.WARN -> Log.w(tag, formatted)
                    LogLevel.ERROR -> Log.e(tag, formatted)
                }
            }
        }
    )

    override fun log(
        level: LogLevel,
        component: String,
        event: String,
        attributes: Map<String, String>
    ) {
        sanitized.log(level, component, event, attributes)
    }
}
