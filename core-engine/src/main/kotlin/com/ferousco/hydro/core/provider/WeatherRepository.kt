package com.ferousco.hydro.core.provider

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.NoOpDiagnosticLogger
import com.ferousco.hydro.core.model.DataQualityAndFreshness
import com.ferousco.hydro.core.model.DeliveryOrigin
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.FreshnessPolicy
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HistoricalWeatherSeries
import com.ferousco.hydro.core.model.WeatherForecast
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

enum class CachePolicy {
    /** Attempt live network first; on offline/transient failure, return cached data explicitly marked as cached. */
    PREFER_FRESH_FALLBACK_CACHE,

    /** Require a live network response; never read from local cache. */
    NETWORK_ONLY,

    /** Read strictly from local cache without issuing network calls. */
    CACHE_ONLY
}

/**
 * Envelope returned by [WeatherRepository] preserving whether data came from live network or cache,
 * re-evaluated freshness at access time, and any live failure that triggered cache fallback.
 */
data class WeatherRetrievalEnvelope<out T>(
    val data: T,
    val deliveryOrigin: DeliveryOrigin,
    val freshness: DataQualityAndFreshness,
    val fallbackTriggerError: EngineError? = null
) {
    val isLiveAndFresh: Boolean
        get() = freshness.isCurrentAndLive
}

interface WeatherRepository {
    suspend fun getCurrentObservation(
        location: GeoLocation,
        cachePolicy: CachePolicy = CachePolicy.PREFER_FRESH_FALLBACK_CACHE
    ): EngineResult<WeatherRetrievalEnvelope<EnvironmentalObservation>>

    suspend fun getHourlyForecast(
        location: GeoLocation,
        forecastHours: Int = 48,
        cachePolicy: CachePolicy = CachePolicy.PREFER_FRESH_FALLBACK_CACHE
    ): EngineResult<WeatherRetrievalEnvelope<WeatherForecast>>

    suspend fun getHistoricalWeather(
        location: GeoLocation,
        startDate: LocalDate,
        endDate: LocalDate,
        cachePolicy: CachePolicy = CachePolicy.PREFER_FRESH_FALLBACK_CACHE
    ): EngineResult<WeatherRetrievalEnvelope<HistoricalWeatherSeries>>

    fun clearCache()
}

data class WeatherCacheConfig(
    val maxEntriesPerType: Int = 64,
    val maxOfflineFallbackAge: Duration = Duration.ofHours(12)
) {
    init {
        require(maxEntriesPerType > 0) { "maxEntriesPerType must be positive." }
        require(!maxOfflineFallbackAge.isNegative && !maxOfflineFallbackAge.isZero) {
            "maxOfflineFallbackAge must be positive."
        }
    }
}

/**
 * Repository coordinating [WeatherProvider], bounded in-memory caching, freshness re-evaluation,
 * and explicit offline / fallback semantics.
 *
 * Guarantees:
 * - Cached entries are NEVER presented as live network readings (`DeliveryOrigin.LOCAL_CACHE`,
 *   `QualityFlag.SERVED_FROM_CACHE`, `isCurrentAndLive == false`).
 * - Forecasts and observations are stored in separate typed caches and never conflated.
 */
