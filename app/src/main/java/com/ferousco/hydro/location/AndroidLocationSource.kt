package com.ferousco.hydro.location

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.integration.LocationFix
import com.ferousco.hydro.core.integration.LocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Adapts [DeviceLocationProvider] to the core engine's [LocationSource] boundary.
 *
 * The adapter reports permission and cache-miss states explicitly instead of fabricating
 * coordinates. Freshness is enforced by `GetCurrentLocationTool`, which compares the platform
 * capture time against the caller's `max_age_seconds`; this adapter does not silently refresh or
 * substitute a location.
 */
class AndroidLocationSource(
    private val provider: DeviceLocationProvider
) : LocationSource {

    override suspend fun currentLocation(maxAge: Duration): EngineResult<LocationFix> =
        withContext(Dispatchers.IO) {
            if (!provider.hasLocationPermission()) {
                return@withContext EngineResult.Failure(
                    EngineError.PolicyDenied(
                        capability = "LOCATION_READ",
                        message = "Location permission has not been granted. Hydro cannot read the " +
                            "device position without it."
                    )
                )
            }

            val fix = provider.lastKnownFix()
                ?: return@withContext EngineResult.Failure(
                    EngineError.UnsupportedCalculation(
                        methodId = "get_current_location",
                        reason = "NO_CACHED_LOCATION_FIX",
                        message = "The platform has no cached location fix yet. Enable location and " +
                            "wait for a fix, or pass explicit coordinates to the weather tools."
                    )
                )

            EngineResult.Success(fix)
        }
}
