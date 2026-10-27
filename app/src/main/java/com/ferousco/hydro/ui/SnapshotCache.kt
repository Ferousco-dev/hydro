package com.ferousco.hydro.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Air quality for the Now page, already reduced to what people read. */
data class AirUi(
    val usAqi: Int?,
    val categoryName: String?,
    val pm25: Double?,
    val pm10: Double?,
    val dust: Double?,
    /** NONE, DUSTY or HEAVY. */
    val dustLevel: String,
    val inHarmattanSeason: Boolean,
    val source: String
)

/**
 * The last good answer, kept on the phone so the app opens with something useful when there is no
 * signal. It holds only what the Now and Trends pages show, and is replaced after every successful
 * refresh. Nothing here is sent anywhere.
 */
internal class SnapshotCache(context: Context) {
    private val prefs = context.getSharedPreferences("hydro_snapshot", Context.MODE_PRIVATE)

    fun save(state: HydroUiState) {
        if (state.airTemperatureCelsius == null && state.heatIndexCelsius == null) return
        val json = JSONObject().apply {
            put("label", state.locationLabel)
            putOpt("lat", state.mapLatitude)
            putOpt("lon", state.mapLongitude)
            putOpt("temp", state.airTemperatureCelsius)
            putOpt("feels", state.feelsLikeCelsius)
            putOpt("humidity", state.relativeHumidityPercent)
            putOpt("wind", state.windSpeedMetersPerSecond)
            putOpt("uv", state.uvIndex)
            putOpt("hi", state.heatIndexCelsius)
            putOpt("hiF", state.heatIndexFahrenheit)
            putOpt("category", state.heatCategory)
            putOpt("categorySummary", state.heatCategorySummary)
            putOpt("uvCategory", state.uvCategory)
            putOpt("explanation", state.assessmentExplanation)
            put("limitations", JSONArray(state.limitations))
            put("observedAt", (state.observationTime ?: Instant.now()).toEpochMilli())
            state.recommendation?.let { put("rec", recommendationJson(it)) }
            state.trend?.let { put("hours", JSONArray(it.hours.map(::hourJson))) }
            state.air?.let { put("air", airJson(it)) }
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    /** The saved state, marked as saved, or null when nothing usable was kept. */
    fun load(): HydroUiState? = runCatching {
        val text = prefs.getString(KEY, null) ?: return null
        val j = JSONObject(text)
        val hours = j.optJSONArray("hours")?.let { a -> (0 until a.length()).map { hourFrom(a.getJSONObject(it)) } }.orEmpty()
        val observedAt = Instant.ofEpochMilli(j.getLong("observedAt"))
        HydroUiState(
            locationLabel = j.optString("label", "Near you"),
            mapLatitude = j.optDoubleOrNull("lat"),
            mapLongitude = j.optDoubleOrNull("lon"),
            airTemperatureCelsius = j.optDoubleOrNull("temp"),
            feelsLikeCelsius = j.optDoubleOrNull("feels"),
            relativeHumidityPercent = j.optDoubleOrNull("humidity"),
            windSpeedMetersPerSecond = j.optDoubleOrNull("wind"),
            uvIndex = j.optDoubleOrNull("uv"),
            heatIndexCelsius = j.optDoubleOrNull("hi"),
            heatIndexFahrenheit = j.optDoubleOrNull("hiF"),
            heatCategory = j.optStringOrNull("category"),
            heatCategorySummary = j.optStringOrNull("categorySummary"),
            uvCategory = j.optStringOrNull("uvCategory"),
            assessmentExplanation = j.optStringOrNull("explanation"),
            limitations = j.optJSONArray("limitations")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            observationTime = observedAt,
            recommendation = j.optJSONObject("rec")?.let(::recommendationFrom),
            trend = if (hours.isEmpty()) null else TrendUi(
                hours = hours,
                hottest = hours.maxByOrNull { it.heatIndexC },
                coolest = hours.minByOrNull { it.heatIndexC }
            ),
            sky = hours.firstOrNull()?.let { h ->
                SkyUi(skyKindOf(h.weatherCode), h.isDay ?: true, h.severity, moonPhaseAt(System.currentTimeMillis()))
            },
            air = j.optJSONObject("air")?.let(::airFrom),
            showingSaved = true,
            savedAt = observedAt
        )
    }.getOrNull()

    private fun recommendationJson(r: RecommendationUi) = JSONObject().apply {
        put("urgency", r.urgency); put("headline", r.headline); put("summary", r.summary)
        put("actions", JSONArray(r.actions.map { JSONArray(listOf(it.first, it.second)) }))
        putOpt("bestWindow", r.bestWindow)
        put("limitations", JSONArray(r.limitations))
        put("current", r.isCurrentData)
    }

    private fun recommendationFrom(j: JSONObject) = RecommendationUi(
        urgency = j.getString("urgency"),
        headline = j.getString("headline"),
        summary = j.optString("summary"),
        actions = j.getJSONArray("actions").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } },
        bestWindow = j.optStringOrNull("bestWindow"),
        limitations = j.optJSONArray("limitations")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        // A saved answer is never presented as current.
        isCurrentData = false
    )

    private fun hourJson(h: TrendHour) = JSONObject().apply {
        put("label", h.label); put("hi", h.heatIndexC); put("sev", h.severity); put("cat", h.categoryName)
        put("now", h.isNow); putOpt("temp", h.tempC); putOpt("code", h.weatherCode); putOpt("day", h.isDay)
    }

    private fun hourFrom(j: JSONObject) = TrendHour(
        label = j.getString("label"), heatIndexC = j.getDouble("hi"), severity = j.getInt("sev"),
        categoryName = j.getString("cat"), isNow = j.getBoolean("now"), tempC = j.optDoubleOrNull("temp"),
        weatherCode = if (j.has("code")) j.getInt("code") else null,
        isDay = if (j.has("day")) j.getBoolean("day") else null
    )

    private fun airJson(a: AirUi) = JSONObject().apply {
        putOpt("aqi", a.usAqi); putOpt("cat", a.categoryName); putOpt("pm25", a.pm25); putOpt("pm10", a.pm10)
        putOpt("dust", a.dust); put("level", a.dustLevel); put("season", a.inHarmattanSeason); put("source", a.source)
    }

    private fun airFrom(j: JSONObject) = AirUi(
        usAqi = if (j.has("aqi")) j.getInt("aqi") else null,
        categoryName = j.optStringOrNull("cat"), pm25 = j.optDoubleOrNull("pm25"), pm10 = j.optDoubleOrNull("pm10"),
        dust = j.optDoubleOrNull("dust"), dustLevel = j.optString("level", "NONE"),
        inHarmattanSeason = j.optBoolean("season"), source = j.optString("source")
    )

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private companion object { const val KEY = "last" }
}
