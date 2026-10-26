package com.ferousco.hydro.core.integration

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.provider.HttpGetRequest
import com.ferousco.hydro.core.provider.HttpPostRequest
import com.ferousco.hydro.core.provider.HttpTransport
import com.ferousco.hydro.core.provider.UrlConnectionHttpTransport
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// ============================================================================
// News (Tavily)
// ============================================================================

enum class NewsFocus(val queryTerms: String) {
    HEAT("heatwave extreme heat warning"),
    AIR_QUALITY("air pollution smog air quality alert"),
    FLOODING("flood warning heavy rainfall"),
    STORMS("storm severe weather warning"),
    HEALTH("heat related illness public health advisory"),
    GENERAL("weather environment alert")
}

data class NewsRequest(
    val place: String,
    val focus: NewsFocus,
    val days: Int,
    val maxResults: Int
)

data class NewsArticle(
    val title: String,
    val url: String,
    val snippet: String,
    val publishedAt: String?,
    val relevanceScore: Double?
)

data class NewsDigest(
    val query: String,
    val summary: String?,
    val articles: List<NewsArticle>,
    val retrievedAt: Instant,
    val providerId: String
)

interface NewsProvider {
    suspend fun search(request: NewsRequest): EngineResult<NewsDigest>
}

/** Used when no `TAVILY_API_KEY` is configured; fails explicitly instead of returning fake news. */
object UnavailableNewsProvider : NewsProvider {
    override suspend fun search(request: NewsRequest): EngineResult<NewsDigest> =
        EngineResult.Failure(
            EngineError.UnsupportedCalculation(
                methodId = "news_search",
                reason = "no_news_provider_configured",
                message = "News search is unavailable: no TAVILY_API_KEY was configured."
            )
        )
}

/**
 * Tavily news search.
 *
 * Privacy: only a place *name* and topic keywords leave the device. Coordinates are never sent.
 * Safety: article text is untrusted third-party content; it is length-capped and the tool layer
 * labels it so the model treats it as data, never as instructions.
 */
class TavilyNewsProvider(
    private val apiKey: String,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC()
) : NewsProvider {

    override suspend fun search(request: NewsRequest): EngineResult<NewsDigest> {
        if (apiKey.isBlank()) return UnavailableNewsProvider.search(request)

        val query = "${request.place} ${request.focus.queryTerms}".trim()
        val body = SafeJsonCodec.stringify(
            JsonValue.ObjectValue(
                linkedMapOf(
                    "query" to JsonValue.StringValue(query),
                    "topic" to JsonValue.StringValue("news"),
                    "days" to num(request.days.toDouble()),
                    "max_results" to num(request.maxResults.toDouble()),
                    "include_answer" to JsonValue.BooleanValue(true)
                )
            )
        )
        val response = when (
            val r = transport.post(
                HttpPostRequest(
                    uri = URI.create("https://api.tavily.com/search"),
                    headers = mapOf(
                        "Authorization" to "Bearer $apiKey",
                        "Content-Type" to "application/json",
                        "Accept" to "application/json"
                    ),
                    body = body
                ),
                PROVIDER_ID
            )
        ) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        if (response.statusCode !in 200..299) return providerFailure(PROVIDER_ID, response.statusCode)

        val json = when (val p = SafeJsonCodec.parseObject(response.body, "tavily_response")) {
            is EngineResult.Failure -> return p
            is EngineResult.Success -> p.value
        }
        val articles = json.getArray("results")?.items.orEmpty().mapNotNull { item ->
            val o = item as? JsonValue.ObjectValue ?: return@mapNotNull null
            val url = o.getString("url") ?: return@mapNotNull null
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            NewsArticle(
                title = (o.getString("title") ?: "(untitled)").take(MAX_TITLE),
                url = url,
                snippet = (o.getString("content") ?: "").take(MAX_SNIPPET),
                publishedAt = o.getString("published_date"),
                relevanceScore = o.getDouble("score")
            )
        }.take(request.maxResults)

        return EngineResult.Success(
            NewsDigest(
                query = query,
                summary = json.getString("answer")?.take(MAX_SNIPPET * 2),
                articles = articles,
                retrievedAt = clock.instant(),
                providerId = PROVIDER_ID
            )
        )
    }

    companion object {
        const val PROVIDER_ID = "tavily"
        private const val MAX_TITLE = 160
        private const val MAX_SNIPPET = 420
    }
}

