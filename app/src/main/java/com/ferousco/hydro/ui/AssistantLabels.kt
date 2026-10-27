package com.ferousco.hydro.ui

/**
 * Plain-language wording for what the assistant is doing. Tool ids are internal and never shown.
 *
 * [activity] is the live line while a step runs ("Checking air quality"); [source] is the short
 * noun used in the caption under a finished answer ("Used weather and air quality").
 */
internal object AssistantLabels {
    private data class Label(val activity: String, val source: String)

    private val labels = mapOf(
        "get_weather" to Label("Checking the weather", "weather"),
        "get_hourly_forecast" to Label("Reading the forecast", "forecast"),
        "get_historical_weather" to Label("Looking at past weather", "past weather"),
        "calculate_heat_risk" to Label("Working out heat risk", "heat risk"),
        "compare_forecast_periods" to Label("Comparing times to go", "best times"),
        "query_memory" to Label("Checking your history", "your history"),
        "get_current_location" to Label("Finding where you are", "your location"),
        "analyze_anomaly" to Label("Comparing with usual conditions", "usual conditions"),
        "get_route_conditions" to Label("Checking your route", "your route"),
        "get_heat_map" to Label("Mapping heat nearby", "heat map"),
        "get_wearable_data" to Label("Reading your health data", "your health data"),
        "create_notification" to Label("Setting up an alert", "alerts"),
        "plan_hydration" to Label("Planning your water", "water plan"),
        "get_environmental_news" to Label("Reading local news", "local news"),
        "get_air_quality" to Label("Checking air quality", "air quality"),
        "get_solar_climate" to Label("Checking recent sun and rain", "recent sun and rain"),
        "geocode_place" to Label("Finding the place", "place lookup"),
        "get_pollen" to Label("Checking pollen", "pollen"),
        "find_nearby_relief" to Label("Finding places to cool off", "nearby places"),
        "get_google_weather" to Label("Cross-checking the forecast", "a second forecast"),
        "find_relief_within_reach" to Label("Finding places you can reach", "reachable places"),
        "get_area_insights" to Label("Scanning the area", "the area"),
        "get_elevation_profile" to Label("Checking the hills", "elevation"),
        "get_time_zone" to Label("Checking the time zone", "time zone")
    )

    fun activity(toolName: String): String = labels[toolName]?.activity ?: "Working on it"

    fun source(toolName: String): String? = labels[toolName]?.source

    /** "weather", "weather and air quality", "weather, forecast and air quality". */
    fun joinSources(sources: List<String>): String = when (sources.size) {
        0 -> ""
        1 -> sources[0]
        else -> sources.dropLast(1).joinToString(", ") + " and " + sources.last()
    }
}
