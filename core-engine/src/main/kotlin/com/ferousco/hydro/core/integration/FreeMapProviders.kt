package com.ferousco.hydro.core.integration

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.provider.HttpGetRequest
import com.ferousco.hydro.core.provider.HttpTransport
import com.ferousco.hydro.core.provider.UrlConnectionHttpTransport
import java.net.URI
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.util.Locale

/**
 * Key-free map data, built on OpenStreetMap.
 *
 * These stand in for the Google providers so routes, nearby places and search still work before a
 * Google Maps key is usable. The `Fallback*` wrappers try the first provider and use the second only
 * when the first fails, so adding a working Google key switches everything back with no other change.
 *
 * The public OSM-run servers are fine for an MVP but have fair-use limits; point [OsrmRoutingProvider]
 * and [OverpassPlacesProvider] at a hosted plan before heavy public use.
 */
private const val USER_AGENT = "Hydro/1.0 (heat-safety app; https://hydro.appmd.dev)"

private fun jsonHeaders() = mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT)

// ============================================================================
// Routing: OSRM (foot, bike and car profiles run by FOSSGIS)
// ============================================================================

class OsrmRoutingProvider(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val baseUrl: String = "https://routing.openstreetmap.de"
) : RoutingProvider {

    override suspend fun planRoute(request: RouteRequest): EngineResult<RoutePlan> {
        val (path, profile) = when (request.travelMode) {
            TravelMode.WALKING -> "routed-foot" to "foot"
            TravelMode.CYCLING -> "routed-bike" to "bike"
            TravelMode.DRIVING -> "routed-car" to "driving"
            TravelMode.TRANSIT -> return EngineResult.Failure(
                EngineError.UnsupportedCalculation(PROVIDER_ID, "transit_unsupported", "OpenStreetMap routing has no public-transport profile.")
            )
        }
        val o = request.origin
        val d = request.destination
        val url = "$baseUrl/$path/route/v1/$profile/" +
            "${coord(o.longitude)},${coord(o.latitude)};${coord(d.longitude)},${coord(d.latitude)}" +
            "?overview=full&geometries=geojson"
        val response = when (val raw = transport.get(HttpGetRequest(URI.create(url), jsonHeaders()), PROVIDER_ID)) {
            is EngineResult.Failure -> return raw
            is EngineResult.Success -> raw.value
        }
        if (response.statusCode !in 200..299) {
            return EngineResult.Failure(
                EngineError.ProviderError(PROVIDER_ID, response.statusCode, null, response.statusCode == 429 || response.statusCode >= 500,
                    message = "Routing returned HTTP ${response.statusCode}.")
            )
        }
        val json = when (val p = SafeJsonCodec.parseObject(response.body, PROVIDER_ID, maxChars = 2_000_000)) {
            is EngineResult.Failure -> return p
            is EngineResult.Success -> p.value
        }
        val route = json.getArray("routes")?.items?.firstOrNull() as? JsonValue.ObjectValue
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "routes", "No ${request.travelMode.displayName} route found between those points.")
            )
        val meters = route.getDouble("distance")?.takeIf { it > 0.0 }
            ?: return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "distance", "Route has no distance."))
        val seconds = route.getDouble("duration")
            ?: return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "duration", "Route has no duration."))
        val points = route.getObject("geometry")?.getArray("coordinates")?.items.orEmpty().mapNotNull { item ->
            val pair = (item as? JsonValue.ArrayValue)?.items ?: return@mapNotNull null
            val lon = (pair.getOrNull(0) as? JsonValue.NumberValue)?.toDoubleOrNull() ?: return@mapNotNull null
            val lat = (pair.getOrNull(1) as? JsonValue.NumberValue)?.toDoubleOrNull() ?: return@mapNotNull null
            (GeoLocation.validated(lat, lon) as? EngineResult.Success)?.value?.copy(timeZone = request.origin.timeZone)
        }
        val geometry = GoogleRoutesProvider.downsample(points, MAX_GEOMETRY_POINTS)
        if (geometry.size < 2) {
            return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "geometry", "Route geometry was missing or too short."))
        }
        return EngineResult.Success(
            RoutePlan(
                distanceKm = meters / 1000.0,
                duration = Duration.ofSeconds(seconds.toLong().coerceAtLeast(60L)),
                geometry = geometry,
                method = "OSRM_${profile.uppercase(Locale.ROOT)}",
                limitations = listOf(
                    "Path geometry and duration come from OpenStreetMap routing; they do not account for shade, heat exposure or personal pace.",
                    "Duration is a typical ${request.travelMode.displayName.lowercase(Locale.ROOT)} time without live traffic."
                )
            )
        )
    }

    private fun coord(v: Double) = String.format(Locale.ROOT, "%.6f", v)

    companion object {
        const val PROVIDER_ID = "osrm-routing"
        private const val MAX_GEOMETRY_POINTS = 60
    }
}