// ============================================================================
// Air quality (Open-Meteo Air Quality API, no key required)
// ============================================================================

enum class AirQualityCategory(val displayName: String, val guidance: String) {
    GOOD("Good", "Air quality is satisfactory."),
    MODERATE("Moderate", "Unusually sensitive people should consider reducing prolonged outdoor exertion."),
    UNHEALTHY_SENSITIVE("Unhealthy for sensitive groups", "Children, older adults and people with asthma or heart disease should limit prolonged outdoor exertion."),
    UNHEALTHY("Unhealthy", "Everyone may begin to experience effects; reduce prolonged or heavy outdoor exertion."),
    VERY_UNHEALTHY("Very unhealthy", "Avoid prolonged outdoor exertion; sensitive groups should stay indoors."),
    HAZARDOUS("Hazardous", "Avoid outdoor activity.");

    companion object {
        /** US EPA AQI breakpoints. */
        fun fromUsAqi(aqi: Double): AirQualityCategory = when {
            aqi <= 50.0 -> GOOD
            aqi <= 100.0 -> MODERATE
            aqi <= 150.0 -> UNHEALTHY_SENSITIVE
            aqi <= 200.0 -> UNHEALTHY
            aqi <= 300.0 -> VERY_UNHEALTHY
            else -> HAZARDOUS
        }
    }
}

data class AirQualityReading(
    val observedAt: String?,
    val usAqi: Double?,
    val europeanAqi: Double?,
    val category: AirQualityCategory?,
    val pollutantsMicrogramsPerM3: Map<String, Double>,
    val retrievedAt: Instant,
    val providerId: String,
    val dominantPollutant: String? = null,
    val universalAqi: Double? = null,
    val healthRecommendations: Map<String, String> = emptyMap()
)

interface AirQualityProvider {
    suspend fun current(location: GeoLocation): EngineResult<AirQualityReading>
}

class OpenMeteoAirQualityProvider(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC()
) : AirQualityProvider {

    override suspend fun current(location: GeoLocation): EngineResult<AirQualityReading> {
        val variables = listOf(
            "us_aqi", "european_aqi", "pm2_5", "pm10", "ozone",
            "nitrogen_dioxide", "sulphur_dioxide", "carbon_monoxide", "dust"
        )
        val uri = URI.create(
            "https://air-quality-api.open-meteo.com/v1/air-quality" +
                "?latitude=${fmt(location.latitude)}&longitude=${fmt(location.longitude)}" +
                "&current=${variables.joinToString(",")}&timezone=UTC"
        )
        val response = when (
            val r = transport.get(
                HttpGetRequest(uri, mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT)),
                PROVIDER_ID
            )
        ) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        if (response.statusCode !in 200..299) return providerFailure(PROVIDER_ID, response.statusCode)

        val json = when (val p = SafeJsonCodec.parseObject(response.body, "open_meteo_air_quality")) {
            is EngineResult.Failure -> return p
            is EngineResult.Success -> p.value
        }
        val current = json.getObject("current")
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "current", "Air-quality response has no 'current' block.")
            )
        val usAqi = current.getDouble("us_aqi")
        val pollutants = linkedMapOf<String, Double>()
        for (key in listOf("pm2_5", "pm10", "ozone", "nitrogen_dioxide", "sulphur_dioxide", "carbon_monoxide", "dust")) {
            current.getDouble(key)?.let { pollutants[key] = it }
        }
        return EngineResult.Success(
            AirQualityReading(
                observedAt = current.getString("time")?.let { "${it}Z" },
                usAqi = usAqi,
                europeanAqi = current.getDouble("european_aqi"),
                category = usAqi?.let(AirQualityCategory::fromUsAqi),
                pollutantsMicrogramsPerM3 = pollutants,
                retrievedAt = clock.instant(),
                providerId = PROVIDER_ID
            )
        )
    }

    companion object {
        const val PROVIDER_ID = "open-meteo-air-quality"
        private const val USER_AGENT = "Hydro/0.1 (+https://github.com/Ferousco-dev/Hydro)"
    }
}

