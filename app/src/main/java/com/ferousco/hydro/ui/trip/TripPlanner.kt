package com.ferousco.hydro.ui.trip

import com.ferousco.hydro.core.HydroEngine
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.integration.RouteRequest
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.tools.ToolCallOutcome
import com.ferousco.hydro.core.trip.TripAdvice
import com.ferousco.hydro.core.trip.TripAdviceInput
import com.ferousco.hydro.core.trip.TripAdvisor
import com.ferousco.hydro.core.trip.TripMode
import com.ferousco.hydro.ui.TrendHour
import com.ferousco.hydro.ui.TripPoint
import com.ferousco.hydro.ui.jsonArgs
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The result of planning one trip. Only the route is essential; everything else may be missing. */
internal data class TripPlan(
    val route: List<TripPoint>,
    val distanceKm: Double,
    val durationMinutes: Int,
    val straightLine: Boolean,
    val worstCategory: String?,
    val worstRank: Int?,
    val advice: TripAdvice,
    val hours: List<TrendHour>
)

/**
 * Plans a trip entirely with the deterministic engine: the route, the heat expected along it, a
 * hydration estimate, a cooler time to go, and plain-language advice. No language model is needed,
 * so the panel fills in quickly and still works when the assistant is slow or offline.
 *
 * A failed route is a failed plan. A failed forecast or hydration lookup only removes the part
 * that depends on it; the advisor says nothing about heat it could not measure.
 */
internal class TripPlanner(private val engine: HydroEngine) {

    suspend fun plan(origin: GeoLocation, destination: GeoLocation, mode: TripMode): EngineResult<TripPlan> {
        val route = when (val r = engine.routingProvider.planRoute(RouteRequest(origin, destination, mode.travelMode, Instant.now()))) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val minutes = route.duration.toMinutes().toInt().coerceAtLeast(1)

        val conditions = routeConditions(origin, destination, mode)
        val hydrationMl = hydrationTarget(destination, mode, minutes)
        val coolerWindow = if ((conditions?.worstRank ?: 0) >= 1 && mode != TripMode.DRIVE) coolerWindow(destination, mode, minutes) else null
        val hours = ForecastBars.load(engine, destination, 24, emphasizeFirst = ceil(minutes / 60.0).toInt().coerceIn(1, 12))

        val points = route.geometry.mapIndexed { i, p ->
            val sample = conditions?.samples?.let { s ->
                if (s.isEmpty()) null else s[((i.toDouble() / (route.geometry.size - 1).coerceAtLeast(1)) * (s.size - 1)).roundToInt()]
            }
            TripPoint(p.latitude, p.longitude, sample?.severity ?: 0)
        }

        val advice = TripAdvisor.advise(
            TripAdviceInput(
                mode = mode,
                distanceKm = route.distanceKm,
                durationMinutes = minutes,
                worstHeatRank = conditions?.worstRank,
                peakHeatIndexCelsius = conditions?.peakHeatIndexC,
                maxUvIndex = conditions?.maxUv,
                hydrationTargetMl = hydrationMl,
                coolerWindow = coolerWindow
            )
        )
        return EngineResult.Success(
            TripPlan(
                route = points,
                distanceKm = route.distanceKm,
                durationMinutes = minutes,
                straightLine = route.method.contains("GREAT_CIRCLE"),
                worstCategory = conditions?.worstCategoryName,
                worstRank = conditions?.worstRank,
                advice = advice,
                hours = hours
            )
        )
    }

    private data class Sample(val severity: Int)
    private data class Conditions(
        val samples: List<Sample>,
        val worstRank: Int?,
        val worstCategoryName: String?,
        val peakHeatIndexC: Double?,
        val maxUv: Double?
    )

    private suspend fun routeConditions(origin: GeoLocation, destination: GeoLocation, mode: TripMode): Conditions? {
        val args = jsonArgs(
            "origin_latitude" to origin.latitude, "origin_longitude" to origin.longitude,
            "destination_latitude" to destination.latitude, "destination_longitude" to destination.longitude,
            "travel_mode" to mode.travelMode.name
        )
        val json = (engine.toolRegistry.executeJson("get_route_conditions", args) as? ToolCallOutcome.Success)?.jsonResult ?: return null
        val items = json.getArray("samples")?.items.orEmpty().mapNotNull { it as? JsonValue.ObjectValue }
        val ranks = items.map { s -> rankOf(s.getString("category")) }
        val worst = NwsHeatIndexCategory.entries.firstOrNull { it.name == json.getString("worst_category") }
        return Conditions(
            samples = ranks.map { Sample(it ?: 0) },
            worstRank = worst?.severityRank ?: ranks.filterNotNull().maxOrNull(),
            worstCategoryName = worst?.displayName,
            peakHeatIndexC = items.mapNotNull { it.getDouble("heat_index_celsius") }.maxOrNull(),
            maxUv = items.mapNotNull { it.getDouble("uv_index") }.maxOrNull()
        )
    }

    private fun rankOf(name: String?): Int? = NwsHeatIndexCategory.entries.firstOrNull { it.name == name }?.severityRank

    private suspend fun hydrationTarget(destination: GeoLocation, mode: TripMode, minutes: Int): Int? {
        if (mode == TripMode.DRIVE) return null
        val args = jsonArgs(
            "latitude" to destination.latitude, "longitude" to destination.longitude,
            "activity_category" to mode.activity.name,
            "duration_minutes" to minutes.coerceIn(15, 720),
            "intensity" to mode.intensity.name
        )
        val json = (engine.toolRegistry.executeJson("plan_hydration", args) as? ToolCallOutcome.Success)?.jsonResult ?: return null
        return json.getInt("target_fluid_ml")
    }

    private suspend fun coolerWindow(destination: GeoLocation, mode: TripMode, minutes: Int): String? {
        val recommendation = (engine.recommendForLocation(
            location = destination,
            activityCategory = mode.activity,
            plannedDuration = Duration.ofMinutes(minutes.toLong())
        ) as? EngineResult.Success)?.value ?: return null
        val window = recommendation.bestWindow ?: return null
        val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(destination.timeZone ?: ZoneId.systemDefault())
        return "${fmt.format(window.start)}–${fmt.format(window.end)}"
    }
}
