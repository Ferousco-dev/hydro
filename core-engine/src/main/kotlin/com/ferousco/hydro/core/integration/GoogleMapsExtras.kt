package com.ferousco.hydro.core.integration

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.provider.HttpTransport
import com.ferousco.hydro.core.provider.UrlConnectionHttpTransport
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

/**
 * Four extra Google Maps Platform data sources: Isochrones, Places Aggregate (Area Insights),
 * Elevation and Time Zone. They share [GoogleMapsConfig] with the other Google providers.
 *
 * Request shapes for Isochrones and Area Insights come from preview/secondary documentation and are
 * verified only against fake responses until the first live run; failures surface the provider's own
 * error text so a wrong field name is obvious.
 */

private fun noKey(method: String) = EngineResult.Failure(
    EngineError.UnsupportedCalculation(method, "google_maps_api_key_missing", "$method is unavailable: no GOOGLE_MAPS_API_KEY was configured.")
)

private fun fmt(d: Double) = String.format(Locale.ROOT, "%.5f", d)

// ============================================================================
// Isochrones: "what can I actually reach in N minutes?"
// ============================================================================

enum class IsochroneMode(val apiName: String, val maxMinutes: Int) {
    WALK("WALK", 120),
    BICYCLE("BICYCLE", 120),
    DRIVE("DRIVE", 60)
}

/** A reachable area as GeoJSON-style polygons: polygon -> ring -> (longitude, latitude). First ring is the outer boundary. */
data class Isochrone(
    val origin: GeoLocation,
    val mode: IsochroneMode,
    val minutes: Int,
    val polygons: List<List<List<Pair<Double, Double>>>>,
    val retrievedAt: Instant,
    val providerId: String
) {
    fun contains(point: GeoLocation): Boolean = polygons.any { rings ->
        val outer = rings.firstOrNull() ?: return@any false
        pointInRing(point.longitude, point.latitude, outer) &&
            rings.drop(1).none { hole -> pointInRing(point.longitude, point.latitude, hole) }
    }

    /** Approximate area in km2 using a local equirectangular projection (fine at city scale). */
    fun approximateAreaKm2(): Double {
        val lat0 = Math.toRadians(origin.latitude)
        fun ringArea(ring: List<Pair<Double, Double>>): Double {
            if (ring.size < 3) return 0.0
            var sum = 0.0
            for (i in ring.indices) {
                val (x1, y1) = project(ring[i], lat0)
                val (x2, y2) = project(ring[(i + 1) % ring.size], lat0)
                sum += x1 * y2 - x2 * y1
            }
            return abs(sum) / 2.0
        }
        return polygons.sumOf { rings ->
            ringArea(rings.firstOrNull().orEmpty()) - rings.drop(1).sumOf(::ringArea)
        }
    }

    private fun project(p: Pair<Double, Double>, lat0: Double): Pair<Double, Double> =
        Pair(Math.toRadians(p.first) * 6371.0 * cos(lat0), Math.toRadians(p.second) * 6371.0)

    private fun pointInRing(x: Double, y: Double, ring: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (xi, yi) = ring[i]
            val (xj, yj) = ring[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

interface IsochroneProvider {
    suspend fun reachable(origin: GeoLocation, mode: IsochroneMode, minutes: Int): EngineResult<Isochrone>
}

object UnavailableIsochroneProvider : IsochroneProvider {
    override suspend fun reachable(origin: GeoLocation, mode: IsochroneMode, minutes: Int): EngineResult<Isochrone> = noKey("google-isochrones")
}

class GoogleIsochroneProvider(
    config: GoogleMapsConfig,
    transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC()
) : IsochroneProvider {
    private val client = GoogleJsonClient(config, transport)

    override suspend fun reachable(origin: GeoLocation, mode: IsochroneMode, minutes: Int): EngineResult<Isochrone> {
        val body = jobj(
            "location" to jobj("latitude" to jnum(origin.latitude), "longitude" to jnum(origin.longitude)),
            "travelDuration" to jstr("${minutes * 60}s"),
            "travelMode" to jstr(mode.apiName),
            "travelDirection" to jstr("FROM"),
            "enableSmoothing" to JsonValue.BooleanValue(false)
        )
        val json = when (val r = client.post(PROVIDER_ID, "https://isochrones.googleapis.com/v1/isochrones:generate", body)) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val geo = json.getObject("isochrone")?.getObject("geoJson")
            ?: return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "isochrone.geoJson", "Isochrone response had no geometry."))
        val polygons = parseGeoJson(geo)
            ?: return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "geoJson.coordinates", "Unsupported or malformed isochrone geometry."))
        return EngineResult.Success(Isochrone(origin, mode, minutes, polygons, clock.instant(), PROVIDER_ID))
    }

    private fun parseGeoJson(geo: JsonValue.ObjectValue): List<List<List<Pair<Double, Double>>>>? {
        val coords = geo.getArray("coordinates") ?: return null
        fun ring(v: JsonValue): List<Pair<Double, Double>>? = (v as? JsonValue.ArrayValue)?.items?.map { p ->
            val a = (p as? JsonValue.ArrayValue)?.items ?: return null
            val lon = (a.getOrNull(0) as? JsonValue.NumberValue)?.toDoubleOrNull() ?: return null
            val lat = (a.getOrNull(1) as? JsonValue.NumberValue)?.toDoubleOrNull() ?: return null
            lon to lat
        }
        fun polygon(v: JsonValue): List<List<Pair<Double, Double>>>? =
            (v as? JsonValue.ArrayValue)?.items?.map { ring(it) ?: return null }
        return when (geo.getString("type")) {
            "MultiPolygon" -> coords.items.map { polygon(it) ?: return null }
            "Polygon" -> listOf(polygon(coords) ?: return null)
            else -> null
        }
    }

    companion object { const val PROVIDER_ID = "google-isochrones" }
}

