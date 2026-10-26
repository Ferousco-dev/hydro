package com.ferousco.hydro.core.integration

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.DataAcquisitionMethod
import com.ferousco.hydro.core.model.EnvironmentalDataSource
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.provider.HttpGetRequest
import com.ferousco.hydro.core.provider.HttpPostRequest
import com.ferousco.hydro.core.provider.HttpTransport
import com.ferousco.hydro.core.provider.UrlConnectionHttpTransport
import java.net.URI
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Google Maps Platform integrations.
 *
 * One key (`GOOGLE_MAPS_API_KEY`) unlocks: Routes, Geocoding, Places (New), Weather, Air Quality and
 * Pollen. The key is sent in the `X-Goog-Api-Key` header wherever the API supports it so it does not
 * land in URLs or logs. Every provider fails explicitly when a response is malformed or a field is
 * absent; none substitutes a default for missing data.
 *
 * Each API must be enabled on the key's Google Cloud project, and the key should be restricted.
 */
data class GoogleMapsConfig(val apiKey: String) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()
}

// ----------------------------------------------------------------------------
// Shared JSON-over-HTTP plumbing
// ----------------------------------------------------------------------------

internal class GoogleJsonClient(
    private val config: GoogleMapsConfig,
    private val transport: HttpTransport
) {
    private fun headers(extra: Map<String, String> = emptyMap()) =
        mapOf("X-Goog-Api-Key" to config.apiKey, "Accept" to "application/json") + extra

    suspend fun get(
        providerId: String,
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        keyInQuery: Boolean = false
    ): EngineResult<JsonValue.ObjectValue> {
        if (!config.isConfigured) return notConfigured(providerId)
        val finalUrl = if (keyInQuery) {
            url + (if ('?' in url) "&" else "?") + "key=" + URLEncoder.encode(config.apiKey, Charsets.UTF_8)
        } else {
            url
        }
        return parse(
            providerId,
            transport.get(
                HttpGetRequest(URI.create(finalUrl), if (keyInQuery) mapOf("Accept" to "application/json") else headers(extraHeaders)),
                providerId
            )
        )
    }

    suspend fun post(
        providerId: String,
        url: String,
        body: JsonValue.ObjectValue,
        extraHeaders: Map<String, String> = emptyMap()
    ): EngineResult<JsonValue.ObjectValue> {
        if (!config.isConfigured) return notConfigured(providerId)
        return parse(
            providerId,
            transport.post(
                HttpPostRequest(
                    uri = URI.create(url),
                    headers = headers(extraHeaders + ("Content-Type" to "application/json")),
                    body = SafeJsonCodec.stringify(body)
                ),
                providerId
            )
        )
    }

    private fun parse(
        providerId: String,
        raw: EngineResult<com.ferousco.hydro.core.provider.RawHttpResponse>
    ): EngineResult<JsonValue.ObjectValue> {
        val response = when (raw) {
            is EngineResult.Failure -> return raw
            is EngineResult.Success -> raw.value
        }
        if (response.statusCode !in 200..299) {
            val reason = (SafeJsonCodec.parseObject(response.body, "google_error") as? EngineResult.Success)
                ?.value?.getObject("error")?.getString("message")?.take(200)
            return EngineResult.Failure(
                EngineError.ProviderError(
                    providerId = providerId,
                    statusCode = response.statusCode,
                    providerReason = reason,
                    isTransient = response.statusCode == 429 || response.statusCode >= 500,
                    message = "$providerId returned HTTP ${response.statusCode}" +
                        (reason?.let { ": $it" } ?: ".") +
                        if (response.statusCode == 403) " Check that this API is enabled for the key." else ""
                )
            )
        }
        return SafeJsonCodec.parseObject(response.body, providerId, maxChars = 1_000_000)
    }

    private fun notConfigured(providerId: String) = EngineResult.Failure(
        EngineError.UnsupportedCalculation(
            methodId = providerId,
            reason = "google_maps_api_key_missing",
            message = "$providerId is unavailable: no GOOGLE_MAPS_API_KEY was configured."
        )
    )
}

