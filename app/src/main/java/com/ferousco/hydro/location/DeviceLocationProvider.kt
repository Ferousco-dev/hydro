package com.ferousco.hydro.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
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

    /**
     * Asks the phone for a fresh position, which is what makes "near you" right after a trip or on
     * a device with no cached fix. Tries the network provider first (fast, city-level is enough for
     * heat), then GPS, and only then settles for whatever is cached.
     */
    @SuppressLint("MissingPermission")
    suspend fun freshFix(timeoutMs: Long = 6_000L): LocationFix? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return lastKnownFix()
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        for (name in providers) {
            val location = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    runCatching {
                        LocationManagerCompat.getCurrentLocation(manager, name, signal, ContextCompat.getMainExecutor(context)) { fix ->
                            if (cont.isActive) cont.resume(fix)
                        }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            if (location != null && location.hasValidCoordinate()) return location.toLocationFix()
        }
        return lastKnownFix()
    }

    /**
     * A live stream of positions while the caller collects it, for "follow me". It ends at once if
     * location is off or not allowed, and stops listening the moment collection stops.
     */
    @SuppressLint("MissingPermission")
    fun updates(minIntervalMs: Long = 3_000L, minDistanceMeters: Float = 10f): Flow<GeoLocation> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        // GPS needs precise permission; with approximate only, the network position is what is allowed.
        val candidates = if (precise) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        else listOf(LocationManager.NETWORK_PROVIDER)
        val provider = manager?.let { m ->
            candidates.firstOrNull { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
        }
        if (manager == null || provider == null || !hasLocationPermission()) {
            close()
            return@callbackFlow
        }
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                if (location.hasValidCoordinate()) trySend(location.toGeoLocation())
            }
        }
        val request = LocationRequestCompat.Builder(minIntervalMs)
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        runCatching {
            LocationManagerCompat.requestLocationUpdates(manager, provider, request, ContextCompat.getMainExecutor(context), listener)
        }.onFailure { close() }
        awaitClose { runCatching { manager.removeUpdates(listener) } }
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
