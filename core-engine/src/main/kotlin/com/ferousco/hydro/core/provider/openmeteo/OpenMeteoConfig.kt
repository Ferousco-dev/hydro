package com.ferousco.hydro.core.provider.openmeteo

import java.net.URI
import java.time.Duration

/**
 * Configuration for Open-Meteo non-commercial API endpoints.
 *
 * Licensing note (verified against https://open-meteo.com/en/terms):
 * - Open-Meteo's public endpoints (`api.open-meteo.com` and `archive-api.open-meteo.com`)
 *   are licensed under CC-BY 4.0 strictly for non-commercial use (<10,000 calls/day).
 * - Commercial applications require a paid Open-Meteo subscription routed through an authorized
 *   backend proxy rather than embedding secret API keys inside the Android APK.
 * - No credentials or secrets are embedded in this configuration.
 */
data class OpenMeteoConfig(
    val forecastBaseUri: URI = URI.create("https://api.open-meteo.com/v1/forecast"),
    val historicalBaseUri: URI = URI.create("https://archive-api.open-meteo.com/v1/archive"),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(15),
    val maxRetryAttempts: Int = 2,
    val initialRetryBackoff: Duration = Duration.ofMillis(250),
    val userAgent: String = "HydroEnvironmentalEngine/0.1 (Non-Commercial; CC-BY-4.0)"
) {
    init {
        require(forecastBaseUri.scheme.equals("https", ignoreCase = true)) {
            "forecastBaseUri must use HTTPS."
        }
        require(historicalBaseUri.scheme.equals("https", ignoreCase = true)) {
            "historicalBaseUri must use HTTPS."
        }
        require(forecastBaseUri.userInfo == null && historicalBaseUri.userInfo == null) {
            "Credentials must not be embedded in provider base URIs."
        }
        require(maxRetryAttempts in 1..4) {
            "maxRetryAttempts must be between 1 and 4."
        }
        require(!connectTimeout.isNegative && !connectTimeout.isZero) {
            "connectTimeout must be positive."
        }
        require(!readTimeout.isNegative && !readTimeout.isZero) {
            "readTimeout must be positive."
        }
    }

    companion object {
        const val MAX_FORECAST_HOURS = 384 // 16 days * 24 hours per Open-Meteo docs
        const val MAX_HISTORICAL_SPAN_DAYS = 366L
        val MIN_HISTORICAL_DATE_YEAR = 1940
    }
}