internal fun jnum(d: Double): JsonValue = JsonValue.NumberValue(d.toString(), d)
internal fun jstr(s: String): JsonValue = JsonValue.StringValue(s)
internal fun jobj(vararg pairs: Pair<String, JsonValue>): JsonValue.ObjectValue =
    JsonValue.ObjectValue(linkedMapOf(*pairs))

private fun latLng(l: GeoLocation) =
    jobj("latitude" to jnum(l.latitude), "longitude" to jnum(l.longitude))

private fun fmt(d: Double) = String.format(Locale.ROOT, "%.5f", d)

// ============================================================================
// Routes API
// ============================================================================

/**
 * Real road/foot/bike/transit routing via the Routes API. Falls back to nothing: when the call
 * fails the caller sees the failure, and `HydroEngine.create` keeps the great-circle estimate as the
 * *explicitly labelled* default when no key is configured.
 */
class GoogleRoutesProvider(
    config: GoogleMapsConfig,
    transport: HttpTransport = UrlConnectionHttpTransport()
) : RoutingProvider {
    private val client = GoogleJsonClient(config, transport)

    override suspend fun planRoute(request: RouteRequest): EngineResult<RoutePlan> {
        val mode = when (request.travelMode) {
            TravelMode.WALKING -> "WALK"
            TravelMode.CYCLING -> "BICYCLE"
            TravelMode.DRIVING -> "DRIVE"
            TravelMode.TRANSIT -> "TRANSIT"
        }
        val body = jobj(
            "origin" to jobj("location" to jobj("latLng" to latLng(request.origin))),
            "destination" to jobj("location" to jobj("latLng" to latLng(request.destination))),
            "travelMode" to jstr(mode),
            "units" to jstr("METRIC")
        )
        val json = when (
            val r = client.post(
                PROVIDER_ID,
                "https://routes.googleapis.com/directions/v2:computeRoutes",
                body,
                mapOf("X-Goog-FieldMask" to "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline")
            )
        ) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val route = (json.getArray("routes")?.items?.firstOrNull() as? JsonValue.ObjectValue)
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "routes", "Google found no ${request.travelMode.displayName} route between those points.")
            )
        val meters = route.getDouble("distanceMeters")
            ?: return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "distanceMeters", "Route has no distance."))
        val seconds = route.getString("duration")?.removeSuffix("s")?.toDoubleOrNull()
            ?: return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "duration", "Route has no duration."))
        val encoded = route.getObject("polyline")?.getString("encodedPolyline")
        val geometry = encoded?.let { decodePolyline(it, request.origin.timeZone) }.orEmpty()
            .let { downsample(it, MAX_GEOMETRY_POINTS) }
        if (geometry.size < 2) {
            return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "polyline", "Route geometry was missing or too short."))
        }
        return EngineResult.Success(
            RoutePlan(
                distanceKm = meters / 1000.0,
                duration = Duration.ofSeconds(seconds.toLong().coerceAtLeast(60L)),
                geometry = geometry,
                method = "GOOGLE_ROUTES_${mode}",
                limitations = listOf(
                    "Road/path geometry and duration come from Google Routes; they do not account for shade, heat exposure or personal pace.",
                    "Duration reflects Google's typical conditions for this mode."
                )
            )
        )
    }

    companion object {
        const val PROVIDER_ID = "google-routes"
        private const val MAX_GEOMETRY_POINTS = 25

        /** Standard Google encoded-polyline algorithm (precision 1e-5). */
        fun decodePolyline(encoded: String, timeZone: java.time.ZoneId?): List<GeoLocation> {
            val out = ArrayList<GeoLocation>()
            var index = 0
            var lat = 0
            var lng = 0
            while (index < encoded.length) {
                var result = 0
                var shift = 0
                var b: Int
                do {
                    if (index >= encoded.length) return out
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
                result = 0
                shift = 0
                do {
                    if (index >= encoded.length) return out
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
                out += GeoLocation(latitude = lat / 1e5, longitude = lng / 1e5, timeZone = timeZone)
            }
            return out
        }

        internal fun downsample(points: List<GeoLocation>, max: Int): List<GeoLocation> {
            if (points.size <= max) return points
            return (0 until max).map { i -> points[(i * (points.size - 1)) / (max - 1)] }
        }
    }
}

// ============================================================================
// Geocoding (Google when keyed, Open-Meteo free geocoder otherwise)
// ============================================================================

data class GeocodedPlace(
    val name: String,
    val formattedAddress: String?,
    val location: GeoLocation,
    val country: String?,
    val providerId: String
)

interface Geocoder {
    suspend fun geocode(query: String, maxResults: Int): EngineResult<List<GeocodedPlace>>
}

class GoogleGeocoder(
    config: GoogleMapsConfig,
    transport: HttpTransport = UrlConnectionHttpTransport()
) : Geocoder {
    private val client = GoogleJsonClient(config, transport)

    override suspend fun geocode(query: String, maxResults: Int): EngineResult<List<GeocodedPlace>> {
        val url = "https://maps.googleapis.com/maps/api/geocode/json?address=" +
            URLEncoder.encode(query, Charsets.UTF_8)
        val json = when (val r = client.get(PROVIDER_ID, url, keyInQuery = true)) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val status = json.getString("status")
        if (status != "OK" && status != "ZERO_RESULTS") {
            return EngineResult.Failure(
                EngineError.ProviderError(PROVIDER_ID, null, status, false, message = "Geocoding failed: $status ${json.getString("error_message").orEmpty()}".trim())
            )
        }
        val places = json.getArray("results")?.items.orEmpty().mapNotNull { item ->
            val o = item as? JsonValue.ObjectValue ?: return@mapNotNull null
            val loc = o.getObject("geometry")?.getObject("location") ?: return@mapNotNull null
            val geo = GeoLocation.validated(loc.getDouble("lat") ?: return@mapNotNull null, loc.getDouble("lng") ?: return@mapNotNull null)
                .let { (it as? EngineResult.Success)?.value } ?: return@mapNotNull null
            val country = o.getArray("address_components")?.items.orEmpty()
                .firstOrNull { c -> (c as? JsonValue.ObjectValue)?.getArray("types")?.items?.any { t -> (t as? JsonValue.StringValue)?.value == "country" } == true }
                .let { (it as? JsonValue.ObjectValue)?.getString("long_name") }
            val address = o.getString("formatted_address")
            GeocodedPlace(address ?: query, address, geo.copy(label = address), country, PROVIDER_ID)
        }.take(maxResults)
        return EngineResult.Success(places)
    }

    companion object { const val PROVIDER_ID = "google-geocoding" }
}

/** Free fallback geocoder (no key). */
class OpenMeteoGeocoder(
    private val transport: HttpTransport = UrlConnectionHttpTransport()
) : Geocoder {
    override suspend fun geocode(query: String, maxResults: Int): EngineResult<List<GeocodedPlace>> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(query, Charsets.UTF_8) + "&count=$maxResults&format=json"
        val raw = transport.get(HttpGetRequest(URI.create(url), mapOf("Accept" to "application/json")), PROVIDER_ID)
        val response = when (raw) {
            is EngineResult.Failure -> return raw
            is EngineResult.Success -> raw.value
        }
        if (response.statusCode !in 200..299) {
            return EngineResult.Failure(
                EngineError.ProviderError(PROVIDER_ID, response.statusCode, null, response.statusCode >= 500, message = "Geocoder returned HTTP ${response.statusCode}.")
            )
        }
        val json = when (val p = SafeJsonCodec.parseObject(response.body, PROVIDER_ID)) {
            is EngineResult.Failure -> return p
            is EngineResult.Success -> p.value
        }
        val places = json.getArray("results")?.items.orEmpty().mapNotNull { item ->
            val o = item as? JsonValue.ObjectValue ?: return@mapNotNull null
            val name = o.getString("name") ?: return@mapNotNull null
            val geo = GeoLocation.validated(o.getDouble("latitude") ?: return@mapNotNull null, o.getDouble("longitude") ?: return@mapNotNull null)
                .let { (it as? EngineResult.Success)?.value } ?: return@mapNotNull null
            val country = o.getString("country")
            val label = listOfNotNull(name, o.getString("admin1"), country).joinToString(", ")
            GeocodedPlace(label, label, geo.copy(label = label), country, PROVIDER_ID)
        }
        return EngineResult.Success(places)
    }

    companion object { const val PROVIDER_ID = "open-meteo-geocoding" }
}

