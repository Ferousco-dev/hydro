package com.ferousco.hydro.core.history

import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.model.HistoricalRecordDataType
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.model.StoredHeatAssessmentRecord
import java.time.Instant

/**
 * Persistence boundary for Hydro's local environmental memory.
 *
 * Implementations include:
 * - [InMemoryEnvironmentalHistoryStore] (JVM tests and ephemeral sessions)
 * - [JsonFileEnvironmentalHistoryStore] (durable JVM file persistence)
 * - `RoomEnvironmentalHistoryStore` in `:app` (Android Room / SQLite database)
 */
interface EnvironmentalHistoryStore {
    suspend fun upsertEnvironmentalRecord(record: StoredEnvironmentalRecord): EngineResult<Unit>

    suspend fun upsertAssessmentRecord(record: StoredHeatAssessmentRecord): EngineResult<Unit>

    suspend fun getEnvironmentalRecordById(id: String): EngineResult<StoredEnvironmentalRecord?>

    suspend fun getAssessmentRecordById(id: String): EngineResult<StoredHeatAssessmentRecord?>

    suspend fun getAssessmentForEnvironmentalRecord(
        environmentalRecordId: String
    ): EngineResult<StoredHeatAssessmentRecord?>

    suspend fun queryEnvironmentalRecords(
        dataTypes: Set<HistoricalRecordDataType> = HistoricalRecordDataType.entries.toSet(),
        validFromInclusive: Instant? = null,
        validToInclusive: Instant? = null,
        limit: Int = 100
    ): EngineResult<List<StoredEnvironmentalRecord>>

    suspend fun queryRecentAssessments(
        dataTypes: Set<HistoricalRecordDataType> = HistoricalRecordDataType.entries.toSet(),
        limit: Int = 100
    ): EngineResult<List<StoredHeatAssessmentRecord>>

    suspend fun listAllEnvironmentalRecords(): EngineResult<List<StoredEnvironmentalRecord>>

    suspend fun listAllAssessmentRecords(): EngineResult<List<StoredHeatAssessmentRecord>>

    suspend fun deleteEnvironmentalRecordById(id: String): EngineResult<Boolean>

    suspend fun deleteAssessmentRecordById(id: String): EngineResult<Boolean>

    suspend fun deleteEnvironmentalRecordsByIds(ids: Set<String>): EngineResult<Int>

    suspend fun deleteAssessmentRecordsByIds(ids: Set<String>): EngineResult<Int>

    suspend fun deleteAll(): EngineResult<Unit>
}
