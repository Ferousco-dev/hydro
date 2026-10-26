package com.ferousco.hydro.core.tools

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.integration.AreaInsights
import com.ferousco.hydro.core.integration.AreaInsightsProvider
import com.ferousco.hydro.core.integration.ElevationProfile
import com.ferousco.hydro.core.integration.ElevationProvider
import com.ferousco.hydro.core.integration.Isochrone
import com.ferousco.hydro.core.integration.IsochroneMode
import com.ferousco.hydro.core.integration.IsochroneProvider
import com.ferousco.hydro.core.integration.InsightGroup
import com.ferousco.hydro.core.integration.NearbyPlace
import com.ferousco.hydro.core.integration.PlacesProvider
import com.ferousco.hydro.core.integration.ReliefKind
import com.ferousco.hydro.core.integration.ResolvedTimeZone
import com.ferousco.hydro.core.integration.TimeZoneProvider
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.model.GeoLocation
import java.time.Clock
import java.time.Duration
import java.time.Instant

private fun num(d: Double): JsonValue = JsonValue.NumberValue(d.toString(), d)
private fun str(s: String): JsonValue = JsonValue.StringValue(s)
private fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.ObjectValue =
    JsonValue.ObjectValue(linkedMapOf(*pairs))
private fun round2(d: Double) = Math.round(d * 100.0) / 100.0

private val mapsFailureSpec = ToolFailureSpec(
    executionTimeout = Duration.ofSeconds(35),
    documentedErrorCodes = listOf(
        "INVALID_ARGUMENTS", "PERMISSION_DENIED", "NETWORK_UNAVAILABLE", "PROVIDER_ERROR",
        "INVALID_PROVIDER_RESPONSE", "UNSUPPORTED_CALCULATION", "TOOL_TIMEOUT"
    )
)

private fun latLonProps() = listOf(
    ToolPropertySchema("latitude", ToolSchemaType.NUMBER, "Latitude [-90, 90].", required = true, minimum = -90.0, maximum = 90.0),
    ToolPropertySchema("longitude", ToolSchemaType.NUMBER, "Longitude [-180, 180].", required = true, minimum = -180.0, maximum = 180.0)
)

// ============================================================================
// 21. find_relief_within_reach
// ============================================================================

data class ReliefWithinReachParams(
    val origin: GeoLocation,
    val kind: ReliefKind,
    val mode: IsochroneMode,
    val minutes: Int
)

data class ReliefWithinReach(
    val isochrone: Isochrone,
    val kind: ReliefKind,
    val reachable: List<NearbyPlace>,
    val consideredCount: Int
)