// ============================================================================
// Solar and recent climate (NASA POWER, no key required)
// ============================================================================

data class SolarClimateDay(
    val date: LocalDate,
    val solarKwhPerM2: Double?,
    val maxTemperatureCelsius: Double?,
    val minTemperatureCelsius: Double?,
    val relativeHumidityPercent: Double?,
    val rainfallMm: Double?
)

data class SolarClimateSeries(
    val days: List<SolarClimateDay>,
    val retrievedAt: Instant,
    val providerId: String
)

interface SolarClimateProvider {
    suspend fun recent(location: GeoLocation, days: Int): EngineResult<SolarClimateSeries>
}

/**
 * NASA POWER daily point data. Values are satellite/reanalysis-derived and lag real time by a few
 * days, so this is a *recent-history* signal, not a current observation.
 */
class NasaPowerSolarClimateProvider(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clock: Clock = Clock.systemUTC()
) : SolarClimateProvider {

    override suspend fun recent(location: GeoLocation, days: Int): EngineResult<SolarClimateSeries> {
        val end = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(LATENCY_DAYS)
        val start = end.minusDays((days - 1).toLong())
        val f = DateTimeFormatter.BASIC_ISO_DATE
        val uri = URI.create(
            "https://power.larc.nasa.gov/api/temporal/daily/point" +
                "?parameters=ALLSKY_SFC_SW_DWN,T2M_MAX,T2M_MIN,RH2M,PRECTOTCORR" +
                "&community=RE&longitude=${fmt(location.longitude)}&latitude=${fmt(location.latitude)}" +
                "&start=${f.format(start)}&end=${f.format(end)}&format=JSON"
        )
        val response = when (
            val r = transport.get(
                HttpGetRequest(uri, mapOf("Accept" to "application/json"), readTimeout = java.time.Duration.ofSeconds(30)),
                PROVIDER_ID
            )
        ) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        if (response.statusCode !in 200..299) return providerFailure(PROVIDER_ID, response.statusCode)

        val json = when (val p = SafeJsonCodec.parseObject(response.body, "nasa_power", maxChars = 400_000)) {
            is EngineResult.Failure -> return p
            is EngineResult.Success -> p.value
        }
        val params = json.getObject("properties")?.getObject("parameter")
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "properties.parameter", "NASA POWER response has no parameter block.")
            )
        val series = (0 until days).map { offset ->
            val date = start.plusDays(offset.toLong())
            val key = f.format(date)
            fun value(name: String): Double? =
                params.getObject(name)?.getDouble(key)?.takeIf { it > MISSING_SENTINEL }
            SolarClimateDay(
                date = date,
                solarKwhPerM2 = value("ALLSKY_SFC_SW_DWN"),
                maxTemperatureCelsius = value("T2M_MAX"),
                minTemperatureCelsius = value("T2M_MIN"),
                relativeHumidityPercent = value("RH2M"),
                rainfallMm = value("PRECTOTCORR")
            )
        }
        return EngineResult.Success(SolarClimateSeries(series, clock.instant(), PROVIDER_ID))
    }

    companion object {
        const val PROVIDER_ID = "nasa-power"
        private const val LATENCY_DAYS = 3L
        /** NASA POWER encodes missing values as -999. */
        private const val MISSING_SENTINEL = -990.0
    }
}

// ============================================================================
// Shared helpers
// ============================================================================

private fun num(d: Double): JsonValue = JsonValue.NumberValue(d.toString(), d)

private fun fmt(d: Double): String = String.format(Locale.ROOT, "%.4f", d)

private fun providerFailure(providerId: String, status: Int): EngineResult.Failure =
    EngineResult.Failure(
        EngineError.ProviderError(
            providerId = providerId,
            statusCode = status,
            providerReason = null,
            isTransient = status == 429 || status >= 500,
            message = "$providerId returned HTTP $status."
        )
    )