// ============================================================================
// Air Quality (Google): UAQI + local AQI + health recommendations
// ============================================================================

class GoogleAirQualityProvider(
    config: GoogleMapsConfig,
    transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC()
) : AirQualityProvider {
    private val client = GoogleJsonClient(config, transport)

    override suspend fun current(location: GeoLocation): EngineResult<AirQualityReading> {
        val body = jobj(
            "location" to latLng(location),
            "universalAqi" to JsonValue.BooleanValue(true),
            "extraComputations" to JsonValue.ArrayValue(
                listOf(
                    "LOCAL_AQI", "HEALTH_RECOMMENDATIONS", "DOMINANT_POLLUTANT_CONCENTRATION",
                    "POLLUTANT_CONCENTRATION", "POLLUTANT_ADDITIONAL_INFO"
                ).map(::jstr)
            )
        )
        val json = when (
            val r = client.post(PROVIDER_ID, "https://airquality.googleapis.com/v1/currentConditions:lookup", body)
        ) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val indexes = json.getArray("indexes")?.items.orEmpty().mapNotNull { it as? JsonValue.ObjectValue }
        if (indexes.isEmpty()) {
            return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "indexes", "Google has no air-quality coverage for this location.")
            )
        }
        val usAqi = indexes.firstOrNull { it.getString("code") == "usa_epa" }?.getDouble("aqi")
        val uaqi = indexes.firstOrNull { it.getString("code") == "uaqi" }
        val primary = usAqi ?: uaqi?.getDouble("aqi")
        val pollutants = linkedMapOf<String, Double>()
        for (p in json.getArray("pollutants")?.items.orEmpty()) {
            val o = p as? JsonValue.ObjectValue ?: continue
            val code = o.getString("code") ?: continue
            val value = o.getObject("concentration")?.getDouble("value") ?: continue
            pollutants[code] = value
        }
        val health = linkedMapOf<String, String>()
        json.getObject("healthRecommendations")?.entries?.forEach { (k, v) ->
            (v as? JsonValue.StringValue)?.value?.takeIf { it.isNotBlank() }?.let { health[k] = it.take(400) }
        }
        return EngineResult.Success(
            AirQualityReading(
                observedAt = json.getString("dateTime"),
                usAqi = usAqi,
                europeanAqi = null,
                category = usAqi?.let(AirQualityCategory::fromUsAqi),
                pollutantsMicrogramsPerM3 = pollutants,
                retrievedAt = clock.instant(),
                providerId = PROVIDER_ID,
                dominantPollutant = (uaqi ?: indexes.first()).getString("dominantPollutant"),
                universalAqi = uaqi?.getDouble("aqi"),
                healthRecommendations = health
            )
        )
    }

    companion object { const val PROVIDER_ID = "google-air-quality" }
}