// ============================================================================
// Places Aggregate (Area Insights): how much help exists around here?
// ============================================================================

enum class InsightGroup(val placeTypes: List<String>, val meaning: String) {
    GREEN_SPACE(listOf("park", "garden"), "Parks and gardens (a rough proxy for shade and cooler pockets)"),
    MEDICAL(listOf("hospital", "pharmacy", "doctor"), "Hospitals, pharmacies and doctors"),
    INDOOR_COOLING(listOf("shopping_mall", "library", "museum"), "Likely air-conditioned public indoor spaces"),
    WATER_AND_SUPPLIES(listOf("convenience_store", "supermarket"), "Places to buy water and supplies")
}

data class AreaInsights(
    val center: GeoLocation,
    val radiusMeters: Int,
    val counts: Map<InsightGroup, Long?>,
    val failedGroups: Map<InsightGroup, String>,
    val retrievedAt: Instant,
    val providerId: String
) {
    val areaKm2: Double get() = Math.PI * (radiusMeters / 1000.0) * (radiusMeters / 1000.0)
}

interface AreaInsightsProvider {
    suspend fun insights(center: GeoLocation, radiusMeters: Int, groups: Set<InsightGroup>): EngineResult<AreaInsights>
}

object UnavailableAreaInsightsProvider : AreaInsightsProvider {
    override suspend fun insights(center: GeoLocation, radiusMeters: Int, groups: Set<InsightGroup>): EngineResult<AreaInsights> = noKey("google-area-insights")
}

class GoogleAreaInsightsProvider(
    config: GoogleMapsConfig,
    transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC()
) : AreaInsightsProvider {
    private val client = GoogleJsonClient(config, transport)

    override suspend fun insights(center: GeoLocation, radiusMeters: Int, groups: Set<InsightGroup>): EngineResult<AreaInsights> {
        val counts = linkedMapOf<InsightGroup, Long?>()
        val failed = linkedMapOf<InsightGroup, String>()
        for (group in groups) {
            val body = jobj(
                "insights" to JsonValue.ArrayValue(listOf(jstr("INSIGHT_COUNT"))),
                "filter" to jobj(
                    "locationFilter" to jobj(
                        "circle" to jobj(
                            "latLng" to jobj("latitude" to jnum(center.latitude), "longitude" to jnum(center.longitude)),
                            "radius" to jnum(radiusMeters.toDouble())
                        )
                    ),
                    "typeFilter" to jobj("includedTypes" to JsonValue.ArrayValue(group.placeTypes.map(::jstr))),
                    "operatingStatus" to JsonValue.ArrayValue(listOf(jstr("OPERATING_STATUS_OPERATIONAL")))
                )
            )
            when (val r = client.post(PROVIDER_ID, "https://areainsights.googleapis.com/v1:computeInsights", body)) {
                is EngineResult.Failure -> {
                    // A key/API problem affects every group; stop early instead of repeating the failure.
                    if (counts.isEmpty() && failed.isEmpty()) return r
                    failed[group] = r.error.message
                }
                is EngineResult.Success -> {
                    // int64 counts arrive as JSON strings.
                    val raw = r.value["count"]
                    counts[group] = when (raw) {
                        is JsonValue.StringValue -> raw.value.toLongOrNull()
                        is JsonValue.NumberValue -> raw.toLongExactOrNull()
                        null -> 0L // the API omits the field when the count is zero
                        else -> null
                    }
                }
            }
        }
        return EngineResult.Success(AreaInsights(center, radiusMeters, counts, failed, clock.instant(), PROVIDER_ID))
    }

    companion object { const val PROVIDER_ID = "google-area-insights" }
}