/** Places that are *actually reachable* in N minutes, not merely close as the crow flies. */
class FindReliefWithinReachTool(
    private val isochrones: IsochroneProvider,
    private val places: PlacesProvider
) : HydroTool<ReliefWithinReachParams, ReliefWithinReach> {

    override val descriptor = ToolDescriptor(
        name = "find_relief_within_reach",
        description = "Find shade, indoor cooling, water or medical places that can be reached within N minutes on foot, " +
            "by bike or by car, using real travel time rather than straight-line distance.",
        requiredCapabilities = setOf(ToolCapability.EXTERNAL_DATA_READ),
        parameterSchema = ToolObjectSchema(
            properties = latLonProps() + listOf(
                ToolPropertySchema("kind", ToolSchemaType.ENUM, "Type of relief.", required = true, enumValues = ReliefKind.entries.map { it.name }),
                ToolPropertySchema("mode", ToolSchemaType.ENUM, "Travel mode (default WALK).", required = false, enumValues = IsochroneMode.entries.map { it.name }),
                ToolPropertySchema("minutes", ToolSchemaType.INTEGER, "Travel time budget in minutes [2, 30].", required = false, minimum = 2.0, maximum = 30.0),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Optional IANA timezone ID.", required = false, maxLength = 64)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("reachable_area_km2", ToolSchemaType.NUMBER, "Approximate reachable area."),
                ToolPropertySchema("places", ToolSchemaType.ARRAY, "Reachable places sorted by distance.", arrayItemType = ToolSchemaType.OBJECT),
                ToolPropertySchema("source", ToolSchemaType.OBJECT, "Providers used.")
            )
        ),
        failureSpec = mapsFailureSpec
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<ReliefWithinReachParams> {
        val origin = when (val l = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> return l
            is EngineResult.Success -> l.value
        }
        val kindName = rawArgs.getString("kind")
            ?: return EngineResult.Failure(EngineError.MissingRequiredInputs("find_relief_within_reach", listOf("kind"), "Missing kind."))
        val kind = ReliefKind.entries.firstOrNull { it.name == kindName }
            ?: return EngineResult.Failure(EngineError.InvalidInput("kind", kindName, "Unknown kind '$kindName'."))
        val mode = rawArgs.getString("mode")?.let { n ->
            IsochroneMode.entries.firstOrNull { it.name == n }
                ?: return EngineResult.Failure(EngineError.InvalidInput("mode", n, "Unknown mode '$n'."))
        } ?: IsochroneMode.WALK
        val minutes = rawArgs.getInt("minutes") ?: 10
        if (minutes !in 2..30) return EngineResult.Failure(EngineError.InvalidInput("minutes", minutes.toString(), "minutes must be 2..30."))
        return EngineResult.Success(ReliefWithinReachParams(origin, kind, mode, minutes))
    }

    override suspend fun executeTyped(params: ReliefWithinReachParams): EngineResult<ReliefWithinReach> {
        val iso = when (val r = isochrones.reachable(params.origin, params.mode, params.minutes)) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        // Search a radius that comfortably contains the isochrone, then keep only points inside it.
        val radiusMeters = (params.minutes * params.mode.searchMetersPerMinute()).coerceIn(300, 5000)
        val nearby = when (val r = places.nearby(params.origin, params.kind, radiusMeters, 20)) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val inside = nearby.places.filter { iso.contains(it.location) }
        return EngineResult.Success(ReliefWithinReach(iso, params.kind, inside, nearby.places.size))
    }

    private fun IsochroneMode.searchMetersPerMinute(): Int = when (this) {
        IsochroneMode.WALK -> 100
        IsochroneMode.BICYCLE -> 300
        IsochroneMode.DRIVE -> 600
    }

    override fun encodeResult(result: ReliefWithinReach): JsonValue.ObjectValue = obj(
        "kind" to str(result.kind.name),
        "mode" to str(result.isochrone.mode.name),
        "minutes" to num(result.isochrone.minutes.toDouble()),
        "reachable_area_km2" to num(round2(result.isochrone.approximateAreaKm2())),
        "candidates_considered" to num(result.consideredCount.toDouble()),
        "places" to JsonValue.ArrayValue(
            result.reachable.map {
                obj(
                    "name" to str(it.name),
                    "address" to (it.address?.let(::str) ?: JsonValue.NullValue),
                    "latitude" to num(it.location.latitude),
                    "longitude" to num(it.location.longitude),
                    "straight_line_km" to num(round2(it.distanceKm)),
                    "open_now" to (it.openNow?.let { b -> JsonValue.BooleanValue(b) } ?: JsonValue.NullValue)
                )
            }
        ),
        "source" to obj(
            "isochrone_provider" to str(result.isochrone.providerId),
            "retrieved_at" to str(result.isochrone.retrievedAt.toString())
        ),
        "note" to str("Only places inside the real travel-time area are listed; an empty list means none are reachable in that time.")
    )
}

// ============================================================================
// 22. get_area_insights
// ============================================================================

data class AreaInsightsParams(val center: GeoLocation, val radiusMeters: Int, val groups: Set<InsightGroup>)

class GetAreaInsightsTool(private val provider: AreaInsightsProvider) : HydroTool<AreaInsightsParams, AreaInsights> {
    override val descriptor = ToolDescriptor(
        name = "get_area_insights",
        description = "Counts of green space, medical facilities, indoor cooling spaces and water/supply shops around a point, " +
            "with density per km2. Green-space counts are a rough proxy for cooler pockets, not proof of shade.",
        requiredCapabilities = setOf(ToolCapability.EXTERNAL_DATA_READ),
        parameterSchema = ToolObjectSchema(
            properties = latLonProps() + listOf(
                ToolPropertySchema("radius_m", ToolSchemaType.INTEGER, "Radius in metres [200, 5000].", required = false, minimum = 200.0, maximum = 5000.0),
                ToolPropertySchema("timezone", ToolSchemaType.STRING, "Optional IANA timezone ID.", required = false, maxLength = 64)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("area_km2", ToolSchemaType.NUMBER, "Searched area."),
                ToolPropertySchema("groups", ToolSchemaType.ARRAY, "Per-group counts and density.", arrayItemType = ToolSchemaType.OBJECT)
            )
        ),
        failureSpec = mapsFailureSpec
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<AreaInsightsParams> {
        val center = when (val l = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> return l
            is EngineResult.Success -> l.value
        }
        val radius = rawArgs.getInt("radius_m") ?: 1500
        if (radius !in 200..5000) return EngineResult.Failure(EngineError.InvalidInput("radius_m", radius.toString(), "radius_m must be 200..5000."))
        return EngineResult.Success(AreaInsightsParams(center, radius, InsightGroup.entries.toSet()))
    }

    override suspend fun executeTyped(params: AreaInsightsParams): EngineResult<AreaInsights> =
        provider.insights(params.center, params.radiusMeters, params.groups)

    override fun encodeResult(result: AreaInsights): JsonValue.ObjectValue = obj(
        "radius_m" to num(result.radiusMeters.toDouble()),
        "area_km2" to num(round2(result.areaKm2)),
        "groups" to JsonValue.ArrayValue(
            result.counts.map { (group, count) ->
                obj(
                    "group" to str(group.name),
                    "meaning" to str(group.meaning),
                    "count" to (count?.let { num(it.toDouble()) } ?: JsonValue.NullValue),
                    "per_km2" to (count?.let { num(round2(it / result.areaKm2)) } ?: JsonValue.NullValue)
                )
            } + result.failedGroups.map { (group, why) ->
                obj("group" to str(group.name), "count" to JsonValue.NullValue, "error" to str(why))
            }
        ),
        "source" to obj("provider" to str(result.providerId), "retrieved_at" to str(result.retrievedAt.toString()))
    )
}

// ============================================================================
// 23. get_elevation_profile
// ============================================================================

data class ElevationParams(val origin: GeoLocation, val destination: GeoLocation, val samples: Int)

class GetElevationProfileTool(private val provider: ElevationProvider) : HydroTool<ElevationParams, ElevationProfile> {
    override val descriptor = ToolDescriptor(
        name = "get_elevation_profile",
        description = "Elevation along a straight path between two points: min, max, total ascent and descent in metres. " +
            "Use it to judge climbing effort on a walk, run or ride.",
        requiredCapabilities = setOf(ToolCapability.EXTERNAL_DATA_READ),
        parameterSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("origin_latitude", ToolSchemaType.NUMBER, "Start latitude.", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("origin_longitude", ToolSchemaType.NUMBER, "Start longitude.", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("destination_latitude", ToolSchemaType.NUMBER, "End latitude.", required = true, minimum = -90.0, maximum = 90.0),
                ToolPropertySchema("destination_longitude", ToolSchemaType.NUMBER, "End longitude.", required = true, minimum = -180.0, maximum = 180.0),
                ToolPropertySchema("samples", ToolSchemaType.INTEGER, "Number of sample points [2, 50].", required = false, minimum = 2.0, maximum = 50.0)
            )
        ),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("min_m", ToolSchemaType.NUMBER, "Lowest elevation."),
                ToolPropertySchema("max_m", ToolSchemaType.NUMBER, "Highest elevation."),
                ToolPropertySchema("total_ascent_m", ToolSchemaType.NUMBER, "Sum of uphill steps."),
                ToolPropertySchema("total_descent_m", ToolSchemaType.NUMBER, "Sum of downhill steps."),
                ToolPropertySchema("samples_m", ToolSchemaType.ARRAY, "Sampled elevations.", arrayItemType = ToolSchemaType.NUMBER)
            )
        ),
        failureSpec = mapsFailureSpec
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<ElevationParams> {
        val origin = when (val l = ToolEncodingSupport.parseLocation(rawArgs, "origin_latitude", "origin_longitude")) {
            is EngineResult.Failure -> return l
            is EngineResult.Success -> l.value
        }
        val destination = when (val l = ToolEncodingSupport.parseLocation(rawArgs, "destination_latitude", "destination_longitude")) {
            is EngineResult.Failure -> return l
            is EngineResult.Success -> l.value
        }
        val samples = rawArgs.getInt("samples") ?: 20
        if (samples !in 2..50) return EngineResult.Failure(EngineError.InvalidInput("samples", samples.toString(), "samples must be 2..50."))
        return EngineResult.Success(ElevationParams(origin, destination, samples))
    }

    override suspend fun executeTyped(params: ElevationParams): EngineResult<ElevationProfile> =
        provider.profile(listOf(params.origin, params.destination), params.samples)

    override fun encodeResult(result: ElevationProfile): JsonValue.ObjectValue = obj(
        "min_m" to num(round2(result.minMeters)),
        "max_m" to num(round2(result.maxMeters)),
        "total_ascent_m" to num(round2(result.totalAscentMeters)),
        "total_descent_m" to num(round2(result.totalDescentMeters)),
        "samples_m" to JsonValue.ArrayValue(result.samplesMeters.map { num(round2(it)) }),
        "note" to str("Straight-line path between the two points, not the road or trail; real routes can climb more."),
        "source" to obj("provider" to str(result.providerId), "retrieved_at" to str(result.retrievedAt.toString()))
    )
}

// ============================================================================
// 24. get_time_zone
// ============================================================================

data class TimeZoneParams(val location: GeoLocation, val at: Instant)

class GetTimeZoneTool(
    private val provider: TimeZoneProvider,
    private val clock: Clock = Clock.systemUTC()
) : HydroTool<TimeZoneParams, ResolvedTimeZone> {
    override val descriptor = ToolDescriptor(
        name = "get_time_zone",
        description = "IANA time zone and current UTC offset for a coordinate, so times for another place are stated correctly.",
        requiredCapabilities = setOf(ToolCapability.EXTERNAL_DATA_READ),
        parameterSchema = ToolObjectSchema(properties = latLonProps()),
        resultSchema = ToolObjectSchema(
            properties = listOf(
                ToolPropertySchema("time_zone_id", ToolSchemaType.STRING, "IANA zone id."),
                ToolPropertySchema("utc_offset_seconds", ToolSchemaType.INTEGER, "Offset including DST."),
                ToolPropertySchema("name", ToolSchemaType.STRING, "Human-readable zone name.", required = false)
            )
        ),
        failureSpec = mapsFailureSpec
    )

    override fun parseAndValidateArguments(rawArgs: JsonValue.ObjectValue): EngineResult<TimeZoneParams> =
        when (val l = ToolEncodingSupport.parseLocation(rawArgs)) {
            is EngineResult.Failure -> l
            is EngineResult.Success -> EngineResult.Success(TimeZoneParams(l.value, clock.instant()))
        }

    override suspend fun executeTyped(params: TimeZoneParams): EngineResult<ResolvedTimeZone> =
        provider.zoneFor(params.location, params.at)

    override fun encodeResult(result: ResolvedTimeZone): JsonValue.ObjectValue = obj(
        "time_zone_id" to str(result.zone.id),
        "utc_offset_seconds" to num(result.utcOffsetSeconds.toDouble()),
        "name" to (result.displayName?.let(::str) ?: JsonValue.NullValue),
        "source" to obj("provider" to str(result.providerId))
    )
}
