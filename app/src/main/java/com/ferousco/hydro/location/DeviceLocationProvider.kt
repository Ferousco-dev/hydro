package com.ferousco.hydro.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ferousco.hydro.core.integration.LocationFix
import com.ferousco.hydro.core.model.GeoLocation
import java.time.Instant
import java.time.ZoneId

/**
 * Reads the device's most recent cached fix without holding a location subscription.
 *
 * Hydro only needs an approximate position to query a weather grid cell, so a last-known fix is
 * sufficient and avoids a battery-draining continuous location stream. Returns `null` when the
 * permission has not been granted or the platform has no cached fix yet; callers must fall back to
 * an explicit default location rather than inventing coordinates.
 */
class DeviceLocationProvider(private val context: Context) {

    fun hasLocationPermission(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return coarse || fine
    }

    fun lastKnownLocation(): GeoLocation? = lastKnownFix()?.location

    /**
     * Returns the newest cached fix together with the platform metadata Hydro needs to be honest
     * about it: the capture time, reported accuracy, provider, and mock-location flag.
     */
    // Permission is checked by `hasLocationPermission()` and every platform read is wrapped in
    // `runCatching`, so a revocation between the check and the call is handled rather than crashing.
    @SuppressLint("MissingPermission")
    fun lastKnownFix(): LocationFix? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val fix = LOCATION_PROVIDERS
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter { it.hasValidCoordinate() }
            .maxByOrNull { it.time }
            ?: return null

        return fix.toLocationFix()
    }

    private fun Location.hasValidCoordinate(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun Location.toLocationFix(): LocationFix = LocationFix(
        location = toGeoLocation(),
        capturedAt = Instant.ofEpochMilli(time),
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        provider = provider ?: UNKNOWN_PROVIDER,
        isMock = isMockLocation()
    )

    @Suppress("DEPRECATION")
    private fun Location.isMockLocation(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider

    private fun Location.toGeoLocation(): GeoLocation = GeoLocation(
        latitude = latitude,
        longitude = longitude,
        elevationMeters = if (hasAltitude()) altitude else null,
        timeZone = ZoneId.systemDefault(),
        label = "Near you"
    )

    private companion object {
        const val UNKNOWN_PROVIDER = "unknown"

        val LOCATION_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    }
}