/** Builds Google Air Quality heat-map tile URLs for a Maps SDK `TileOverlay` (UI step). */
object GoogleAirQualityTiles {
    enum class MapType(val code: String) {
        US_AQI("US_AQI"), UNIVERSAL_AQI("UAQI_INDIGO_PERSIAN"), PM25("PM25_INDIGO_PERSIAN"), NO2("NO2_INDIGO_PERSIAN")
    }

    /** The key must be appended by the caller from `BuildConfig`; it is never stored here. */
    fun urlTemplate(type: MapType): String =
        "https://airquality.googleapis.com/v1/mapTypes/${type.code}/heatmapTiles/{z}/{x}/{y}"
}

// ============================================================================
// Pollen
// ============================================================================

data class PollenDay(
    val date: String,
    val types: List<PollenTypeInfo>
)

data class PollenTypeInfo(
    val type: String,
    val inSeason: Boolean?,
    val indexValue: Int?,
    val category: String?,
    val advice: List<String>
)

data class PollenForecast(
    val days: List<PollenDay>,
    val retrievedAt: Instant,
    val providerId: String
)

interface PollenProvider {
    suspend fun forecast(location: GeoLocation, days: Int): EngineResult<PollenForecast>
}

class GooglePollenProvider(
    config: GoogleMapsConfig,
    transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC()
) : PollenProvider {
    private val client = GoogleJsonClient(config, transport)

    override suspend fun forecast(location: GeoLocation, days: Int): EngineResult<PollenForecast> {
        val url = "https://pollen.googleapis.com/v1/forecast:lookup" +
            "?location.latitude=${fmt(location.latitude)}&location.longitude=${fmt(location.longitude)}&days=$days"
        val json = when (val r = client.get(PROVIDER_ID, url)) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val daily = json.getArray("dailyInfo")?.items.orEmpty().mapNotNull { it as? JsonValue.ObjectValue }
        if (daily.isEmpty()) {
            return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "dailyInfo", "Google has no pollen coverage for this location.")
            )
        }
        val result = daily.map { d ->
            val date = d.getObject("date")?.let {
                "%04d-%02d-%02d".format(it.getInt("year") ?: 0, it.getInt("month") ?: 0, it.getInt("day") ?: 0)
            } ?: "unknown"
            PollenDay(
                date = date,
                types = d.getArray("pollenTypeInfo")?.items.orEmpty().mapNotNull { t ->
                    val o = t as? JsonValue.ObjectValue ?: return@mapNotNull null
                    val idx = o.getObject("indexInfo")
                    PollenTypeInfo(
                        type = o.getString("code") ?: return@mapNotNull null,
                        inSeason = (o["inSeason"] as? JsonValue.BooleanValue)?.value,
                        indexValue = idx?.getInt("value"),
                        category = idx?.getString("category"),
                        advice = o.getArray("healthRecommendations")?.items.orEmpty()
                            .mapNotNull { (it as? JsonValue.StringValue)?.value?.take(300) }
                    )
                }
            )
        }
        return EngineResult.Success(PollenForecast(result, clock.instant(), PROVIDER_ID))
    }

    companion object { const val PROVIDER_ID = "google-pollen" }
}

