package com.ferousco.hydro.core.logging

import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class DiagnosticEvent(
    val timestamp: Instant,
    val level: LogLevel,
    val component: String,
    val event: String,
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Diagnostic logging interface for engine observability.
 * Implementations must avoid exposing precise user coordinates or secret tokens.
 */
interface DiagnosticLogger {
    fun log(
        level: LogLevel,
        component: String,
        event: String,
        attributes: Map<String, String> = emptyMap()
    )

    fun debug(component: String, event: String, attributes: Map<String, String> = emptyMap()) =
        log(LogLevel.DEBUG, component, event, attributes)

    fun info(component: String, event: String, attributes: Map<String, String> = emptyMap()) =
        log(LogLevel.INFO, component, event, attributes)

    fun warn(component: String, event: String, attributes: Map<String, String> = emptyMap()) =
        log(LogLevel.WARN, component, event, attributes)

    fun error(component: String, event: String, attributes: Map<String, String> = emptyMap()) =
        log(LogLevel.ERROR, component, event, attributes)
}

object NoOpDiagnosticLogger : DiagnosticLogger {
    override fun log(
        level: LogLevel,
        component: String,
        event: String,
        attributes: Map<String, String>
    ) = Unit
}

/**
 * Sanitizing wrapper that strips precise GPS coordinates, query coordinate parameters,
 * and credential-like keys before forwarding diagnostic events.
 */
class SanitizingDiagnosticLogger(
    private val delegate: DiagnosticLogger
) : DiagnosticLogger {

    override fun log(
        level: LogLevel,
        component: String,
        event: String,
        attributes: Map<String, String>
    ) {
        val sanitizedEvent = sanitizeText(event)
        val sanitizedAttributes = attributes.entries.associate { (rawKey, rawValue) ->
            val keyLower = rawKey.lowercase()
            val cleanValue = when {
                SENSITIVE_SECRET_KEYS.any { keyLower.contains(it) } -> "[REDACTED_SECRET]"
                SENSITIVE_LOCATION_KEYS.contains(keyLower) -> "[REDACTED_LOCATION]"
                else -> sanitizeText(rawValue)
            }
            rawKey to cleanValue
        }
        delegate.log(level, component, sanitizedEvent, sanitizedAttributes)
    }

    companion object {
        private val SENSITIVE_SECRET_KEYS = setOf(
            "apikey",
            "api_key",
            "token",
            "secret",
            "password",
            "authorization",
            "bearer"
        )

        private val SENSITIVE_LOCATION_KEYS = setOf(
            "latitude",
            "longitude",
            "lat",
            "lon",
            "lng",
            "coordinates",
            "precise_location",
            "user_location"
        )

        private val QUERY_PARAM_PATTERN = Regex(
            "(?i)(latitude|longitude|lat|lon|lng|apikey|api_key|token|secret|authorization)=([^&\\s]+)"
        )

        private val COORDINATE_PAIR_PATTERN = Regex(
            "(?<![0-9A-Za-z_])[-+]?\\d{1,2}\\.\\d{2,}\\s*[,/\\s]\\s*[-+]?\\d{1,3}\\.\\d{2,}(?![0-9A-Za-z_])"
        )

        private val LABELED_COORDINATE_PATTERN = Regex(
            "(?i)(lat(?:itude)?|lon(?:gitude)?|lng)\\s*[:=]\\s*[-+]?\\d{1,3}\\.\\d+"
        )

        fun sanitizeText(input: String): String {
            var current = QUERY_PARAM_PATTERN.replace(input) { match ->
                "${match.groupValues[1]}=[REDACTED]"
            }
            current = LABELED_COORDINATE_PATTERN.replace(current) { match ->
                "${match.groupValues[1]}=[REDACTED]"
            }
            current = COORDINATE_PAIR_PATTERN.replace(current, "[REDACTED_COORDINATES]")
            return current
        }
    }
}

/**
 * Thread-safe diagnostic logger backed by an in-memory buffer with built-in sanitization.
 * Useful for unit testing privacy guarantees and engine diagnostics without Android dependencies.
 */
class InMemoryDiagnosticLogger(
    private val clock: Clock = Clock.systemUTC(),
    private val maxEvents: Int = 500
) : DiagnosticLogger {
    private val buffer = CopyOnWriteArrayList<DiagnosticEvent>()
    private val sanitizer = SanitizingDiagnosticLogger(
        object : DiagnosticLogger {
            override fun log(
                level: LogLevel,
                component: String,
                event: String,
                attributes: Map<String, String>
            ) {
                if (buffer.size >= maxEvents) {
                    buffer.removeAt(0)
                }
                buffer.add(
                    DiagnosticEvent(
                        timestamp = clock.instant(),
                        level = level,
                        component = component,
                        event = event,
                        attributes = attributes
                    )
                )
            }
        }
    )

    override fun log(
        level: LogLevel,
        component: String,
        event: String,
        attributes: Map<String, String>
    ) {
        sanitizer.log(level, component, event, attributes)
    }

    fun events(): List<DiagnosticEvent> = buffer.toList()

    fun clear() {
        buffer.clear()
    }
}