// ============================================================================
// Nearby places: OpenStreetMap via the Overpass API
// ============================================================================

class OverpassPlacesProvider(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC(),
    private val endpoint: String = "https://overpass-api.de/api/interpreter"
) : PlacesProvider {

    override suspend fun nearby(center: GeoLocation, kind: ReliefKind, radiusMeters: Int, maxResults: Int): EngineResult<NearbyRelief> {
        val around = "(around:$radiusMeters,${String.format(Locale.ROOT, "%.5f,%.5f", center.latitude, center.longitude)})"
        val clauses = filtersFor(kind).joinToString("") { "nwr$it$around;" }
        val query = "[out:json][timeout:15];($clauses);out center 80;"
        val url = endpoint + "?data=" + URLEncoder.encode(query, Charsets.UTF_8)
        val response = when (val raw = transport.get(HttpGetRequest(URI.create(url), jsonHeaders(), readTimeout = Duration.ofSeconds(25)), PROVIDER_ID)) {
            is EngineResult.Failure -> return raw
            is EngineResult.Success -> raw.value
        }
        if (response.statusCode !in 200..299) {
            return EngineResult.Failure(
                EngineError.ProviderError(PROVIDER_ID, response.statusCode, null, response.statusCode == 429 || response.statusCode >= 500,
                    message = "Places search returned HTTP ${response.statusCode}.")
            )
        }
        val json = when (val p = SafeJsonCodec.parseObject(response.body, PROVIDER_ID, maxChars = 2_000_000)) {
            is EngineResult.Failure -> return p
            is EngineResult.Success -> p.value
        }
        val places = json.getArray("elements")?.items.orEmpty().mapNotNull { item ->
            val o = item as? JsonValue.ObjectValue ?: return@mapNotNull null
            val tags = o.getObject("tags") ?: return@mapNotNull null
            val name = tags.getString("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Nodes carry lat/lon directly; ways and relations carry a computed centre.
            val at = o.getObject("center") ?: o
            val geo = (GeoLocation.validated(at.getDouble("lat") ?: return@mapNotNull null, at.getDouble("lon") ?: return@mapNotNull null) as? EngineResult.Success)?.value
                ?: return@mapNotNull null
            NearbyPlace(
                name = name,
                address = address(tags),
                location = geo,
                distanceKm = center.distanceKilometersTo(geo),
                primaryType = tags.getString("amenity") ?: tags.getString("leisure") ?: tags.getString("shop") ?: tags.getString("tourism"),
                openNow = null
            )
        }.distinctBy { it.name to (it.distanceKm * 1000).toInt() }.sortedBy { it.distanceKm }.take(maxResults)
        return EngineResult.Success(NearbyRelief(kind, places, clock.instant(), PROVIDER_ID))
    }

    private fun filtersFor(kind: ReliefKind): List<String> = when (kind) {
        ReliefKind.SHADE_AND_PARKS -> listOf("[\"leisure\"~\"^(park|garden)\$\"]")
        ReliefKind.INDOOR_COOLING -> listOf("[\"shop\"~\"^(mall|supermarket)\$\"]", "[\"amenity\"=\"library\"]", "[\"tourism\"=\"museum\"]")
        ReliefKind.WATER_AND_SUPPLIES -> listOf("[\"amenity\"~\"^(drinking_water|cafe)\$\"]", "[\"shop\"~\"^(convenience|supermarket)\$\"]")
        ReliefKind.MEDICAL -> listOf("[\"amenity\"~\"^(hospital|pharmacy|clinic|doctors)\$\"]")
    }

    private fun address(tags: JsonValue.ObjectValue): String? {
        val street = listOfNotNull(tags.getString("addr:housenumber"), tags.getString("addr:street")).joinToString(" ")
        return listOfNotNull(street.ifBlank { null }, tags.getString("addr:suburb") ?: tags.getString("addr:city"))
            .joinToString(", ").ifBlank { null }
    }

    companion object { const val PROVIDER_ID = "osm-overpass" }
}

// ============================================================================
// Automatic fallback: use the first provider, and the second only if the first fails
// ============================================================================

class FallbackRoutingProvider(
    private val primary: RoutingProvider,
    private val fallback: RoutingProvider
) : RoutingProvider {
    override suspend fun planRoute(request: RouteRequest): EngineResult<RoutePlan> =
        when (val first = primary.planRoute(request)) {
            is EngineResult.Success -> first
            is EngineResult.Failure -> fallback.planRoute(request)
        }
}

class FallbackGeocoder(
    private val primary: Geocoder,
    private val fallback: Geocoder
) : Geocoder {
    override suspend fun geocode(query: String, maxResults: Int): EngineResult<List<GeocodedPlace>> =
        when (val first = primary.geocode(query, maxResults)) {
            is EngineResult.Success -> first
            is EngineResult.Failure -> fallback.geocode(query, maxResults)
        }
}

class FallbackPlacesProvider(
    private val primary: PlacesProvider,
    private val fallback: PlacesProvider
) : PlacesProvider {
    override suspend fun nearby(center: GeoLocation, kind: ReliefKind, radiusMeters: Int, maxResults: Int): EngineResult<NearbyRelief> =
        when (val first = primary.nearby(center, kind, radiusMeters, maxResults)) {
            is EngineResult.Success -> first
            is EngineResult.Failure -> fallback.nearby(center, kind, radiusMeters, maxResults)
        }
}

/**
 * Keeps place search to Africa, with Nigeria first. Asks the wrapped geocoder for extra results so
 * that African matches are not crowded out by namesakes elsewhere, then drops everything outside.
 */
class AfricaGeocoder(private val inner: Geocoder) : Geocoder {
    override suspend fun geocode(query: String, maxResults: Int): EngineResult<List<GeocodedPlace>> {
        val found = when (val r = inner.geocode(query, (maxResults * 3).coerceAtMost(20))) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val african = found.filter { com.ferousco.hydro.core.model.AfricaCoverage.contains(it.location) }
        if (african.isEmpty() && found.isNotEmpty()) {
            return EngineResult.Failure(
                EngineError.UnsupportedCalculation(
                    methodId = "geocoder",
                    reason = "outside_africa",
                    message = "Hydro covers Africa for now."
                )
            )
        }
        val nigeriaFirst = african.sortedBy { if (it.country.equals("Nigeria", ignoreCase = true)) 0 else 1 }
        return EngineResult.Success(nigeriaFirst.take(maxResults))
    }
}

/** Air quality from the first provider, or from the second when the first fails. */
class FallbackAirQualityProvider(
    private val primary: AirQualityProvider,
    private val fallback: AirQualityProvider
) : AirQualityProvider {
    override suspend fun current(location: GeoLocation): EngineResult<AirQualityReading> =
        when (val first = primary.current(location)) {
            is EngineResult.Success -> first
            is EngineResult.Failure -> fallback.current(location)
        }
}