// ============================================================================
// Elevation: how hilly is the route?
// ============================================================================

data class ElevationProfile(
    val samplesMeters: List<Double>,
    val minMeters: Double,
    val maxMeters: Double,
    val totalAscentMeters: Double,
    val totalDescentMeters: Double,
    val retrievedAt: Instant,
    val providerId: String
)

interface ElevationProvider {
    suspend fun profile(path: List<GeoLocation>, samples: Int): EngineResult<ElevationProfile>
}

object UnavailableElevationProvider : ElevationProvider {
    override suspend fun profile(path: List<GeoLocation>, samples: Int): EngineResult<ElevationProfile> = noKey("google-elevation")
}

class GoogleElevationProvider(
    private val config: GoogleMapsConfig,
    transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC()
) : ElevationProvider {
    private val client = GoogleJsonClient(config, transport)

    override suspend fun profile(path: List<GeoLocation>, samples: Int): EngineResult<ElevationProfile> {
        val pathParam = path.joinToString("|") { "${fmt(it.latitude)},${fmt(it.longitude)}" }
        val url = "https://maps.googleapis.com/maps/api/elevation/json?path=" +
            java.net.URLEncoder.encode(pathParam, Charsets.UTF_8) + "&samples=$samples"
        val json = when (val r = client.get(PROVIDER_ID, url, keyInQuery = true)) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val status = json.getString("status")
        if (status != "OK") {
            return EngineResult.Failure(
                EngineError.ProviderError(PROVIDER_ID, null, status, false, message = "Elevation failed: $status ${json.getString("error_message").orEmpty()}".trim())
            )
        }
        val elevations = json.getArray("results")?.items.orEmpty()
            .mapNotNull { (it as? JsonValue.ObjectValue)?.getDouble("elevation") }
        if (elevations.size < 2) {
            return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "results", "Elevation returned fewer than two samples."))
        }
        var up = 0.0
        var down = 0.0
        for (i in 1 until elevations.size) {
            val d = elevations[i] - elevations[i - 1]
            if (d > 0) up += d else down -= d
        }
        return EngineResult.Success(
            ElevationProfile(elevations, elevations.min(), elevations.max(), up, down, clock.instant(), PROVIDER_ID)
        )
    }

    companion object { const val PROVIDER_ID = "google-elevation" }
}

// ============================================================================
// Time Zone
// ============================================================================

data class ResolvedTimeZone(
    val zone: ZoneId,
    val displayName: String?,
    val utcOffsetSeconds: Int,
    val providerId: String
)

interface TimeZoneProvider {
    suspend fun zoneFor(location: GeoLocation, at: Instant): EngineResult<ResolvedTimeZone>
}

object UnavailableTimeZoneProvider : TimeZoneProvider {
    override suspend fun zoneFor(location: GeoLocation, at: Instant): EngineResult<ResolvedTimeZone> = noKey("google-timezone")
}

class GoogleTimeZoneProvider(
    config: GoogleMapsConfig,
    transport: HttpTransport = UrlConnectionHttpTransport()
) : TimeZoneProvider {
    private val client = GoogleJsonClient(config, transport)

    override suspend fun zoneFor(location: GeoLocation, at: Instant): EngineResult<ResolvedTimeZone> {
        val url = "https://maps.googleapis.com/maps/api/timezone/json" +
            "?location=${fmt(location.latitude)},${fmt(location.longitude)}&timestamp=${at.epochSecond}"
        val json = when (val r = client.get(PROVIDER_ID, url, keyInQuery = true)) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val status = json.getString("status")
        if (status != "OK") {
            return EngineResult.Failure(
                EngineError.ProviderError(PROVIDER_ID, null, status, false, message = "Time zone lookup failed: $status".trim())
            )
        }
        val id = json.getString("timeZoneId")
            ?: return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "timeZoneId", "No time zone id returned."))
        val zone = try {
            ZoneId.of(id)
        } catch (_: Exception) {
            return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "timeZoneId", "Unrecognised time zone '$id'."))
        }
        val offset = (json.getDouble("rawOffset") ?: 0.0) + (json.getDouble("dstOffset") ?: 0.0)
        return EngineResult.Success(ResolvedTimeZone(zone, json.getString("timeZoneName"), offset.toInt(), PROVIDER_ID))
    }

    companion object { const val PROVIDER_ID = "google-timezone" }
}