// ============================================================================
// Places (New): cooling, shade, water and medical help nearby
// ============================================================================

enum class ReliefKind(val placeTypes: List<String>, val purpose: String) {
    SHADE_AND_PARKS(listOf("park", "garden"), "Shade and green space"),
    INDOOR_COOLING(listOf("shopping_mall", "library", "supermarket", "museum"), "Air-conditioned indoor refuge"),
    WATER_AND_SUPPLIES(listOf("convenience_store", "supermarket", "cafe"), "Water, drinks and supplies"),
    MEDICAL(listOf("hospital", "pharmacy", "doctor"), "Medical help")
}

data class NearbyPlace(
    val name: String,
    val address: String?,
    val location: GeoLocation,
    val distanceKm: Double,
    val primaryType: String?,
    val openNow: Boolean?
)

data class NearbyRelief(
    val kind: ReliefKind,
    val places: List<NearbyPlace>,
    val retrievedAt: Instant,
    val providerId: String
)

interface PlacesProvider {
    suspend fun nearby(center: GeoLocation, kind: ReliefKind, radiusMeters: Int, maxResults: Int): EngineResult<NearbyRelief>
}

class GooglePlacesProvider(
    config: GoogleMapsConfig,
    transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC()
) : PlacesProvider {
    private val client = GoogleJsonClient(config, transport)

    override suspend fun nearby(center: GeoLocation, kind: ReliefKind, radiusMeters: Int, maxResults: Int): EngineResult<NearbyRelief> {
        val body = jobj(
            "includedTypes" to JsonValue.ArrayValue(kind.placeTypes.map(::jstr)),
            "maxResultCount" to jnum(maxResults.toDouble()),
            "rankPreference" to jstr("DISTANCE"),
            "locationRestriction" to jobj(
                "circle" to jobj("center" to latLng(center), "radius" to jnum(radiusMeters.toDouble()))
            )
        )
        val json = when (
            val r = client.post(
                PROVIDER_ID, "https://places.googleapis.com/v1/places:searchNearby", body,
                mapOf("X-Goog-FieldMask" to "places.displayName,places.formattedAddress,places.location,places.primaryType,places.currentOpeningHours.openNow")
            )
        ) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val places = json.getArray("places")?.items.orEmpty().mapNotNull { item ->
            val o = item as? JsonValue.ObjectValue ?: return@mapNotNull null
            val loc = o.getObject("location") ?: return@mapNotNull null
            val geo = (GeoLocation.validated(loc.getDouble("latitude") ?: return@mapNotNull null, loc.getDouble("longitude") ?: return@mapNotNull null) as? EngineResult.Success)?.value
                ?: return@mapNotNull null
            NearbyPlace(
                name = o.getObject("displayName")?.getString("text") ?: return@mapNotNull null,
                address = o.getString("formattedAddress"),
                location = geo,
                distanceKm = center.distanceKilometersTo(geo),
                primaryType = o.getString("primaryType"),
                openNow = (o.getObject("currentOpeningHours")?.get("openNow") as? JsonValue.BooleanValue)?.value
            )
        }.sortedBy { it.distanceKm }
        return EngineResult.Success(NearbyRelief(kind, places, clock.instant(), PROVIDER_ID))
    }

    companion object { const val PROVIDER_ID = "google-places" }
}

