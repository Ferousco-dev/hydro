package com.ferousco.hydro.core.model

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import java.time.ZoneId
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Validated WGS84 geographic coordinate with optional elevation and IANA time zone.
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val timeZone: ZoneId? = null,
    val label: String? = null
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be finite and within [-90.0, 90.0], got $latitude."
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be finite and within [-180.0, 180.0], got $longitude."
        }
        if (elevationMeters != null) {
            require(elevationMeters.isFinite() && elevationMeters in -500.0..10_000.0) {
                "Elevation must be finite and within [-500.0, 10000.0] meters, got $elevationMeters."
            }
        }
    }

    /**
     * Great-circle distance in kilometers using the Haversine formula.
     */
    fun distanceKilometersTo(other: GeoLocation): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)

        val a = sin(dLat / 2.0).pow(2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1.0 - a).coerceIn(0.0, 1.0)))
        return EARTH_RADIUS_KM * c
    }

    /**
     * Deterministic cache cell key rounded to [decimals] decimal places (~1.1 km at 2 decimals).
     * Never write this directly to external diagnostic logs.
     */
    fun cacheCellKey(decimals: Int = 2): String {
        val pattern = "%.${decimals.coerceIn(1, 4)}f"
        val lat = String.format(Locale.US, pattern, latitude)
        val lon = String.format(Locale.US, pattern, longitude)
        return "$lat,$lon"
    }

    /**
     * Privacy-safe representation suitable for diagnostic logging without exposing precise GPS coordinates.
     */
    fun redactedSummary(): String {
        val latBand = (latitude / 5.0).toInt() * 5
        val ns = if (latitude >= 0) "N" else "S"
        val ew = if (longitude >= 0) "E" else "W"
        val tz = timeZone?.id ?: "tz-unspecified"
        return "band=${kotlin.math.abs(latBand)}deg$ns/$ew,tz=$tz"
    }

    companion object {
        private const val EARTH_RADIUS_KM = 6371.0088

        fun validated(
            latitude: Double,
            longitude: Double,
            elevationMeters: Double? = null,
            timeZone: ZoneId? = null,
            label: String? = null
        ): EngineResult<GeoLocation> {
            if (!latitude.isFinite() || latitude !in -90.0..90.0) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(
                        field = "latitude",
                        rejectedValueSummary = "out_of_range",
                        message = "Latitude must be finite and between -90 and 90 degrees."
                    )
                )
            }
            if (!longitude.isFinite() || longitude !in -180.0..180.0) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(
                        field = "longitude",
                        rejectedValueSummary = "out_of_range",
                        message = "Longitude must be finite and between -180 and 180 degrees."
                    )
                )
            }
            if (elevationMeters != null && (!elevationMeters.isFinite() || elevationMeters !in -500.0..10_000.0)) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(
                        field = "elevationMeters",
                        rejectedValueSummary = "out_of_range",
                        message = "Elevation must be finite and between -500 and 10000 meters."
                    )
                )
            }
            return EngineResult.Success(
                GeoLocation(
                    latitude = latitude,
                    longitude = longitude,
                    elevationMeters = elevationMeters,
                    timeZone = timeZone,
                    label = label
                )
            )
        }
    }
}
