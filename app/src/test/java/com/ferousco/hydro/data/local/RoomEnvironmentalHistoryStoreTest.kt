package com.ferousco.hydro.data.local

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.model.DataAcquisitionMethod
import com.ferousco.hydro.core.model.EnvironmentalDataSource
import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HistoricalRecordDataType
import com.ferousco.hydro.core.model.MeasuredQuantity
import com.ferousco.hydro.core.model.ObservationKind
import com.ferousco.hydro.core.model.PercentageUnit
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.UnavailableReason
import com.ferousco.hydro.core.model.ValueNature
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomEnvironmentalHistoryStoreTest {

    private class FakeDao : EnvironmentalHistoryDao {
        var failWithRuntimeError: Boolean = false
        private val envMap = LinkedHashMap<String, EnvironmentalRecordEntity>()
        private val assessMap = LinkedHashMap<String, HeatAssessmentEntity>()

        private fun checkFailure() {
            if (failWithRuntimeError) throw IllegalStateException("Simulated SQLite disk I/O error")
        }

        override suspend fun upsertEnvironmentalRecord(entity: EnvironmentalRecordEntity) {
            checkFailure()
            envMap[entity.id] = entity
        }

        override suspend fun upsertHeatAssessment(entity: HeatAssessmentEntity) {
            checkFailure()
            assessMap[entity.id] = entity
        }

        override suspend fun getEnvironmentalRecordById(id: String): EnvironmentalRecordEntity? {
            checkFailure()
            return envMap[id]
        }

        override suspend fun getAssessmentById(id: String): HeatAssessmentEntity? {
            checkFailure()
            return assessMap[id]
        }

        override suspend fun getLatestAssessmentForEnvironmentalRecord(envRecordId: String): HeatAssessmentEntity? {
            checkFailure()
            return assessMap.values
                .filter { it.linkedEnvironmentalRecordId == envRecordId }
                .maxByOrNull { it.assessedAtEpochMillis }
        }

        override suspend fun queryEnvironmentalRecords(
            dataTypes: List<String>,
            fromMillis: Long?,
            toMillis: Long?,
            limit: Int
        ): List<EnvironmentalRecordEntity> {
            checkFailure()
            return envMap.values
                .filter { it.dataType in dataTypes }
                .filter { fromMillis == null || it.validAtEpochMillis >= fromMillis }
                .filter { toMillis == null || it.validAtEpochMillis <= toMillis }
                .sortedByDescending { it.validAtEpochMillis }
                .take(limit)
        }

        override suspend fun queryRecentAssessments(dataTypes: List<String>, limit: Int): List<HeatAssessmentEntity> {
            checkFailure()
            return assessMap.values.filter { it.sourceDataType in dataTypes }.take(limit)
        }

        override suspend fun listAllEnvironmentalRecords(): List<EnvironmentalRecordEntity> {
            checkFailure()
            return envMap.values.toList()
        }

        override suspend fun listAllAssessments(): List<HeatAssessmentEntity> {
            checkFailure()
            return assessMap.values.toList()
        }

        override suspend fun deleteEnvironmentalRecordById(id: String): Int {
            checkFailure()
            return if (envMap.remove(id) != null) 1 else 0
        }

        override suspend fun deleteAssessmentById(id: String): Int {
            checkFailure()
            return if (assessMap.remove(id) != null) 1 else 0
        }

        override suspend fun deleteEnvironmentalRecordsByIds(ids: List<String>): Int {
            checkFailure()
            var c = 0
            ids.forEach { if (envMap.remove(it) != null) c++ }
            return c
        }

        override suspend fun deleteAssessmentsByIds(ids: List<String>): Int {
            checkFailure()
            var c = 0
            ids.forEach { if (assessMap.remove(it) != null) c++ }
            return c
        }

        override suspend fun clearAllEnvironmentalRecords() {
            checkFailure()
            envMap.clear()
        }

        override suspend fun clearAllAssessments() {
            checkFailure()
            assessMap.clear()
        }
    }

    @Test
    fun `RoomEnvironmentalHistoryStore preserves provenance, units, unavailability, and wraps SQLite errors`() = runBlocking {
        val dao = FakeDao()
        val store = RoomEnvironmentalHistoryStore(dao)
        val loc = GeoLocation(7.3775, 3.9470, 230.0, ZoneId.of("Africa/Lagos"), "Ibadan")
        val src = EnvironmentalDataSource(
            providerId = "open-meteo",
            providerName = "Open-Meteo",
            endpointOrDataset = "/v1/forecast",
            acquisitionMethod = DataAcquisitionMethod.GRIDDED_MODEL_NOWCAST,
            modelInfo = "best_match",
            license = EnvironmentalDataSource.OPEN_METEO_CC_BY_LICENSE,
            attribution = EnvironmentalDataSource.OPEN_METEO_ATTRIBUTION,
            resolvedGridLocation = loc
        )
        val validAt = Instant.parse("2026-10-26T12:00:00Z")
        val record = StoredEnvironmentalRecord(
            id = "room-obs-1",
            dataType = HistoricalRecordDataType.OBSERVATION,
            observationKind = ObservationKind.MODEL_ASSIMILATED_CURRENT,
            location = loc,
            source = src,
            validAt = validAt,
            retrievedAt = validAt.plusSeconds(60),
            timeZoneId = "Africa/Lagos",
            variables = EnvironmentalSnapshotVariables(
                airTemperature = FieldAvailability.Available(
                    MeasuredQuantity(33.2, TemperatureUnit.CELSIUS, validAt, loc, src, ValueNature.OBSERVED)
                ),
                relativeHumidity = FieldAvailability.Available(
                    MeasuredQuantity(67.0, PercentageUnit.PERCENT, validAt, loc, src, ValueNature.OBSERVED)
                ),
                uvIndex = FieldAvailability.Unavailable(UnavailableReason.NULL_IN_PROVIDER_RESPONSE, "null in payload")
            )
        )

        assertTrue(store.upsertEnvironmentalRecord(record).isSuccess)
        val loaded = store.getEnvironmentalRecordById("room-obs-1").getOrNull()
        assertNotNull(loaded)
        assertEquals(33.2, loaded.variables.airTemperature.quantityOrNull()!!.value, 1e-9)
        assertFalse(loaded.variables.uvIndex.isAvailable)
        assertEquals(
            UnavailableReason.NULL_IN_PROVIDER_RESPONSE,
            (loaded.variables.uvIndex as FieldAvailability.Unavailable).reason
        )

        // Simulate SQLite exception -> PersistenceFailure
        dao.failWithRuntimeError = true
        val failedQuery = store.queryEnvironmentalRecords()
        assertTrue(failedQuery.errorOrNull() is EngineError.PersistenceFailure)
    }
}