// ============================================================================
// Google Weather: a second opinion with thunderstorm, precipitation and heat index
// ============================================================================

data class GoogleWeatherHour(
    val startTime: String?,
    val description: String?,
    val temperatureC: Double?,
    val feelsLikeC: Double?,
    val humidityPercent: Double?,
    val uvIndex: Double?,
    val rainProbabilityPercent: Double?,
    val thunderstormProbabilityPercent: Double?,
    val windKmh: Double?
)

data class GoogleWeatherSnapshot(
    val current: GoogleWeatherHour,
    val heatIndexC: Double?,
    val cloudCoverPercent: Double?,
    val visibilityKm: Double?,
    val hourly: List<GoogleWeatherHour>,
    val retrievedAt: Instant,
    val providerId: String
)

interface GoogleWeatherProvider {
    suspend fun snapshot(location: GeoLocation, hours: Int): EngineResult<GoogleWeatherSnapshot>
}

class GoogleWeatherApiProvider(
    config: GoogleMapsConfig,
    transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC()
) : GoogleWeatherProvider {
    private val client = GoogleJsonClient(config, transport)

    override suspend fun snapshot(location: GeoLocation, hours: Int): EngineResult<GoogleWeatherSnapshot> {
        val coords = "location.latitude=${fmt(location.latitude)}&location.longitude=${fmt(location.longitude)}&unitsSystem=METRIC"
        val now = when (val r = client.get(PROVIDER_ID, "https://weather.googleapis.com/v1/currentConditions:lookup?$coords")) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val forecast = when (val r = client.get(PROVIDER_ID, "https://weather.googleapis.com/v1/forecast/hours:lookup?$coords&hours=$hours&pageSize=$hours")) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val current = hour(now, now.getString("currentTime"))
        if (current.temperatureC == null) {
            return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "temperature", "Google Weather returned no temperature."))
        }
        val hourly = forecast.getArray("forecastHours")?.items.orEmpty().mapNotNull { it as? JsonValue.ObjectValue }
            .map { hour(it, it.getObject("interval")?.getString("startTime")) }
        return EngineResult.Success(
            GoogleWeatherSnapshot(
                current = current,
                heatIndexC = now.getObject("heatIndex")?.getDouble("degrees"),
                cloudCoverPercent = now.getDouble("cloudCover"),
                visibilityKm = now.getObject("visibility")?.getDouble("distance"),
                hourly = hourly,
                retrievedAt = clock.instant(),
                providerId = PROVIDER_ID
            )
        )
    }

    private fun hour(o: JsonValue.ObjectValue, time: String?) = GoogleWeatherHour(
        startTime = time,
        description = o.getObject("weatherCondition")?.getObject("description")?.getString("text"),
        temperatureC = o.getObject("temperature")?.getDouble("degrees"),
        feelsLikeC = o.getObject("feelsLikeTemperature")?.getDouble("degrees"),
        humidityPercent = o.getDouble("relativeHumidity"),
        uvIndex = o.getDouble("uvIndex"),
        rainProbabilityPercent = o.getObject("precipitation")?.getObject("probability")?.getDouble("percent"),
        thunderstormProbabilityPercent = o.getDouble("thunderstormProbability"),
        windKmh = o.getObject("wind")?.getObject("speed")?.getDouble("value")
    )

    companion object { const val PROVIDER_ID = "google-weather" }
}

