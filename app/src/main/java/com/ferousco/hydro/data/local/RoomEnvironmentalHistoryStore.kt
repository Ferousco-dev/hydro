package com.ferousco.hydro.data.local

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.history.EnvironmentalHistoryStore
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Room-backed implementation of [EnvironmentalHistoryStore] for Android local persistence.
 */
class RoomEnvironmentalHistoryStore(
    private val dao: EnvironmentalHistoryDao
) : EnvironmentalHistoryStore {

    override suspend fun upsertEnvironmentalRecord(record: StoredEnvironmentalRecord): EngineResult<Unit> =
        runRoomSafely("upsertEnvironmentalRecord") {
            dao.upsertEnvironmentalRecord(record.toEntity())
        }

    override suspend fun upsertAssessmentRecord(record: StoredHeatAssessmentRecord): EngineResult<Unit> =
        runRoomSafely("upsertAssessmentRecord") {
            dao.upsertHeatAssessment(record.toEntity())
        }

    override suspend fun getEnvironmentalRecordById(id: String): EngineResult<StoredEnvironmentalRecord?> =
        runRoomSafely("getEnvironmentalRecordById") {
            dao.getEnvironmentalRecordById(id)?.toDomain()
        }

    override suspend fun getAssessmentRecordById(id: String): EngineResult<StoredHeatAssessmentRecord?> =
        runRoomSafely("getAssessmentRecordById") {
            dao.getAssessmentById(id)?.toDomain()
        }

    override suspend fun getAssessmentForEnvironmentalRecord(
        environmentalRecordId: String
    ): EngineResult<StoredHeatAssessmentRecord?> =
        runRoomSafely("getAssessmentForEnvironmentalRecord") {
            dao.getLatestAssessmentForEnvironmentalRecord(environmentalRecordId)?.toDomain()
        }

    override suspend fun queryEnvironmentalRecords(
        dataTypes: Set<HistoricalRecordDataType>,
        validFromInclusive: Instant?,
        validToInclusive: Instant?,
        limit: Int
    ): EngineResult<List<StoredEnvironmentalRecord>> =
        runRoomSafely("queryEnvironmentalRecords") {
            if (dataTypes.isEmpty() || limit <= 0) {
                emptyList()
            } else {
                dao.queryEnvironmentalRecords(
                    dataTypes = dataTypes.map { it.name },
                    fromMillis = validFromInclusive?.toEpochMilli(),
                    toMillis = validToInclusive?.toEpochMilli(),
                    limit = limit
                ).map { it.toDomain() }
            }
        }

    override suspend fun queryRecentAssessments(
        dataTypes: Set<HistoricalRecordDataType>,
        limit: Int
    ): EngineResult<List<StoredHeatAssessmentRecord>> =
        runRoomSafely("queryRecentAssessments") {
            if (dataTypes.isEmpty() || limit <= 0) {
                emptyList()
            } else {
                dao.queryRecentAssessments(
                    dataTypes = dataTypes.map { it.name },
                    limit = limit
                ).map { it.toDomain() }
            }
        }

    override suspend fun listAllEnvironmentalRecords(): EngineResult<List<StoredEnvironmentalRecord>> =
        runRoomSafely("listAllEnvironmentalRecords") {
            dao.listAllEnvironmentalRecords().map { it.toDomain() }
        }

    override suspend fun listAllAssessmentRecords(): EngineResult<List<StoredHeatAssessmentRecord>> =
        runRoomSafely("listAllAssessmentRecords") {
            dao.listAllAssessments().map { it.toDomain() }
        }

    override suspend fun deleteEnvironmentalRecordById(id: String): EngineResult<Boolean> =
        runRoomSafely("deleteEnvironmentalRecordById") {
            dao.deleteEnvironmentalRecordById(id) > 0
        }

    override suspend fun deleteAssessmentRecordById(id: String): EngineResult<Boolean> =
        runRoomSafely("deleteAssessmentRecordById") {
            dao.deleteAssessmentById(id) > 0
        }

    override suspend fun deleteEnvironmentalRecordsByIds(ids: Set<String>): EngineResult<Int> =
        runRoomSafely("deleteEnvironmentalRecordsByIds") {
            if (ids.isEmpty()) 0 else dao.deleteEnvironmentalRecordsByIds(ids.toList())
        }

    override suspend fun deleteAssessmentRecordsByIds(ids: Set<String>): EngineResult<Int> =
        runRoomSafely("deleteAssessmentRecordsByIds") {
            if (ids.isEmpty()) 0 else dao.deleteAssessmentsByIds(ids.toList())
        }

    override suspend fun deleteAll(): EngineResult<Unit> =
        runRoomSafely("deleteAll") {
            dao.clearAllHistory()
        }

    private suspend inline fun <T> runRoomSafely(
        operation: String,
        block: () -> T
    ): EngineResult<T> {
        return try {
            EngineResult.Success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            EngineResult.Failure(
                EngineError.PersistenceFailure(
                    operation = operation,
                    message = "Room database operation '$operation' failed: ${ex.message}",
                    causeSummary = ex::class.simpleName
                )
            )
        }
    }

    private fun StoredEnvironmentalRecord.toEntity(): EnvironmentalRecordEntity {
        val air = unpackField(variables.airTemperature) { it.name }
        val rh = unpackField(variables.relativeHumidity) { it.name }
        val wind = unpackField(variables.windSpeed) { it.name }
        val feels = unpackField(variables.feelsLikeTemperature) { it.name }
        val uv = unpackField(variables.uvIndex) { it.name }
        val solar = unpackField(variables.solarRadiation) { it.name }
        val grid = source.resolvedGridLocation

        return EnvironmentalRecordEntity(
            id = id,
            dataType = dataType.name,
            observationKind = observationKind?.name,
            latitude = location.latitude,
            longitude = location.longitude,
            elevationMeters = location.elevationMeters,
            locationLabel = location.label,
            timeZoneId = timeZoneId,
            providerId = source.providerId,
            providerName = source.providerName,
            endpointOrDataset = source.endpointOrDataset,
            acquisitionMethod = source.acquisitionMethod.name,
            modelInfo = source.modelInfo,
            license = source.license,
            attribution = source.attribution,
            gridLatitude = grid?.latitude,
            gridLongitude = grid?.longitude,
            gridElevationMeters = grid?.elevationMeters,
            validAtEpochMillis = validAt.toEpochMilli(),
            retrievedAtEpochMillis = retrievedAt.toEpochMilli(),
            airTempAvailable = air.available,
            airTempValue = air.value,
            airTempUnit = air.unit,
            airTempNature = air.nature,
            airTempUnavailableReason = air.unavailableReason,
            airTempUnavailableDetail = air.unavailableDetail,
            rhAvailable = rh.available,
            rhValue = rh.value,
            rhUnit = rh.unit,
            rhNature = rh.nature,
            rhUnavailableReason = rh.unavailableReason,
            rhUnavailableDetail = rh.unavailableDetail,
            windAvailable = wind.available,
            windValue = wind.value,
            windUnit = wind.unit,
            windNature = wind.nature,
            windUnavailableReason = wind.unavailableReason,
            windUnavailableDetail = wind.unavailableDetail,
            feelsLikeAvailable = feels.available,
            feelsLikeValue = feels.value,
            feelsLikeUnit = feels.unit,
            feelsLikeNature = feels.nature,
            feelsLikeUnavailableReason = feels.unavailableReason,
            feelsLikeUnavailableDetail = feels.unavailableDetail,
            uvAvailable = uv.available,
            uvValue = uv.value,
            uvUnit = uv.unit,
            uvNature = uv.nature,
            uvUnavailableReason = uv.unavailableReason,
            uvUnavailableDetail = uv.unavailableDetail,
            solarAvailable = solar.available,
            solarValue = solar.value,
            solarUnit = solar.unit,
            solarNature = solar.nature,
            solarUnavailableReason = solar.unavailableReason,
            solarUnavailableDetail = solar.unavailableDetail,
            isDaylight = variables.isDaylight,
            wmoWeatherCode = variables.wmoWeatherCode
        )
    }

    private fun EnvironmentalRecordEntity.toDomain(): StoredEnvironmentalRecord {
        val zone = safeZone(timeZoneId)
        val location = GeoLocation(
            latitude = latitude,
            longitude = longitude,
            elevationMeters = elevationMeters,
            timeZone = zone,
            label = locationLabel
        )
        val gridLoc = if (gridLatitude != null && gridLongitude != null) {
            GeoLocation(gridLatitude, gridLongitude, gridElevationMeters, zone)
        } else {
            null
        }
        val source = EnvironmentalDataSource(
            providerId = providerId,
            providerName = providerName,
            endpointOrDataset = endpointOrDataset,
            acquisitionMethod = DataAcquisitionMethod.valueOf(acquisitionMethod),
            modelInfo = modelInfo,
            license = license,
            attribution = attribution,
            resolvedGridLocation = gridLoc
        )
        val validAt = Instant.ofEpochMilli(validAtEpochMillis)
        val retrievedAt = Instant.ofEpochMilli(retrievedAtEpochMillis)

        val variables = EnvironmentalSnapshotVariables(
            airTemperature = rebuildField(
                airTempAvailable, airTempValue, airTempUnit, airTempNature,
                airTempUnavailableReason, airTempUnavailableDetail,
                validAt, location, source, TemperatureUnit::valueOf
            ),
            relativeHumidity = rebuildField(
                rhAvailable, rhValue, rhUnit, rhNature,
                rhUnavailableReason, rhUnavailableDetail,
                validAt, location, source, PercentageUnit::valueOf
            ),
            windSpeed = rebuildField(
                windAvailable, windValue, windUnit, windNature,
                windUnavailableReason, windUnavailableDetail,
                validAt, location, source, SpeedUnit::valueOf
            ),
            feelsLikeTemperature = rebuildField(
                feelsLikeAvailable, feelsLikeValue, feelsLikeUnit, feelsLikeNature,
                feelsLikeUnavailableReason, feelsLikeUnavailableDetail,
                validAt, location, source, TemperatureUnit::valueOf
            ),
            uvIndex = rebuildField(
                uvAvailable, uvValue, uvUnit, uvNature,
                uvUnavailableReason, uvUnavailableDetail,
                validAt, location, source, UvIndexUnit::valueOf
            ),
            solarRadiation = rebuildField(
                solarAvailable, solarValue, solarUnit, solarNature,
                solarUnavailableReason, solarUnavailableDetail,
                validAt, location, source, IrradianceUnit::valueOf
            ),
            isDaylight = isDaylight,
            wmoWeatherCode = wmoWeatherCode
        )

        return StoredEnvironmentalRecord(
            id = id,
            dataType = HistoricalRecordDataType.valueOf(dataType),
            observationKind = observationKind?.let(ObservationKind::valueOf),
            location = location,
            source = source,
            validAt = validAt,
            retrievedAt = retrievedAt,
            timeZoneId = timeZoneId,
            variables = variables
        )
    }

    private fun StoredHeatAssessmentRecord.toEntity(): HeatAssessmentEntity = HeatAssessmentEntity(
        id = id,
        linkedEnvironmentalRecordId = linkedEnvironmentalRecordId,
        sourceDataType = sourceDataType.name,
        latitude = location.latitude,
        longitude = location.longitude,
        elevationMeters = location.elevationMeters,
        locationLabel = location.label,
        timeZoneId = timeZoneId,
        providerId = source.providerId,
        providerName = source.providerName,
        endpointOrDataset = source.endpointOrDataset,
        acquisitionMethod = source.acquisitionMethod.name,
        modelInfo = source.modelInfo,
        license = source.license,
        attribution = source.attribution,
        validAtEpochMillis = validAt.toEpochMilli(),
        assessedAtEpochMillis = assessedAt.toEpochMilli(),
        retrievedAtEpochMillis = retrievedAt.toEpochMilli(),
        methodId = methodId,
        methodVersion = methodVersion,
        completionStatus = completionStatus.name,
        category = category?.name,
        uvCategory = uvCategory?.name,
        heatIndexCelsius = heatIndexCelsius,
        airTemperatureValue = airTemperatureValue,
        airTemperatureUnit = airTemperatureUnit?.name,
        relativeHumidityPercent = relativeHumidityPercent,
        windSpeedValue = windSpeedValue,
        windSpeedUnit = windSpeedUnit?.name,
        uvIndexValue = uvIndexValue,
        solarRadiationWm2 = solarRadiationWm2,
        missingVariablesCsv = missingVariables.joinToString(","),
        explanation = explanation
    )

    private fun HeatAssessmentEntity.toDomain(): StoredHeatAssessmentRecord {
        val zone = safeZone(timeZoneId)
        val location = GeoLocation(latitude, longitude, elevationMeters, zone, locationLabel)
        val source = EnvironmentalDataSource(
            providerId = providerId,
            providerName = providerName,
            endpointOrDataset = endpointOrDataset,
            acquisitionMethod = DataAcquisitionMethod.valueOf(acquisitionMethod),
            modelInfo = modelInfo,
            license = license,
            attribution = attribution
        )
        return StoredHeatAssessmentRecord(
            id = id,
            linkedEnvironmentalRecordId = linkedEnvironmentalRecordId,
            sourceDataType = HistoricalRecordDataType.valueOf(sourceDataType),
            location = location,
            source = source,
            validAt = Instant.ofEpochMilli(validAtEpochMillis),
            assessedAt = Instant.ofEpochMilli(assessedAtEpochMillis),
            retrievedAt = Instant.ofEpochMilli(retrievedAtEpochMillis),
            timeZoneId = timeZoneId,
            methodId = methodId,
            methodVersion = methodVersion,
            completionStatus = AssessmentCompletionStatus.valueOf(completionStatus),
            category = category?.let(NwsHeatIndexCategory::valueOf),
            uvCategory = uvCategory?.let(WhoUvCategory::valueOf),
            heatIndexCelsius = heatIndexCelsius,
            airTemperatureValue = airTemperatureValue,
            airTemperatureUnit = airTemperatureUnit?.let(TemperatureUnit::valueOf),
            relativeHumidityPercent = relativeHumidityPercent,
            windSpeedValue = windSpeedValue,
            windSpeedUnit = windSpeedUnit?.let(SpeedUnit::valueOf),
            uvIndexValue = uvIndexValue,
            solarRadiationWm2 = solarRadiationWm2,
            missingVariables = if (missingVariablesCsv.isBlank()) emptyList() else missingVariablesCsv.split(","),
            explanation = explanation
        )
    }

    private data class UnpackedField(
        val available: Boolean,
        val value: Double?,
        val unit: String?,
        val nature: String?,
        val unavailableReason: String?,
        val unavailableDetail: String?
    )

    private fun <U : PhysicalUnit> unpackField(
        field: FieldAvailability<U>,
        unitSelector: (U) -> String
    ): UnpackedField = when (field) {
        is FieldAvailability.Available -> UnpackedField(
            available = true,
            value = field.quantity.value,
            unit = unitSelector(field.quantity.unit),
            nature = field.quantity.nature.name,
            unavailableReason = null,
            unavailableDetail = null
        )
        is FieldAvailability.Unavailable -> UnpackedField(
            available = false,
            value = null,
            unit = null,
            nature = null,
            unavailableReason = field.reason.name,
            unavailableDetail = field.detail
        )
    }

    private fun <U : PhysicalUnit> rebuildField(
        available: Boolean,
        value: Double?,
        unitName: String?,
        natureName: String?,
        unavailableReason: String?,
        unavailableDetail: String?,
        validAt: Instant,
        location: GeoLocation,
        source: EnvironmentalDataSource,
        unitDecoder: (String) -> U
    ): FieldAvailability<U> {
        return if (available && value != null && unitName != null && natureName != null) {
            FieldAvailability.Available(
                MeasuredQuantity(
                    value = value,
                    unit = unitDecoder(unitName),
                    validAt = validAt,
                    location = location,
                    source = source,
                    nature = ValueNature.valueOf(natureName)
                )
            )
        } else {
            FieldAvailability.Unavailable(
                reason = unavailableReason?.let(UnavailableReason::valueOf) ?: UnavailableReason.NOT_PROVIDED_BY_SOURCE,
                detail = unavailableDetail
            )
        }
    }

    private fun safeZone(tzId: String): ZoneId = try {
        ZoneId.of(tzId)
    } catch (_: Exception) {
        ZoneOffset.UTC
    }
}