class DefaultWeatherRepository(
    private val provider: WeatherProvider,
    private val freshnessPolicy: FreshnessPolicy = FreshnessPolicy(),
    private val cacheConfig: WeatherCacheConfig = WeatherCacheConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) : WeatherRepository {

    private val currentCache = BoundedLruCache<String, EnvironmentalObservation>(cacheConfig.maxEntriesPerType)
    private val forecastCache = BoundedLruCache<String, WeatherForecast>(cacheConfig.maxEntriesPerType)
    private val historicalCache = BoundedLruCache<String, HistoricalWeatherSeries>(cacheConfig.maxEntriesPerType)

    override suspend fun getCurrentObservation(
        location: GeoLocation,
        cachePolicy: CachePolicy
    ): EngineResult<WeatherRetrievalEnvelope<EnvironmentalObservation>> {
        val key = "${provider.providerId}:current:${location.cacheCellKey()}"

        if (cachePolicy == CachePolicy.CACHE_ONLY) {
            return readCachedCurrentOrFail(key, fallbackTrigger = null)
        }

        val liveResult = try {
            provider.fetchCurrentObservation(location)
        } catch (ce: CancellationException) {
            throw ce
        }

        return when (liveResult) {
            is EngineResult.Success -> {
                val now = clock.instant()
                val reEvaluatedQuality = liveResult.value.quality.reEvaluatedAt(
                    now = now,
                    newOrigin = DeliveryOrigin.LIVE_NETWORK,
                    isForecast = false,
                    policy = freshnessPolicy
                )
                val updated = liveResult.value.copy(quality = reEvaluatedQuality)
                currentCache.put(key, updated)
                EngineResult.Success(
                    WeatherRetrievalEnvelope(
                        data = updated,
                        deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
                        freshness = reEvaluatedQuality
                    )
                )
            }
            is EngineResult.Failure -> {
                if (cachePolicy == CachePolicy.PREFER_FRESH_FALLBACK_CACHE && isEligibleForCacheFallback(liveResult.error)) {
                    val cached = readCachedCurrentOrNull(key, liveResult.error)
                    if (cached != null) {
                        logger.info(
                            COMPONENT,
                            "Serving cached current observation after live failure",
                            mapOf(
                                "region" to location.redactedSummary(),
                                "freshnessState" to cached.freshness.freshnessState.name,
                                "trigger" to (liveResult.error::class.simpleName ?: "EngineError")
                            )
                        )
                        return EngineResult.Success(cached)
                    }
                }
                EngineResult.Failure(liveResult.error)
            }
        }
    }

    override suspend fun getHourlyForecast(
        location: GeoLocation,
        forecastHours: Int,
        cachePolicy: CachePolicy
    ): EngineResult<WeatherRetrievalEnvelope<WeatherForecast>> {
        val key = "${provider.providerId}:forecast:${location.cacheCellKey()}:$forecastHours"

        if (cachePolicy == CachePolicy.CACHE_ONLY) {
            return readCachedForecastOrFail(key, fallbackTrigger = null)
        }

        val liveResult = try {
            provider.fetchHourlyForecast(location, forecastHours)
        } catch (ce: CancellationException) {
            throw ce
        }

        return when (liveResult) {
            is EngineResult.Success -> {
                val now = clock.instant()
                val reEvaluatedQuality = liveResult.value.quality.reEvaluatedAt(
                    now = now,
                    newOrigin = DeliveryOrigin.LIVE_NETWORK,
                    isForecast = true,
                    policy = freshnessPolicy
                )
                val updated = liveResult.value.copy(quality = reEvaluatedQuality)
                forecastCache.put(key, updated)
                EngineResult.Success(
                    WeatherRetrievalEnvelope(
                        data = updated,
                        deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
                        freshness = reEvaluatedQuality
                    )
                )
            }
            is EngineResult.Failure -> {
                if (cachePolicy == CachePolicy.PREFER_FRESH_FALLBACK_CACHE && isEligibleForCacheFallback(liveResult.error)) {
                    val cached = readCachedForecastOrNull(key, liveResult.error)
                    if (cached != null) {
                        logger.info(
                            COMPONENT,
                            "Serving cached hourly forecast after live failure",
                            mapOf(
                                "region" to location.redactedSummary(),
                                "freshnessState" to cached.freshness.freshnessState.name,
                                "trigger" to (liveResult.error::class.simpleName ?: "EngineError")
                            )
                        )
                        return EngineResult.Success(cached)
                    }
                }
                EngineResult.Failure(liveResult.error)
            }
        }
    }

    override suspend fun getHistoricalWeather(
        location: GeoLocation,
        startDate: LocalDate,
        endDate: LocalDate,
        cachePolicy: CachePolicy
    ): EngineResult<WeatherRetrievalEnvelope<HistoricalWeatherSeries>> {
        val key = "${provider.providerId}:archive:${location.cacheCellKey()}:$startDate:$endDate"

        if (cachePolicy == CachePolicy.CACHE_ONLY) {
            val cached = readCachedHistoricalOrNull(key, fallbackTrigger = null)
                ?: return EngineResult.Failure(
                    EngineError.NetworkUnavailable("No cached historical weather available for requested window.")
                )
            return EngineResult.Success(cached)
        }

        // Historical reanalysis for past dates is immutable once ingested, but still preserve LOCAL_CACHE origin if served from cache on failure.
        val liveResult = try {
            provider.fetchHistoricalWeather(location, startDate, endDate)
        } catch (ce: CancellationException) {
            throw ce
        }

        return when (liveResult) {
            is EngineResult.Success -> {
                historicalCache.put(key, liveResult.value)
                EngineResult.Success(
                    WeatherRetrievalEnvelope(
                        data = liveResult.value,
                        deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
                        freshness = liveResult.value.quality
                    )
                )
            }
            is EngineResult.Failure -> {
                if (cachePolicy == CachePolicy.PREFER_FRESH_FALLBACK_CACHE && isEligibleForCacheFallback(liveResult.error)) {
                    val cached = readCachedHistoricalOrNull(key, liveResult.error)
                    if (cached != null) {
                        return EngineResult.Success(cached)
                    }
                }
                EngineResult.Failure(liveResult.error)
            }
        }
    }

    override fun clearCache() {
        currentCache.clear()
        forecastCache.clear()
        historicalCache.clear()
    }

    private fun readCachedCurrentOrFail(
        key: String,
        fallbackTrigger: EngineError?
    ): EngineResult<WeatherRetrievalEnvelope<EnvironmentalObservation>> {
        val cached = readCachedCurrentOrNull(key, fallbackTrigger)
            ?: return EngineResult.Failure(
                EngineError.NetworkUnavailable("No valid cached current observation available.")
            )
        return EngineResult.Success(cached)
    }

    private fun readCachedCurrentOrNull(
        key: String,
        fallbackTrigger: EngineError?
    ): WeatherRetrievalEnvelope<EnvironmentalObservation>? {
        val raw = currentCache.get(key) ?: return null
        val now = clock.instant()
        val age = Duration.between(raw.retrievedAt, now)
        if (age > cacheConfig.maxOfflineFallbackAge) {
            currentCache.remove(key)
            return null
        }
        val updatedQuality = raw.quality.reEvaluatedAt(
            now = now,
            newOrigin = DeliveryOrigin.LOCAL_CACHE,
            isForecast = false,
            policy = freshnessPolicy
        )
        val updatedObs = raw.copy(quality = updatedQuality)
        return WeatherRetrievalEnvelope(
            data = updatedObs,
            deliveryOrigin = DeliveryOrigin.LOCAL_CACHE,
            freshness = updatedQuality,
            fallbackTriggerError = fallbackTrigger
        )
    }

    private fun readCachedForecastOrFail(
        key: String,
        fallbackTrigger: EngineError?
    ): EngineResult<WeatherRetrievalEnvelope<WeatherForecast>> {
        val cached = readCachedForecastOrNull(key, fallbackTrigger)
            ?: return EngineResult.Failure(
                EngineError.NetworkUnavailable("No valid cached forecast available.")
            )
        return EngineResult.Success(cached)
    }

    private fun readCachedForecastOrNull(
        key: String,
        fallbackTrigger: EngineError?
    ): WeatherRetrievalEnvelope<WeatherForecast>? {
        val raw = forecastCache.get(key) ?: return null
        val now = clock.instant()
        val age = Duration.between(raw.issuedOrRetrievedAt, now)
        if (age > cacheConfig.maxOfflineFallbackAge) {
            forecastCache.remove(key)
            return null
        }
        val updatedQuality = raw.quality.reEvaluatedAt(
            now = now,
            newOrigin = DeliveryOrigin.LOCAL_CACHE,
            isForecast = true,
            policy = freshnessPolicy
        )
        val updatedForecast = raw.copy(quality = updatedQuality)
        return WeatherRetrievalEnvelope(
            data = updatedForecast,
            deliveryOrigin = DeliveryOrigin.LOCAL_CACHE,
            freshness = updatedQuality,
            fallbackTriggerError = fallbackTrigger
        )
    }

    private fun readCachedHistoricalOrNull(
        key: String,
        fallbackTrigger: EngineError?
    ): WeatherRetrievalEnvelope<HistoricalWeatherSeries>? {
        val raw = historicalCache.get(key) ?: return null
        val now = clock.instant()
        val updatedQuality = raw.quality.reEvaluatedAt(
            now = now,
            newOrigin = DeliveryOrigin.LOCAL_CACHE,
            isForecast = false,
            isHistoricalArchive = true,
            policy = freshnessPolicy
        )
        val updatedObs = raw.hourlyObservations.map {
            it.copy(
                quality = it.quality.reEvaluatedAt(
                    now = now,
                    newOrigin = DeliveryOrigin.LOCAL_CACHE,
                    isForecast = false,
                    isHistoricalArchive = true,
                    policy = freshnessPolicy
                )
            )
        }
        val updatedSeries = raw.copy(hourlyObservations = updatedObs, quality = updatedQuality)
        return WeatherRetrievalEnvelope(
            data = updatedSeries,
            deliveryOrigin = DeliveryOrigin.LOCAL_CACHE,
            freshness = updatedQuality,
            fallbackTriggerError = fallbackTrigger
        )
    }

    private fun isEligibleForCacheFallback(error: EngineError): Boolean = when (error) {
        is EngineError.NetworkUnavailable -> true
        is EngineError.ProviderError -> error.isTransient
        else -> false
    }

    private class BoundedLruCache<K, V>(private val maxSize: Int) {
        private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxSize
        }

        @Synchronized
        fun get(key: K): V? = map[key]

        @Synchronized
        fun put(key: K, value: V) {
            map[key] = value
        }

        @Synchronized
        fun remove(key: K) {
            map.remove(key)
        }

        @Synchronized
        fun clear() {
            map.clear()
        }
    }

    companion object {
        private const val COMPONENT = "DefaultWeatherRepository"
    }
}