// ============================================================================
// Heat map: free gridded layer from one multi-location Open-Meteo request
// ============================================================================

/**
 * Builds a real gridded heat layer by sampling Open-Meteo at up to [MAX_CELLS] points in a single
 * request. Heat index is computed locally with the NWS formula, never taken from the model.
 *
 * Honest limits: this is a coarse *forecast-model* grid (roughly 2-10 km native resolution), not an
 * urban heat-island or street-level map, and it says so in [HeatMapLayer.limitations].
 */
class OpenMeteoGridHeatMapProvider(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC()
) : HeatMapProvider {

    override suspend fun heatMap(request: HeatMapRequest): EngineResult<HeatMapLayer> {
        val perSide = ((2.0 * request.radiusKm / request.resolutionKm).roundToInt() + 1).coerceIn(2, 7)
        val latStep = (2.0 * request.radiusKm / 111.0) / (perSide - 1)
        val lonStep = latStep / cos(Math.toRadians(request.center.latitude)).coerceAtLeast(0.2)
        val points = buildList {
            for (i in 0 until perSide) for (j in 0 until perSide) {
                val lat = request.center.latitude - (perSide - 1) * latStep / 2 + i * latStep
                val lon = request.center.longitude - (perSide - 1) * lonStep / 2 + j * lonStep
                if (lat in -90.0..90.0 && lon in -180.0..180.0) add(lat to lon)
            }
        }
        if (points.size < 4) {
            return EngineResult.Failure(EngineError.InvalidInput("center", "near_pole_or_antimeridian", "Heat-map area is outside the supported range."))
        }
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${points.joinToString(",") { fmt(it.first) }}" +
            "&longitude=${points.joinToString(",") { fmt(it.second) }}" +
            "&current=temperature_2m,relative_humidity_2m,uv_index&timezone=UTC"
        val raw = transport.get(HttpGetRequest(URI.create(url), mapOf("Accept" to "application/json")), PROVIDER_ID)
        val response = when (raw) {
            is EngineResult.Failure -> return raw
            is EngineResult.Success -> raw.value
        }
        if (response.statusCode !in 200..299) {
            return EngineResult.Failure(
                EngineError.ProviderError(PROVIDER_ID, response.statusCode, null, response.statusCode >= 500, message = "Heat-map sampling returned HTTP ${response.statusCode}.")
            )
        }
        val parsed = SafeJsonCodec.parse(response.body, PROVIDER_ID, maxChars = 800_000)
        val items = when (val v = (parsed as? EngineResult.Success)?.value) {
            is JsonValue.ArrayValue -> v.items
            is JsonValue.ObjectValue -> listOf(v)
            else -> return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "body", "Unparseable grid response."))
        }
        val cells = items.mapIndexedNotNull { idx, item ->
            val o = item as? JsonValue.ObjectValue ?: return@mapIndexedNotNull null
            val cur = o.getObject("current") ?: return@mapIndexedNotNull null
            val t = cur.getDouble("temperature_2m") ?: return@mapIndexedNotNull null
            val rh = cur.getDouble("relative_humidity_2m")
            val value = when (request.metric) {
                HeatMapMetric.AIR_TEMPERATURE_CELSIUS -> t
                HeatMapMetric.UV_INDEX -> cur.getDouble("uv_index") ?: return@mapIndexedNotNull null
                HeatMapMetric.HEAT_INDEX_CELSIUS -> {
                    val f = t * 9.0 / 5.0 + 32.0
                    val hi = HeatAssessmentEngine.computeNwsHeatIndexFahrenheit(f, rh ?: return@mapIndexedNotNull null).heatIndexFahrenheit
                    // The NWS formula is only defined from 80 F; below that it equals air temperature for our purposes.
                    if (hi < 80.0) t else (hi - 32.0) * 5.0 / 9.0
                }
            }
            val (lat, lon) = points.getOrNull(idx) ?: return@mapIndexedNotNull null
            val loc = (GeoLocation.validated(lat, lon) as? EngineResult.Success)?.value ?: return@mapIndexedNotNull null
            HeatMapCell(
                location = loc,
                value = value,
                category = if (request.metric == HeatMapMetric.HEAT_INDEX_CELSIUS) {
                    NwsHeatIndexCategory.fromHeatIndexFahrenheit(value * 9.0 / 5.0 + 32.0).displayName
                } else null
            )
        }
        if (cells.isEmpty()) {
            return EngineResult.Failure(EngineError.InvalidResponse(PROVIDER_ID, "current", "No grid cell had the variables needed for this metric."))
        }
        val legend = when (request.metric) {
            HeatMapMetric.UV_INDEX -> listOf(
                HeatMapLegendEntry("Low", 0.0, 3.0, "#4CAF50"), HeatMapLegendEntry("Moderate", 3.0, 6.0, "#FFEB3B"),
                HeatMapLegendEntry("High", 6.0, 8.0, "#FF9800"), HeatMapLegendEntry("Very high", 8.0, 11.0, "#F44336"),
                HeatMapLegendEntry("Extreme", 11.0, null, "#9C27B0")
            )
            else -> listOf(
                HeatMapLegendEntry("Below caution", Double.NEGATIVE_INFINITY, 26.7, "#4CAF50"),
                HeatMapLegendEntry("Caution", 26.7, 32.2, "#FFEB3B"),
                HeatMapLegendEntry("Extreme caution", 32.2, 39.4, "#FF9800"),
                HeatMapLegendEntry("Danger", 39.4, 51.1, "#F44336"),
                HeatMapLegendEntry("Extreme danger", 51.1, null, "#7B1FA2")
            )
        }
        val now = clock.instant()
        return EngineResult.Success(
            HeatMapLayer(
                metric = request.metric,
                validAt = now,
                issuedOrRetrievedAt = now,
                resolutionKm = (2.0 * request.radiusKm) / (perSide - 1),
                cells = cells,
                legend = legend,
                source = EnvironmentalDataSource(
                    providerId = "open-meteo",
                    providerName = "Open-Meteo",
                    endpointOrDataset = "v1/forecast multi-location current",
                    acquisitionMethod = DataAcquisitionMethod.GRIDDED_MODEL_NOWCAST,
                    license = EnvironmentalDataSource.OPEN_METEO_CC_BY_LICENSE,
                    attribution = EnvironmentalDataSource.OPEN_METEO_ATTRIBUTION
                ),
                limitations = listOf(
                    "Coarse model grid (about ${"%.0f".format(Locale.ROOT, (2.0 * request.radiusKm) / (perSide - 1))} km spacing), not street-level or urban heat-island data.",
                    "Heat index is computed locally from modelled temperature and humidity, not measured on site."
                )
            )
        )
    }

    companion object {
        const val PROVIDER_ID = "open-meteo-grid"
        const val MAX_CELLS = 49
    }
}


// ============================================================================
// Explicit "not configured" defaults
// ============================================================================

private fun noKey(method: String) = EngineResult.Failure(
    EngineError.UnsupportedCalculation(method, "google_maps_api_key_missing", "$method is unavailable: no GOOGLE_MAPS_API_KEY was configured.")
)

object UnavailablePollenProvider : PollenProvider {
    override suspend fun forecast(location: GeoLocation, days: Int): EngineResult<PollenForecast> = noKey("google-pollen")
}

object UnavailablePlacesProvider : PlacesProvider {
    override suspend fun nearby(center: GeoLocation, kind: ReliefKind, radiusMeters: Int, maxResults: Int): EngineResult<NearbyRelief> = noKey("google-places")
}

object UnavailableGoogleWeatherProvider : GoogleWeatherProvider {
    override suspend fun snapshot(location: GeoLocation, hours: Int): EngineResult<GoogleWeatherSnapshot> = noKey("google-weather")
}
