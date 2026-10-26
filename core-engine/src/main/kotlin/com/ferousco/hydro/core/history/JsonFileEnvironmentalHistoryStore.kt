package com.ferousco.hydro.core.history

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.json.JsonValue
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.AssessmentCompletionStatus
import com.ferousco.hydro.core.model.DataAcquisitionMethod
import com.ferousco.hydro.core.model.EnvironmentalDataSource
import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HistoricalRecordDataType
import com.ferousco.hydro.core.model.IrradianceUnit
import com.ferousco.hydro.core.model.MeasuredQuantity
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.model.ObservationKind
import com.ferousco.hydro.core.model.PercentageUnit
import com.ferousco.hydro.core.model.PhysicalUnit
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.model.StoredHeatAssessmentRecord
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.UnavailableReason
import com.ferousco.hydro.core.model.UvIndexUnit
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.WhoUvCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId

/**
 * Durable JVM file-backed [EnvironmentalHistoryStore] using atomic temp-file replacement.
 * Preserves full provenance, units, unavailability reasons, and assessment metadata on disk,
 * and surfaces corrupted/unwritable storage as [EngineError.PersistenceFailure].
 */
class JsonFileEnvironmentalHistoryStore(
    private val storageFile: Path
) : EnvironmentalHistoryStore {

    private val mutex = Mutex()

    override suspend fun upsertEnvironmentalRecord(record: StoredEnvironmentalRecord): EngineResult<Unit> =
        mutex.withLock {
            val state = when (val loaded = loadStateUnlocked()) {
                is EngineResult.Failure -> return@withLock loaded
                is EngineResult.Success -> loaded.value
            }
            state.envRecords[record.id] = record
            saveStateUnlocked(state, "upsertEnvironmentalRecord")
        }

    override suspend fun upsertAssessmentRecord(record: StoredHeatAssessmentRecord): EngineResult<Unit> =
        mutex.withLock {
            val state = when (val loaded = loadStateUnlocked()) {
                is EngineResult.Failure -> return@withLock loaded
                is EngineResult.Success -> loaded.value
            }
            state.assessmentRecords[record.id] = record
            saveStateUnlocked(state, "upsertAssessmentRecord")
        }

    override suspend fun getEnvironmentalRecordById(id: String): EngineResult<StoredEnvironmentalRecord?> =
        mutex.withLock {
            when (val loaded = loadStateUnlocked()) {
                is EngineResult.Failure -> loaded
                is EngineResult.Success -> EngineResult.Success(loaded.value.envRecords[id])
            }
        }

    override suspend fun getAssessmentRecordById(id: String): EngineResult<StoredHeatAssessmentRecord?> =
        mutex.withLock {
            when (val loaded = loadStateUnlocked()) {
                is EngineResult.Failure -> loaded
                is EngineResult.Success -> EngineResult.Success(loaded.value.assessmentRecords[id])
            }
        }

    override suspend fun getAssessmentForEnvironmentalRecord(
        environmentalRecordId: String
    ): EngineResult<StoredHeatAssessmentRecord?> = mutex.withLock {
        when (val loaded = loadStateUnlocked()) {
            is EngineResult.Failure -> loaded
            is EngineResult.Success -> {
                val match = loaded.value.assessmentRecords.values
                    .filter { it.linkedEnvironmentalRecordId == environmentalRecordId }
                    .maxByOrNull { it.assessedAt }
                EngineResult.Success(match)
            }
        }
    }

    override suspend fun queryEnvironmentalRecords(
        dataTypes: Set<HistoricalRecordDataType>,
        validFromInclusive: Instant?,
        validToInclusive: Instant?,
        limit: Int
    ): EngineResult<List<StoredEnvironmentalRecord>> = mutex.withLock {
        when (val loaded = loadStateUnlocked()) {
            is EngineResult.Failure -> loaded
            is EngineResult.Success -> {
                val items = loaded.value.envRecords.values
                    .asSequence()
                    .filter { it.dataType in dataTypes }
                    .filter { validFromInclusive == null || !it.validAt.isBefore(validFromInclusive) }
                    .filter { validToInclusive == null || !it.validAt.isAfter(validToInclusive) }
                    .sortedWith(compareByDescending<StoredEnvironmentalRecord> { it.validAt }.thenByDescending { it.retrievedAt })
                    .take(limit.coerceAtLeast(0))
                    .toList()
                EngineResult.Success(items)
            }
        }
    }

    override suspend fun queryRecentAssessments(
        dataTypes: Set<HistoricalRecordDataType>,
        limit: Int
    ): EngineResult<List<StoredHeatAssessmentRecord>> = mutex.withLock {
        when (val loaded = loadStateUnlocked()) {
            is EngineResult.Failure -> loaded
            is EngineResult.Success -> {
                val items = loaded.value.assessmentRecords.values
                    .asSequence()
                    .filter { it.sourceDataType in dataTypes }
                    .sortedWith(compareByDescending<StoredHeatAssessmentRecord> { it.validAt }.thenByDescending { it.assessedAt })
                    .take(limit.coerceAtLeast(0))
                    .toList()
                EngineResult.Success(items)
            }
        }
    }

    override suspend fun listAllEnvironmentalRecords(): EngineResult<List<StoredEnvironmentalRecord>> =
        mutex.withLock {
            when (val loaded = loadStateUnlocked()) {
                is EngineResult.Failure -> loaded
                is EngineResult.Success -> EngineResult.Success(loaded.value.envRecords.values.toList())
            }
        }

    override suspend fun listAllAssessmentRecords(): EngineResult<List<StoredHeatAssessmentRecord>> =
        mutex.withLock {
            when (val loaded = loadStateUnlocked()) {
                is EngineResult.Failure -> loaded
                is EngineResult.Success -> EngineResult.Success(loaded.value.assessmentRecords.values.toList())
            }
        }

    override suspend fun deleteEnvironmentalRecordById(id: String): EngineResult<Boolean> =
        mutex.withLock {
            val state = when (val loaded = loadStateUnlocked()) {
                is EngineResult.Failure -> return@withLock loaded
                is EngineResult.Success -> loaded.value
            }
            val removed = state.envRecords.remove(id) != null
            if (!removed) return@withLock EngineResult.Success(false)
            when (val saved = saveStateUnlocked(state, "deleteEnvironmentalRecordById")) {
                is EngineResult.Failure -> saved
                is EngineResult.Success -> EngineResult.Success(true)
            }
        }

    override suspend fun deleteAssessmentRecordById(id: String): EngineResult<Boolean> =
        mutex.withLock {
            val state = when (val loaded = loadStateUnlocked()) {
                is EngineResult.Failure -> return@withLock loaded
                is EngineResult.Success -> loaded.value
            }
            val removed = state.assessmentRecords.remove(id) != null
            if (!removed) return@withLock EngineResult.Success(false)
            when (val saved = saveStateUnlocked(state, "deleteAssessmentRecordById")) {
                is EngineResult.Failure -> saved
                is EngineResult.Success -> EngineResult.Success(true)
            }
        }

    override suspend fun deleteEnvironmentalRecordsByIds(ids: Set<String>): EngineResult<Int> =
        mutex.withLock {
            val state = when (val loaded = loadStateUnlocked()) {
                is EngineResult.Failure -> return@withLock loaded
                is EngineResult.Success -> loaded.value
            }
            var count = 0
            for (id in ids) {
                if (state.envRecords.remove(id) != null) count++
            }
            if (count == 0) return@withLock EngineResult.Success(0)
            when (val saved = saveStateUnlocked(state, "deleteEnvironmentalRecordsByIds")) {
                is EngineResult.Failure -> saved
                is EngineResult.Success -> EngineResult.Success(count)
            }
        }

    override suspend fun deleteAssessmentRecordsByIds(ids: Set<String>): EngineResult<Int> =
        mutex.withLock {
            val state = when (val loaded = loadStateUnlocked()) {
                is EngineResult.Failure -> return@withLock loaded
                is EngineResult.Success -> loaded.value
            }
            var count = 0
            for (id in ids) {
                if (state.assessmentRecords.remove(id) != null) count++
            }
            if (count == 0) return@withLock EngineResult.Success(0)
            when (val saved = saveStateUnlocked(state, "deleteAssessmentRecordsByIds")) {
                is EngineResult.Failure -> saved
                is EngineResult.Success -> EngineResult.Success(count)
            }
        }

    override suspend fun deleteAll(): EngineResult<Unit> = mutex.withLock {
        try {
            Files.deleteIfExists(storageFile)
            EngineResult.Success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            EngineResult.Failure(
                EngineError.PersistenceFailure(
                    operation = "deleteAll",
                    message = "Failed to delete local history file.",
                    causeSummary = ex::class.simpleName
                )
            )
        }
    }

    private class DiskState(
        val envRecords: LinkedHashMap<String, StoredEnvironmentalRecord> = LinkedHashMap(),
        val assessmentRecords: LinkedHashMap<String, StoredHeatAssessmentRecord> = LinkedHashMap()
    )

    private fun loadStateUnlocked(): EngineResult<DiskState> {
        if (!Files.exists(storageFile)) {
            return EngineResult.Success(DiskState())
        }
        return try {
            if (Files.isDirectory(storageFile)) {
                return EngineResult.Failure(
                    EngineError.PersistenceFailure(
                        operation = "loadState",
                        message = "Configured storage path points to a directory rather than a file."
                    )
                )
            }
            val raw = Files.readString(storageFile, StandardCharsets.UTF_8)
            if (raw.isBlank()) return EngineResult.Success(DiskState())
            val root = when (val parsed = SafeJsonCodec.parseObject(raw, "local_history_store", maxChars = 16_777_216)) {
                is EngineResult.Failure -> return EngineResult.Failure(
                    EngineError.PersistenceFailure(
                        operation = "loadState",
                        message = "Corrupted JSON in local history store: ${parsed.error.message}"
                    )
                )
                is EngineResult.Success -> parsed.value
            }
            val state = DiskState()
            val envArr = root.getArray("environmental_records")?.items ?: emptyList()
            for (item in envArr) {
                val obj = item as? JsonValue.ObjectValue
                    ?: throw IllegalArgumentException("Invalid environmental_record entry.")
                val rec = decodeEnvRecord(obj)
                state.envRecords[rec.id] = rec
            }
            val assessArr = root.getArray("assessment_records")?.items ?: emptyList()
            for (item in assessArr) {
                val obj = item as? JsonValue.ObjectValue
                    ?: throw IllegalArgumentException("Invalid assessment_record entry.")
                val rec = decodeAssessmentRecord(obj)
                state.assessmentRecords[rec.id] = rec
            }
            EngineResult.Success(state)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            EngineResult.Failure(
                EngineError.PersistenceFailure(
                    operation = "loadState",
                    message = "Failed to load local history records: ${ex.message}",
                    causeSummary = ex::class.simpleName
                )
            )
        }
    }

    private fun saveStateUnlocked(state: DiskState, operation: String): EngineResult<Unit> {
        return try {
            val parent = storageFile.parent
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent)
            }
            val root = JsonValue.ObjectValue(
                linkedMapOf(
                    "schema_version" to SafeJsonCodec.number(1),
                    "environmental_records" to JsonValue.ArrayValue(state.envRecords.values.map(::encodeEnvRecord)),
                    "assessment_records" to JsonValue.ArrayValue(state.assessmentRecords.values.map(::encodeAssessmentRecord))
                )
            )
            val serialized = SafeJsonCodec.stringify(root)
            val tmpFile = storageFile.resolveSibling("${storageFile.fileName}.tmp")
            Files.writeString(tmpFile, serialized, StandardCharsets.UTF_8)
            Files.move(tmpFile, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            EngineResult.Success(Unit)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            return try {
                val tmpFile = storageFile.resolveSibling("${storageFile.fileName}.tmp")
                Files.move(tmpFile, storageFile, StandardCopyOption.REPLACE_EXISTING)
                EngineResult.Success(Unit)
            } catch (ex: Exception) {
                EngineResult.Failure(
                    EngineError.PersistenceFailure(
                        operation = operation,
                        message = "Failed to persist history file: ${ex.message}",
                        causeSummary = ex::class.simpleName
                    )
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            EngineResult.Failure(
                EngineError.PersistenceFailure(
                    operation = operation,
                    message = "Failed to persist local history state: ${ex.message}",
                    causeSummary = ex::class.simpleName
                )
            )
        }
    }

    private fun encodeLocation(loc: GeoLocation): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf<String, JsonValue>().apply {
            put("latitude", SafeJsonCodec.number(loc.latitude))
            put("longitude", SafeJsonCodec.number(loc.longitude))
            loc.elevationMeters?.let { put("elevation_m", SafeJsonCodec.number(it)) }
            loc.timeZone?.let { put("timezone", JsonValue.StringValue(it.id)) }
            loc.label?.let { put("label", JsonValue.StringValue(it)) }
        }
    )

    private fun decodeLocation(obj: JsonValue.ObjectValue): GeoLocation {
        val lat = obj.getDouble("latitude") ?: throw IllegalArgumentException("Missing latitude")
        val lon = obj.getDouble("longitude") ?: throw IllegalArgumentException("Missing longitude")
        val elev = obj.getDouble("elevation_m")
        val tz = obj.getString("timezone")?.let { ZoneId.of(it) }
        val label = obj.getString("label")
        return GeoLocation(lat, lon, elev, tz, label)
    }

    private fun encodeSource(src: EnvironmentalDataSource): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf<String, JsonValue>().apply {
            put("provider_id", JsonValue.StringValue(src.providerId))
            put("provider_name", JsonValue.StringValue(src.providerName))
            put("endpoint", JsonValue.StringValue(src.endpointOrDataset))
            put("acquisition_method", JsonValue.StringValue(src.acquisitionMethod.name))
            src.modelInfo?.let { put("model_info", JsonValue.StringValue(it)) }
            put("license", JsonValue.StringValue(src.license))
            put("attribution", JsonValue.StringValue(src.attribution))
            src.resolvedGridLocation?.let { put("grid_location", encodeLocation(it)) }
        }
    )

    private fun decodeSource(obj: JsonValue.ObjectValue): EnvironmentalDataSource {
        return EnvironmentalDataSource(
            providerId = obj.getString("provider_id")!!,
            providerName = obj.getString("provider_name")!!,
            endpointOrDataset = obj.getString("endpoint")!!,
            acquisitionMethod = DataAcquisitionMethod.valueOf(obj.getString("acquisition_method")!!),
            modelInfo = obj.getString("model_info"),
            license = obj.getString("license")!!,
            attribution = obj.getString("attribution")!!,
            resolvedGridLocation = obj.getObject("grid_location")?.let(::decodeLocation)
        )
    }

    private fun <U : PhysicalUnit> encodeField(
        field: FieldAvailability<U>,
        unitNameSelector: (U) -> String
    ): JsonValue.ObjectValue = when (field) {
        is FieldAvailability.Available -> JsonValue.ObjectValue(
            linkedMapOf(
                "available" to JsonValue.BooleanValue(true),
                "value" to SafeJsonCodec.number(field.quantity.value),
                "unit" to JsonValue.StringValue(unitNameSelector(field.quantity.unit)),
                "nature" to JsonValue.StringValue(field.quantity.nature.name)
            )
        )
        is FieldAvailability.Unavailable -> JsonValue.ObjectValue(
            linkedMapOf<String, JsonValue>().apply {
                put("available", JsonValue.BooleanValue(false))
                put("reason", JsonValue.StringValue(field.reason.name))
                field.detail?.let { put("detail", JsonValue.StringValue(it)) }
            }
        )
    }

    private fun <U : PhysicalUnit> decodeField(
        obj: JsonValue.ObjectValue,
        validAt: Instant,
        location: GeoLocation,
        source: EnvironmentalDataSource,
        unitDecoder: (String) -> U
    ): FieldAvailability<U> {
        val available = obj.getBoolean("available") ?: false
        return if (available) {
            val value = obj.getDouble("value")!!
            val unit = unitDecoder(obj.getString("unit")!!)
            val nature = ValueNature.valueOf(obj.getString("nature")!!)
            FieldAvailability.Available(
                MeasuredQuantity(
                    value = value,
                    unit = unit,
                    validAt = validAt,
                    location = location,
                    source = source,
                    nature = nature
                )
            )
        } else {
            FieldAvailability.Unavailable(
                reason = UnavailableReason.valueOf(obj.getString("reason")!!),
                detail = obj.getString("detail")
            )
        }
    }

    private fun encodeEnvRecord(rec: StoredEnvironmentalRecord): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf<String, JsonValue>().apply {
            put("id", JsonValue.StringValue(rec.id))
            put("data_type", JsonValue.StringValue(rec.dataType.name))
            rec.observationKind?.let { put("observation_kind", JsonValue.StringValue(it.name)) }
            put("location", encodeLocation(rec.location))
            put("source", encodeSource(rec.source))
            put("valid_at", JsonValue.StringValue(rec.validAt.toString()))
            put("retrieved_at", JsonValue.StringValue(rec.retrievedAt.toString()))
            put("timezone_id", JsonValue.StringValue(rec.timeZoneId))
            put("air_temperature", encodeField(rec.variables.airTemperature) { it.name })
            put("relative_humidity", encodeField(rec.variables.relativeHumidity) { it.name })
            put("wind_speed", encodeField(rec.variables.windSpeed) { it.name })
            put("feels_like_temperature", encodeField(rec.variables.feelsLikeTemperature) { it.name })
            put("uv_index", encodeField(rec.variables.uvIndex) { it.name })
            put("solar_radiation", encodeField(rec.variables.solarRadiation) { it.name })
            rec.variables.isDaylight?.let { put("is_daylight", JsonValue.BooleanValue(it)) }
            rec.variables.wmoWeatherCode?.let { put("wmo_weather_code", SafeJsonCodec.number(it)) }
        }
    )

    private fun decodeEnvRecord(obj: JsonValue.ObjectValue): StoredEnvironmentalRecord {
        val location = decodeLocation(obj.getObject("location")!!)
        val source = decodeSource(obj.getObject("source")!!)
        val validAt = Instant.parse(obj.getString("valid_at")!!)
        val retrievedAt = Instant.parse(obj.getString("retrieved_at")!!)
        val vars = EnvironmentalSnapshotVariables(
            airTemperature = decodeField(obj.getObject("air_temperature")!!, validAt, location, source, TemperatureUnit::valueOf),
            relativeHumidity = decodeField(obj.getObject("relative_humidity")!!, validAt, location, source, PercentageUnit::valueOf),
            windSpeed = decodeField(obj.getObject("wind_speed")!!, validAt, location, source, SpeedUnit::valueOf),
            feelsLikeTemperature = decodeField(obj.getObject("feels_like_temperature")!!, validAt, location, source, TemperatureUnit::valueOf),
            uvIndex = decodeField(obj.getObject("uv_index")!!, validAt, location, source, UvIndexUnit::valueOf),
            solarRadiation = decodeField(obj.getObject("solar_radiation")!!, validAt, location, source, IrradianceUnit::valueOf),
            isDaylight = obj.getBoolean("is_daylight"),
            wmoWeatherCode = obj.getInt("wmo_weather_code")
        )
        return StoredEnvironmentalRecord(
            id = obj.getString("id")!!,
            dataType = HistoricalRecordDataType.valueOf(obj.getString("data_type")!!),
            observationKind = obj.getString("observation_kind")?.let(ObservationKind::valueOf),
            location = location,
            source = source,
            validAt = validAt,
            retrievedAt = retrievedAt,
            timeZoneId = obj.getString("timezone_id")!!,
            variables = vars
        )
    }

    private fun encodeAssessmentRecord(rec: StoredHeatAssessmentRecord): JsonValue.ObjectValue = JsonValue.ObjectValue(
        linkedMapOf<String, JsonValue>().apply {
            put("id", JsonValue.StringValue(rec.id))
            rec.linkedEnvironmentalRecordId?.let { put("linked_record_id", JsonValue.StringValue(it)) }
            put("source_data_type", JsonValue.StringValue(rec.sourceDataType.name))
            put("location", encodeLocation(rec.location))
            put("source", encodeSource(rec.source))
            put("valid_at", JsonValue.StringValue(rec.validAt.toString()))
            put("assessed_at", JsonValue.StringValue(rec.assessedAt.toString()))
            put("retrieved_at", JsonValue.StringValue(rec.retrievedAt.toString()))
            put("timezone_id", JsonValue.StringValue(rec.timeZoneId))
            put("method_id", JsonValue.StringValue(rec.methodId))
            put("method_version", JsonValue.StringValue(rec.methodVersion))
            put("completion_status", JsonValue.StringValue(rec.completionStatus.name))
            rec.category?.let { put("category", JsonValue.StringValue(it.name)) }
            rec.uvCategory?.let { put("uv_category", JsonValue.StringValue(it.name)) }
            rec.heatIndexCelsius?.let { put("heat_index_celsius", SafeJsonCodec.number(it)) }
            rec.airTemperatureValue?.let { put("air_temp_val", SafeJsonCodec.number(it)) }
            rec.airTemperatureUnit?.let { put("air_temp_unit", JsonValue.StringValue(it.name)) }
            rec.relativeHumidityPercent?.let { put("rh_percent", SafeJsonCodec.number(it)) }
            rec.windSpeedValue?.let { put("wind_val", SafeJsonCodec.number(it)) }
            rec.windSpeedUnit?.let { put("wind_unit", JsonValue.StringValue(it.name)) }
            rec.uvIndexValue?.let { put("uv_val", SafeJsonCodec.number(it)) }
            rec.solarRadiationWm2?.let { put("solar_wm2", SafeJsonCodec.number(it)) }
            put("missing_variables", JsonValue.ArrayValue(rec.missingVariables.map(JsonValue::StringValue)))
            put("explanation", JsonValue.StringValue(rec.explanation))
        }
    )

    private fun decodeAssessmentRecord(obj: JsonValue.ObjectValue): StoredHeatAssessmentRecord {
        val missing = obj.getArray("missing_variables")?.items
            ?.mapNotNull { (it as? JsonValue.StringValue)?.value }
            ?: emptyList()
        return StoredHeatAssessmentRecord(
            id = obj.getString("id")!!,
            linkedEnvironmentalRecordId = obj.getString("linked_record_id"),
            sourceDataType = HistoricalRecordDataType.valueOf(obj.getString("source_data_type")!!),
            location = decodeLocation(obj.getObject("location")!!),
            source = decodeSource(obj.getObject("source")!!),
            validAt = Instant.parse(obj.getString("valid_at")!!),
            assessedAt = Instant.parse(obj.getString("assessed_at")!!),
            retrievedAt = Instant.parse(obj.getString("retrieved_at")!!),
            timeZoneId = obj.getString("timezone_id")!!,
            methodId = obj.getString("method_id")!!,
            methodVersion = obj.getString("method_version")!!,
            completionStatus = AssessmentCompletionStatus.valueOf(obj.getString("completion_status")!!),
            category = obj.getString("category")?.let(NwsHeatIndexCategory::valueOf),
            uvCategory = obj.getString("uv_category")?.let(WhoUvCategory::valueOf),
            heatIndexCelsius = obj.getDouble("heat_index_celsius"),
            airTemperatureValue = obj.getDouble("air_temp_val"),
            airTemperatureUnit = obj.getString("air_temp_unit")?.let(TemperatureUnit::valueOf),
            relativeHumidityPercent = obj.getDouble("rh_percent"),
            windSpeedValue = obj.getDouble("wind_val"),
            windSpeedUnit = obj.getString("wind_unit")?.let(SpeedUnit::valueOf),
            uvIndexValue = obj.getDouble("uv_val"),
            solarRadiationWm2 = obj.getDouble("solar_wm2"),
            missingVariables = missing,
            explanation = obj.getString("explanation")!!
        )
    }
}
