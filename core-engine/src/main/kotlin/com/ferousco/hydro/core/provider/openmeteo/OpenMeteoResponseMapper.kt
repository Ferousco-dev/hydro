package com.ferousco.hydro.core.provider.openmeteo

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.DataAcquisitionMethod
import com.ferousco.hydro.core.model.DataQualityAndFreshness
import com.ferousco.hydro.core.model.DeliveryOrigin
import com.ferousco.hydro.core.model.EnvironmentalDataSource
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.FreshnessPolicy
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HistoricalWeatherSeries
import com.ferousco.hydro.core.model.HourlyForecastPoint
import com.ferousco.hydro.core.model.IrradianceUnit
import com.ferousco.hydro.core.model.MeasuredQuantity
import com.ferousco.hydro.core.model.ObservationKind
import com.ferousco.hydro.core.model.PercentageUnit
import com.ferousco.hydro.core.model.QualityFlag
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.UnavailableReason
import com.ferousco.hydro.core.model.UvIndexUnit
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.WeatherForecast
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Internal mapper keeping Open-Meteo JSON schemas isolated from domain consumers.
 */
internal class OpenMeteoResponseMapper(
    private val freshnessPolicy: FreshnessPolicy = FreshnessPolicy()
) {

    fun parseErrorReasonOrNull(body: String): String? {
        val obj = SafeJsonCodec.parseObject(body, PROVIDER_ID).getOrNull() ?: return null
        return if (obj.getBoolean("error") == true) {
            obj.getString("reason") ?: "Open-Meteo returned error=true without reason."
        } else {
            null
        }
    }

    fun mapCurrentObservation(
        rawJson: String,
        requestedLocation: GeoLocation,
        retrievedAt: Instant
    ): EngineResult<EnvironmentalObservation> {
        val root = when (val parsed = SafeJsonCodec.parseObject(rawJson, PROVIDER_ID)) {
            is EngineResult.Failure -> return parsed
            is EngineResult.Success -> parsed.value
        }

        if (root.getBoolean("error") == true) {
            return EngineResult.Failure(
                EngineError.ProviderError(
                    providerId = PROVIDER_ID,
                    statusCode = 400,
                    providerReason = root.getString("reason"),
                    isTransient = false,
                    message = root.getString("reason") ?: "Open-Meteo reported an error payload."
                )
            )
        }

        val header = when (val h = parseHeader(root, requestedLocation)) {
            is EngineResult.Failure -> return h
            is EngineResult.Success -> h.value
        }

        val currentObj = root.getObject("current")
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "current", "Missing 'current' object in Open-Meteo response.")
            )
        val unitsObj = root.getObject("current_units")
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "current_units", "Missing 'current_units' object in Open-Meteo response.")
            )

        val timeFormat = unitsObj.getString("time") ?: "iso8601"
        val observedAt = when (val t = parseTimestamp(currentObj["time"], timeFormat, header.utcOffsetSeconds)) {
            is EngineResult.Failure -> return t
            is EngineResult.Success -> t.value
        }

        val source = EnvironmentalDataSource(
            providerId = PROVIDER_ID,
            providerName = EnvironmentalDataSource.OPEN_METEO_PROVIDER_NAME,
            endpointOrDataset = "Weather Forecast API (/v1/forecast current)",
            acquisitionMethod = DataAcquisitionMethod.GRIDDED_MODEL_NOWCAST,
            modelInfo = "best_match (15-min model assimilation)",
            license = EnvironmentalDataSource.OPEN_METEO_CC_BY_LICENSE,
            attribution = EnvironmentalDataSource.OPEN_METEO_ATTRIBUTION,
            resolvedGridLocation = header.effectiveLocation
        )

        val qualityFlags = mutableSetOf(QualityFlag.MODEL_DERIVED_CURRENT_CONDITION)
        if (header.gridOffsetKm > freshnessPolicy.significantGridOffsetKm) {
            qualityFlags.add(QualityFlag.GRID_CELL_OFFSET_SIGNIFICANT)
        }

        val mappedVars = mapSingleObjectVariables(
            valuesObj = currentObj,
            unitsObj = unitsObj,
            validAt = observedAt,
            location = header.effectiveLocation,
            source = source,
            nature = ValueNature.OBSERVED,
            expectUvIndex = true,
            flagsOut = qualityFlags
        ).let {
            when (it) {
                is EngineResult.Failure -> return it
                is EngineResult.Success -> it.value
            }
        }

        if (mappedVars.completenessRatio() < 1.0) {
            qualityFlags.add(QualityFlag.PARTIAL_VARIABLES_MISSING)
        }

        val notes = buildList {
            add("Open-Meteo 'current' conditions derive from 15-minute assimilated weather model data rather than an on-site station thermometer.")
            if (QualityFlag.GRID_CELL_OFFSET_SIGNIFICANT in qualityFlags) {
                add("Model grid center is ${"%.1f".format(java.util.Locale.US, header.gridOffsetKm)} km from requested coordinates.")
            }
        }

        val quality = DataQualityAndFreshness.evaluateCurrentObservation(
            retrievedAt = retrievedAt,
            evaluatedAt = retrievedAt,
            observedAt = observedAt,
            deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
            completenessRatio = mappedVars.completenessRatio(),
            baseFlags = qualityFlags,
            notes = notes,
            policy = freshnessPolicy
        )

        return EngineResult.Success(
            EnvironmentalObservation(
                requestedLocation = requestedLocation,
                effectiveLocation = header.effectiveLocation,
                observedAt = observedAt,
                retrievedAt = retrievedAt,
                timeZone = header.timeZone,
                observationKind = ObservationKind.MODEL_ASSIMILATED_CURRENT,
                variables = mappedVars,
                source = source,
                quality = quality,
                intervalSeconds = currentObj.getInt("interval")
            )
        )
    }

    fun mapHourlyForecast(
        rawJson: String,
        requestedLocation: GeoLocation,
        retrievedAt: Instant,
        maxHours: Int? = null
    ): EngineResult<WeatherForecast> {
        val root = when (val parsed = SafeJsonCodec.parseObject(rawJson, PROVIDER_ID)) {
            is EngineResult.Failure -> return parsed
            is EngineResult.Success -> parsed.value
        }

        if (root.getBoolean("error") == true) {
            return EngineResult.Failure(
                EngineError.ProviderError(
                    providerId = PROVIDER_ID,
                    statusCode = 400,
                    providerReason = root.getString("reason"),
                    isTransient = false,
                    message = root.getString("reason") ?: "Open-Meteo reported an error payload."
                )
            )
        }

        val header = when (val h = parseHeader(root, requestedLocation)) {
            is EngineResult.Failure -> return h
            is EngineResult.Success -> h.value
        }

        val hourlyObj = root.getObject("hourly")
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "hourly", "Missing 'hourly' object in Open-Meteo forecast response.")
            )
        val unitsObj = root.getObject("hourly_units")
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "hourly_units", "Missing 'hourly_units' object in Open-Meteo forecast response.")
            )

        val source = EnvironmentalDataSource(
            providerId = PROVIDER_ID,
            providerName = EnvironmentalDataSource.OPEN_METEO_PROVIDER_NAME,
            endpointOrDataset = "Weather Forecast API (/v1/forecast hourly)",
            acquisitionMethod = DataAcquisitionMethod.NUMERICAL_WEATHER_FORECAST,
            modelInfo = "best_match",
            license = EnvironmentalDataSource.OPEN_METEO_CC_BY_LICENSE,
            attribution = EnvironmentalDataSource.OPEN_METEO_ATTRIBUTION,
            resolvedGridLocation = header.effectiveLocation
        )

        val flags = mutableSetOf<QualityFlag>()
        if (header.gridOffsetKm > freshnessPolicy.significantGridOffsetKm) {
            flags.add(QualityFlag.GRID_CELL_OFFSET_SIGNIFICANT)
        }

        val series = when (
            val mapped = mapHourlySeries(
                hourlyObj = hourlyObj,
                unitsObj = unitsObj,
                header = header,
                source = source,
                nature = ValueNature.FORECAST,
                expectUvIndex = true,
                flagsOut = flags
            )
        ) {
            is EngineResult.Failure -> return mapped
            is EngineResult.Success -> mapped.value
        }

        if (series.isEmpty()) {
            return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "hourly.time", "Hourly forecast contained zero timestamps.")
            )
        }

        val trimmed = series
            .sortedBy { it.first }
            .let { sorted -> if (maxHours != null) sorted.take(maxHours) else sorted }

        val points = trimmed.map { (validAt, vars) ->
            HourlyForecastPoint(
                validAt = validAt,
                localDateTime = validAt.atZone(header.timeZone),
                issuedOrRetrievedAt = retrievedAt,
                location = header.effectiveLocation,
                variables = vars,
                source = source
            )
        }

        val avgCompleteness = points.map { it.variables.completenessRatio(expectedCoreFields = 6) }.average()
        if (avgCompleteness < 1.0) {
            flags.add(QualityFlag.PARTIAL_VARIABLES_MISSING)
        }

        val quality = DataQualityAndFreshness.evaluateForecast(
            retrievedAt = retrievedAt,
            evaluatedAt = retrievedAt,
            anchorTime = points.first().validAt,
            deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
            completenessRatio = avgCompleteness.coerceIn(0.0, 1.0),
            baseFlags = flags,
            notes = buildList {
                if (QualityFlag.GRID_CELL_OFFSET_SIGNIFICANT in flags) {
                    add("Forecast grid center is ${"%.1f".format(java.util.Locale.US, header.gridOffsetKm)} km from requested coordinates.")
                }
            },
            policy = freshnessPolicy
        )

        return EngineResult.Success(
            WeatherForecast(
                requestedLocation = requestedLocation,
                effectiveLocation = header.effectiveLocation,
                timeZone = header.timeZone,
                issuedOrRetrievedAt = retrievedAt,
                hourly = points,
                source = source,
                quality = quality
            )
        )
    }

    fun mapHistoricalWeather(
        rawJson: String,
        requestedLocation: GeoLocation,
        startDate: LocalDate,
        endDate: LocalDate,
        retrievedAt: Instant
    ): EngineResult<HistoricalWeatherSeries> {
        val root = when (val parsed = SafeJsonCodec.parseObject(rawJson, PROVIDER_ID)) {
            is EngineResult.Failure -> return parsed
            is EngineResult.Success -> parsed.value
        }

        if (root.getBoolean("error") == true) {
            return EngineResult.Failure(
                EngineError.ProviderError(
                    providerId = PROVIDER_ID,
                    statusCode = 400,
                    providerReason = root.getString("reason"),
                    isTransient = false,
                    message = root.getString("reason") ?: "Open-Meteo archive reported an error payload."
                )
            )
        }

        val header = when (val h = parseHeader(root, requestedLocation)) {
            is EngineResult.Failure -> return h
            is EngineResult.Success -> h.value
        }

        val hourlyObj = root.getObject("hourly")
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "hourly", "Missing 'hourly' object in Open-Meteo archive response.")
            )
        val unitsObj = root.getObject("hourly_units")
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "hourly_units", "Missing 'hourly_units' object in Open-Meteo archive response.")
            )

        val source = EnvironmentalDataSource(
            providerId = PROVIDER_ID,
            providerName = EnvironmentalDataSource.OPEN_METEO_PROVIDER_NAME,
            endpointOrDataset = "Historical Weather API (/v1/archive)",
            acquisitionMethod = DataAcquisitionMethod.HISTORICAL_REANALYSIS,
            modelInfo = "ERA5 / ERA5-Land / ECMWF IFS reanalysis",
            license = EnvironmentalDataSource.OPEN_METEO_CC_BY_LICENSE,
            attribution = EnvironmentalDataSource.OPEN_METEO_ATTRIBUTION,
            resolvedGridLocation = header.effectiveLocation
        )

        val flags = mutableSetOf(QualityFlag.REANALYSIS_INTERPOLATED)
        if (header.gridOffsetKm > freshnessPolicy.significantGridOffsetKm) {
            flags.add(QualityFlag.GRID_CELL_OFFSET_SIGNIFICANT)
        }

        val series = when (
            val mapped = mapHourlySeries(
                hourlyObj = hourlyObj,
                unitsObj = unitsObj,
                header = header,
                source = source,
                nature = ValueNature.OBSERVED,
                expectUvIndex = false, // /v1/archive does not provide hourly uv_index
                flagsOut = flags
            )
        ) {
            is EngineResult.Failure -> return mapped
            is EngineResult.Success -> mapped.value
        }

        if (series.isEmpty()) {
            return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "hourly.time", "Historical weather response contained zero timestamps.")
            )
        }

        val sorted = series.sortedBy { it.first }
        // Historical archive requests 5 core variables (uv_index is unsupported on /v1/archive)
        val avgCompleteness = sorted.map { it.second.completenessRatio(expectedCoreFields = 5) }.average()
            .coerceIn(0.0, 1.0)
        if (avgCompleteness < 1.0) {
            flags.add(QualityFlag.PARTIAL_VARIABLES_MISSING)
        }

        val overallQuality = DataQualityAndFreshness.evaluateHistoricalArchive(
            retrievedAt = retrievedAt,
            evaluatedAt = retrievedAt,
            referenceDataTime = sorted.first().first,
            deliveryOrigin = DeliveryOrigin.LIVE_NETWORK,
            completenessRatio = avgCompleteness,
            baseFlags = flags,
            notes = listOf(
                "Historical weather records from Open-Meteo /v1/archive are gridded meteorological reanalysis estimates (ERA5 / ECMWF IFS), not point thermometer measurements.",
                "Hourly uv_index is not provided by Open-Meteo Historical Weather API (/v1/archive)."
            )
        )

        val observations = sorted.map { (validAt, vars) ->
            EnvironmentalObservation(
                requestedLocation = requestedLocation,
                effectiveLocation = header.effectiveLocation,
                observedAt = validAt,
                retrievedAt = retrievedAt,
                timeZone = header.timeZone,
                observationKind = ObservationKind.HISTORICAL_REANALYSIS_OBSERVATION,
                variables = vars,
                source = source,
                quality = overallQuality.copy(referenceDataTime = validAt),
                intervalSeconds = 3600
            )
        }

        return EngineResult.Success(
            HistoricalWeatherSeries(
                requestedLocation = requestedLocation,
                effectiveLocation = header.effectiveLocation,
                timeZone = header.timeZone,
                startDate = startDate,
                endDate = endDate,
                retrievedAt = retrievedAt,
                hourlyObservations = observations,
                source = source,
                quality = overallQuality
            )
        )
    }

    private data class ResponseHeader(
        val effectiveLocation: GeoLocation,
        val timeZone: ZoneId,
        val utcOffsetSeconds: Int,
        val gridOffsetKm: Double
    )

    private fun parseHeader(
        root: JsonValue.ObjectValue,
        requestedLocation: GeoLocation
    ): EngineResult<ResponseHeader> {
        val lat = root.getDouble("latitude")
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "latitude", "Missing numeric 'latitude' in Open-Meteo response.")
            )
        val lon = root.getDouble("longitude")
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "longitude", "Missing numeric 'longitude' in Open-Meteo response.")
            )
        val elevation = root.getDouble("elevation")?.takeIf { it in -500.0..10_000.0 }
        val utcOffsetSeconds = root.getInt("utc_offset_seconds") ?: 0
        if (utcOffsetSeconds !in -64_800..64_800) {
            return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "utc_offset_seconds", "Invalid utc_offset_seconds=$utcOffsetSeconds.")
            )
        }

        val tzRaw = root.getString("timezone")
        val timeZone = resolveZoneId(tzRaw, utcOffsetSeconds, requestedLocation.timeZone)

        val effectiveLoc = when (
            val validated = GeoLocation.validated(
                latitude = lat,
                longitude = lon,
                elevationMeters = elevation,
                timeZone = timeZone,
                label = requestedLocation.label
            )
        ) {
            is EngineResult.Failure -> return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "coordinates", validated.error.message)
            )
            is EngineResult.Success -> validated.value
        }

        val offsetKm = requestedLocation.distanceKilometersTo(effectiveLoc)
        return EngineResult.Success(
            ResponseHeader(
                effectiveLocation = effectiveLoc,
                timeZone = timeZone,
                utcOffsetSeconds = utcOffsetSeconds,
                gridOffsetKm = offsetKm
            )
        )
    }

    private fun resolveZoneId(rawTz: String?, utcOffsetSeconds: Int, fallback: ZoneId?): ZoneId {
        if (!rawTz.isNullOrBlank()) {
            try {
                return ZoneId.of(rawTz)
            } catch (_: Exception) {
                // Fall back to explicit UTC offset if provider returned a custom label like "GMT+1"
            }
        }
        if (fallback != null) return fallback
        return ZoneOffset.ofTotalSeconds(utcOffsetSeconds)
    }

    private fun parseTimestamp(
        value: JsonValue?,
        timeFormat: String,
        utcOffsetSeconds: Int
    ): EngineResult<Instant> {
        if (value == null || value is JsonValue.NullValue) {
            return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "time", "Missing timestamp value.")
            )
        }
        return when {
            timeFormat.equals("unixtime", ignoreCase = true) || value is JsonValue.NumberValue -> {
                val epochSeconds = (value as? JsonValue.NumberValue)?.toLongExactOrNull()
                    ?: return EngineResult.Failure(
                        EngineError.InvalidResponse(PROVIDER_ID, "time", "Expected integer unixtime seconds, got $value.")
                    )
                EngineResult.Success(Instant.ofEpochSecond(epochSeconds))
            }
            value is JsonValue.StringValue -> parseIsoTimestamp(value.value, utcOffsetSeconds)
            else -> EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "time", "Unsupported timestamp JSON token: $value.")
            )
        }
    }

    private fun parseIsoTimestamp(raw: String, utcOffsetSeconds: Int): EngineResult<Instant> {
        return try {
            when {
                raw.endsWith("Z", ignoreCase = true) -> EngineResult.Success(Instant.parse(raw))
                raw.length > 10 && (raw.indexOf('+', 10) > 0 || raw.indexOf('-', 10) > 0) ->
                    EngineResult.Success(OffsetDateTime.parse(raw).toInstant())
                else -> {
                    // Open-Meteo iso8601 mode formats local timestamps using fixed utc_offset_seconds
                    val ldt = LocalDateTime.parse(raw)
                    val offset = ZoneOffset.ofTotalSeconds(utcOffsetSeconds)
                    EngineResult.Success(ldt.toInstant(offset))
                }
            }
        } catch (ex: Exception) {
            EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "time", "Invalid ISO-8601 timestamp '$raw'.")
            )
        }
    }

    private fun mapSingleObjectVariables(
        valuesObj: JsonValue.ObjectValue,
        unitsObj: JsonValue.ObjectValue,
        validAt: Instant,
        location: GeoLocation,
        source: EnvironmentalDataSource,
        nature: ValueNature,
        expectUvIndex: Boolean,
        flagsOut: MutableSet<QualityFlag>
    ): EngineResult<EnvironmentalSnapshotVariables> {
        val tempUnitResult = resolveTemperatureUnit(unitsObj, "temperature_2m", required = "temperature_2m" in valuesObj.entries)
        val tempUnit = when (tempUnitResult) {
            is EngineResult.Failure -> return tempUnitResult
            is EngineResult.Success -> tempUnitResult.value
        }

        val feelsUnitResult = resolveTemperatureUnit(unitsObj, "apparent_temperature", required = "apparent_temperature" in valuesObj.entries)
        val feelsUnit = when (feelsUnitResult) {
            is EngineResult.Failure -> return feelsUnitResult
            is EngineResult.Success -> feelsUnitResult.value
        }

        val windUnitResult = resolveSpeedUnit(unitsObj, "wind_speed_10m", required = "wind_speed_10m" in valuesObj.entries)
        val windUnit = when (windUnitResult) {
            is EngineResult.Failure -> return windUnitResult
            is EngineResult.Success -> windUnitResult.value
        }

        val rhUnitResult = resolveHumidityUnit(unitsObj, "relative_humidity_2m", required = "relative_humidity_2m" in valuesObj.entries)
        if (rhUnitResult is EngineResult.Failure) return rhUnitResult

        val solarUnitResult = resolveIrradianceUnit(unitsObj, "shortwave_radiation", required = "shortwave_radiation" in valuesObj.entries)
        if (solarUnitResult is EngineResult.Failure) return solarUnitResult

        val airTemp = extractNormalizedAirTemperature(valuesObj["temperature_2m"], tempUnit, validAt, location, source, nature, flagsOut)
        val humidity = extractHumidity(valuesObj["relative_humidity_2m"], validAt, location, source, nature, flagsOut)
        val wind = extractNormalizedWindSpeed(valuesObj["wind_speed_10m"], windUnit, validAt, location, source, nature, flagsOut)
        val feelsLike = extractNormalizedFeelsLike(valuesObj["apparent_temperature"], feelsUnit, validAt, location, source, nature, flagsOut)
        val uv = if (expectUvIndex) {
            extractUvIndex(valuesObj["uv_index"], validAt, location, source, nature, flagsOut)
        } else {
            FieldAvailability.Unavailable(
                UnavailableReason.NOT_PROVIDED_BY_SOURCE,
                "Open-Meteo Historical Weather API (/v1/archive) does not provide hourly uv_index."
            )
        }
        val solar = extractSolarRadiation(valuesObj["shortwave_radiation"], validAt, location, source, nature, flagsOut)
        val isDay = valuesObj.getInt("is_day")?.let {
            when (it) {
                1 -> true
                0 -> false
                else -> null
            }
        }
        val weatherCode = valuesObj.getInt("weather_code")

        return EngineResult.Success(
            EnvironmentalSnapshotVariables(
                airTemperature = airTemp,
                relativeHumidity = humidity,
                windSpeed = wind,
                feelsLikeTemperature = feelsLike,
                uvIndex = uv,
                solarRadiation = solar,
                isDaylight = isDay,
                wmoWeatherCode = weatherCode
            )
        )
    }

    private fun mapHourlySeries(
        hourlyObj: JsonValue.ObjectValue,
        unitsObj: JsonValue.ObjectValue,
        header: ResponseHeader,
        source: EnvironmentalDataSource,
        nature: ValueNature,
        expectUvIndex: Boolean,
        flagsOut: MutableSet<QualityFlag>
    ): EngineResult<List<Pair<Instant, EnvironmentalSnapshotVariables>>> {
        val timeArray = hourlyObj.getArray("time")?.items
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "hourly.time", "Missing 'hourly.time' array.")
            )
        val count = timeArray.size
        val timeFormat = unitsObj.getString("time") ?: "iso8601"

        fun optionalArray(field: String): EngineResult<List<JsonValue>?> {
            val raw = hourlyObj[field] ?: return EngineResult.Success(null)
            if (raw is JsonValue.NullValue) return EngineResult.Success(null)
            val arr = raw as? JsonValue.ArrayValue
                ?: return EngineResult.Failure(
                    EngineError.InvalidResponse(PROVIDER_ID, "hourly.$field", "Expected array for 'hourly.$field'.")
                )
            if (arr.items.size != count) {
                return EngineResult.Failure(
                    EngineError.InvalidResponse(
                        PROVIDER_ID,
                        "hourly.$field",
                        "Array length mismatch for 'hourly.$field': expected $count, got ${arr.items.size}."
                    )
                )
            }
            return EngineResult.Success(arr.items)
        }

        val temps = when (val r = optionalArray("temperature_2m")) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val humidities = when (val r = optionalArray("relative_humidity_2m")) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val winds = when (val r = optionalArray("wind_speed_10m")) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val feelsLikes = when (val r = optionalArray("apparent_temperature")) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val uvs = when (val r = optionalArray("uv_index")) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val solars = when (val r = optionalArray("shortwave_radiation")) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val isDays = when (val r = optionalArray("is_day")) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }
        val weatherCodes = when (val r = optionalArray("weather_code")) {
            is EngineResult.Failure -> return r
            is EngineResult.Success -> r.value
        }

        val tempUnit = when (val u = resolveTemperatureUnit(unitsObj, "temperature_2m", required = temps != null)) {
            is EngineResult.Failure -> return u
            is EngineResult.Success -> u.value
        }
        val feelsUnit = when (val u = resolveTemperatureUnit(unitsObj, "apparent_temperature", required = feelsLikes != null)) {
            is EngineResult.Failure -> return u
            is EngineResult.Success -> u.value
        }
        val windUnit = when (val u = resolveSpeedUnit(unitsObj, "wind_speed_10m", required = winds != null)) {
            is EngineResult.Failure -> return u
            is EngineResult.Success -> u.value
        }
        val rhUnitCheck = resolveHumidityUnit(unitsObj, "relative_humidity_2m", required = humidities != null)
        if (rhUnitCheck is EngineResult.Failure) return rhUnitCheck
        val solarUnitCheck = resolveIrradianceUnit(unitsObj, "shortwave_radiation", required = solars != null)
        if (solarUnitCheck is EngineResult.Failure) return solarUnitCheck

        val results = ArrayList<Pair<Instant, EnvironmentalSnapshotVariables>>(count)
        for (i in 0 until count) {
            val validAt = when (val ts = parseTimestamp(timeArray[i], timeFormat, header.utcOffsetSeconds)) {
                is EngineResult.Failure -> return ts
                is EngineResult.Success -> ts.value
            }

            val airTemp = if (temps == null) {
                FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
            } else {
                extractNormalizedAirTemperature(temps[i], tempUnit, validAt, header.effectiveLocation, source, nature, flagsOut)
            }

            val rh = if (humidities == null) {
                FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
            } else {
                extractHumidity(humidities[i], validAt, header.effectiveLocation, source, nature, flagsOut)
            }

            val wind = if (winds == null) {
                FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
            } else {
                extractNormalizedWindSpeed(winds[i], windUnit, validAt, header.effectiveLocation, source, nature, flagsOut)
            }

            val feels = if (feelsLikes == null) {
                FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
            } else {
                extractNormalizedFeelsLike(feelsLikes[i], feelsUnit, validAt, header.effectiveLocation, source, nature, flagsOut)
            }

            val uv = when {
                !expectUvIndex -> FieldAvailability.Unavailable(
                    UnavailableReason.NOT_PROVIDED_BY_SOURCE,
                    "Open-Meteo Historical Weather API (/v1/archive) does not provide hourly uv_index."
                )
                uvs == null -> FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
                else -> extractUvIndex(uvs[i], validAt, header.effectiveLocation, source, nature, flagsOut)
            }

            val solar = if (solars == null) {
                FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
            } else {
                extractSolarRadiation(solars[i], validAt, header.effectiveLocation, source, nature, flagsOut)
            }

            val isDay = (isDays?.get(i) as? JsonValue.NumberValue)?.toLongExactOrNull()?.let {
                when (it) {
                    1L -> true
                    0L -> false
                    else -> null
                }
            }
            val code = (weatherCodes?.get(i) as? JsonValue.NumberValue)?.toLongExactOrNull()?.toInt()

            results.add(
                validAt to EnvironmentalSnapshotVariables(
                    airTemperature = airTemp,
                    relativeHumidity = rh,
                    windSpeed = wind,
                    feelsLikeTemperature = feels,
                    uvIndex = uv,
                    solarRadiation = solar,
                    isDaylight = isDay,
                    wmoWeatherCode = code
                )
            )
        }
        return EngineResult.Success(results)
    }

    private fun resolveTemperatureUnit(
        unitsObj: JsonValue.ObjectValue,
        field: String,
        required: Boolean
    ): EngineResult<TemperatureUnit> {
        val raw = unitsObj.getString(field)
        if (raw == null) {
            return if (required) {
                EngineResult.Failure(
                    EngineError.InvalidResponse(PROVIDER_ID, "units.$field", "Missing unit declaration for '$field'.")
                )
            } else {
                EngineResult.Success(TemperatureUnit.CELSIUS)
            }
        }
        val parsed = TemperatureUnit.fromProviderSymbol(raw)
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "units.$field", "Unsupported temperature unit '$raw' for '$field'.")
            )
        return EngineResult.Success(parsed)
    }

    private fun resolveSpeedUnit(
        unitsObj: JsonValue.ObjectValue,
        field: String,
        required: Boolean
    ): EngineResult<SpeedUnit> {
        val raw = unitsObj.getString(field)
        if (raw == null) {
            return if (required) {
                EngineResult.Failure(
                    EngineError.InvalidResponse(PROVIDER_ID, "units.$field", "Missing unit declaration for '$field'.")
                )
            } else {
                EngineResult.Success(SpeedUnit.METERS_PER_SECOND)
            }
        }
        val parsed = SpeedUnit.fromProviderSymbol(raw)
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "units.$field", "Unsupported wind speed unit '$raw' for '$field'.")
            )
        return EngineResult.Success(parsed)
    }

    private fun resolveHumidityUnit(
        unitsObj: JsonValue.ObjectValue,
        field: String,
        required: Boolean
    ): EngineResult<PercentageUnit> {
        val raw = unitsObj.getString(field)
        if (raw == null) {
            return if (required) {
                EngineResult.Failure(
                    EngineError.InvalidResponse(PROVIDER_ID, "units.$field", "Missing unit declaration for '$field'.")
                )
            } else {
                EngineResult.Success(PercentageUnit.PERCENT)
            }
        }
        val parsed = PercentageUnit.fromProviderSymbol(raw)
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "units.$field", "Unsupported humidity unit '$raw' for '$field'.")
            )
        return EngineResult.Success(parsed)
    }

    private fun resolveIrradianceUnit(
        unitsObj: JsonValue.ObjectValue,
        field: String,
        required: Boolean
    ): EngineResult<IrradianceUnit> {
        val raw = unitsObj.getString(field)
        if (raw == null) {
            return if (required) {
                EngineResult.Failure(
                    EngineError.InvalidResponse(PROVIDER_ID, "units.$field", "Missing unit declaration for '$field'.")
                )
            } else {
                EngineResult.Success(IrradianceUnit.WATTS_PER_SQUARE_METER)
            }
        }
        val parsed = IrradianceUnit.fromProviderSymbol(raw)
            ?: return EngineResult.Failure(
                EngineError.InvalidResponse(PROVIDER_ID, "units.$field", "Unsupported irradiance unit '$raw' for '$field'.")
            )
        return EngineResult.Success(parsed)
    }

    private fun extractNormalizedAirTemperature(
        token: JsonValue?,
        declaredUnit: TemperatureUnit,
        validAt: Instant,
        location: GeoLocation,
        source: EnvironmentalDataSource,
        nature: ValueNature,
        flagsOut: MutableSet<QualityFlag>
    ): FieldAvailability<TemperatureUnit> {
        if (token == null) return FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
        if (token is JsonValue.NullValue) return FieldAvailability.Unavailable(UnavailableReason.NULL_IN_PROVIDER_RESPONSE)
        val raw = (token as? JsonValue.NumberValue)?.toDoubleOrNull() ?: run {
            flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
            return FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, "Non-numeric temperature_2m.")
        }
        return when (val v = TemperatureUnit.validateAmbientAir(raw, declaredUnit, "temperature_2m")) {
            is EngineResult.Failure -> {
                flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
                FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, v.error.message)
            }
            is EngineResult.Success -> {
                val normalizedCelsius = declaredUnit.convert(v.value, TemperatureUnit.CELSIUS)
                FieldAvailability.Available(
                    MeasuredQuantity(
                        value = normalizedCelsius,
                        unit = TemperatureUnit.CELSIUS,
                        validAt = validAt,
                        location = location,
                        source = source,
                        nature = nature
                    )
                )
            }
        }
    }

    private fun extractNormalizedFeelsLike(
        token: JsonValue?,
        declaredUnit: TemperatureUnit,
        validAt: Instant,
        location: GeoLocation,
        source: EnvironmentalDataSource,
        nature: ValueNature,
        flagsOut: MutableSet<QualityFlag>
    ): FieldAvailability<TemperatureUnit> {
        if (token == null) return FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
        if (token is JsonValue.NullValue) return FieldAvailability.Unavailable(UnavailableReason.NULL_IN_PROVIDER_RESPONSE)
        val raw = (token as? JsonValue.NumberValue)?.toDoubleOrNull() ?: run {
            flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
            return FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, "Non-numeric apparent_temperature.")
        }
        return when (val v = TemperatureUnit.validateThermalIndex(raw, declaredUnit, "apparent_temperature")) {
            is EngineResult.Failure -> {
                flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
                FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, v.error.message)
            }
            is EngineResult.Success -> {
                val normalizedCelsius = declaredUnit.convert(v.value, TemperatureUnit.CELSIUS)
                FieldAvailability.Available(
                    MeasuredQuantity(
                        value = normalizedCelsius,
                        unit = TemperatureUnit.CELSIUS,
                        validAt = validAt,
                        location = location,
                        source = source,
                        nature = nature
                    )
                )
            }
        }
    }

    private fun extractHumidity(
        token: JsonValue?,
        validAt: Instant,
        location: GeoLocation,
        source: EnvironmentalDataSource,
        nature: ValueNature,
        flagsOut: MutableSet<QualityFlag>
    ): FieldAvailability<PercentageUnit> {
        if (token == null) return FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
        if (token is JsonValue.NullValue) return FieldAvailability.Unavailable(UnavailableReason.NULL_IN_PROVIDER_RESPONSE)
        val raw = (token as? JsonValue.NumberValue)?.toDoubleOrNull() ?: run {
            flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
            return FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, "Non-numeric relative_humidity_2m.")
        }
        return when (val v = PercentageUnit.validateRelativeHumidity(raw, "relative_humidity_2m")) {
            is EngineResult.Failure -> {
                flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
                FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, v.error.message)
            }
            is EngineResult.Success -> FieldAvailability.Available(
                MeasuredQuantity(
                    value = v.value,
                    unit = PercentageUnit.PERCENT,
                    validAt = validAt,
                    location = location,
                    source = source,
                    nature = nature
                )
            )
        }
    }

    private fun extractNormalizedWindSpeed(
        token: JsonValue?,
        declaredUnit: SpeedUnit,
        validAt: Instant,
        location: GeoLocation,
        source: EnvironmentalDataSource,
        nature: ValueNature,
        flagsOut: MutableSet<QualityFlag>
    ): FieldAvailability<SpeedUnit> {
        if (token == null) return FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
        if (token is JsonValue.NullValue) return FieldAvailability.Unavailable(UnavailableReason.NULL_IN_PROVIDER_RESPONSE)
        val raw = (token as? JsonValue.NumberValue)?.toDoubleOrNull() ?: run {
            flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
            return FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, "Non-numeric wind_speed_10m.")
        }
        return when (val v = SpeedUnit.validateWindSpeed(raw, declaredUnit, "wind_speed_10m")) {
            is EngineResult.Failure -> {
                flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
                FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, v.error.message)
            }
            is EngineResult.Success -> {
                val normalizedMs = declaredUnit.convert(v.value, SpeedUnit.METERS_PER_SECOND)
                FieldAvailability.Available(
                    MeasuredQuantity(
                        value = normalizedMs,
                        unit = SpeedUnit.METERS_PER_SECOND,
                        validAt = validAt,
                        location = location,
                        source = source,
                        nature = nature
                    )
                )
            }
        }
    }

    private fun extractUvIndex(
        token: JsonValue?,
        validAt: Instant,
        location: GeoLocation,
        source: EnvironmentalDataSource,
        nature: ValueNature,
        flagsOut: MutableSet<QualityFlag>
    ): FieldAvailability<UvIndexUnit> {
        if (token == null) return FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
        if (token is JsonValue.NullValue) return FieldAvailability.Unavailable(UnavailableReason.NULL_IN_PROVIDER_RESPONSE)
        val raw = (token as? JsonValue.NumberValue)?.toDoubleOrNull() ?: run {
            flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
            return FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, "Non-numeric uv_index.")
        }
        return when (val v = UvIndexUnit.validateUvIndex(raw, "uv_index")) {
            is EngineResult.Failure -> {
                flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
                FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, v.error.message)
            }
            is EngineResult.Success -> FieldAvailability.Available(
                MeasuredQuantity(
                    value = v.value,
                    unit = UvIndexUnit.WHO_INDEX,
                    validAt = validAt,
                    location = location,
                    source = source,
                    nature = nature
                )
            )
        }
    }

    private fun extractSolarRadiation(
        token: JsonValue?,
        validAt: Instant,
        location: GeoLocation,
        source: EnvironmentalDataSource,
        nature: ValueNature,
        flagsOut: MutableSet<QualityFlag>
    ): FieldAvailability<IrradianceUnit> {
        if (token == null) return FieldAvailability.Unavailable(UnavailableReason.NOT_PROVIDED_BY_SOURCE)
        if (token is JsonValue.NullValue) return FieldAvailability.Unavailable(UnavailableReason.NULL_IN_PROVIDER_RESPONSE)
        val raw = (token as? JsonValue.NumberValue)?.toDoubleOrNull() ?: run {
            flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
            return FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, "Non-numeric shortwave_radiation.")
        }
        return when (val v = IrradianceUnit.validateSolarIrradiance(raw, "shortwave_radiation")) {
            is EngineResult.Failure -> {
                flagsOut.add(QualityFlag.INVALID_PROVIDER_VALUES_REJECTED)
                FieldAvailability.Unavailable(UnavailableReason.FAILED_VALIDATION, v.error.message)
            }
            is EngineResult.Success -> FieldAvailability.Available(
                MeasuredQuantity(
                    value = v.value,
                    unit = IrradianceUnit.WATTS_PER_SQUARE_METER,
                    validAt = validAt,
                    location = location,
                    source = source,
                    nature = nature
                )
            )
        }
    }

    companion object {
        private const val PROVIDER_ID = EnvironmentalDataSource.OPEN_METEO_PROVIDER_ID
    }
}
