package com.ferousco.hydro.core.history

import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.model.HistoricalRecordDataType
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.model.StoredHeatAssessmentRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Coroutine-safe in-memory implementation of [EnvironmentalHistoryStore].
 */
class InMemoryEnvironmentalHistoryStore : EnvironmentalHistoryStore {
    private val mutex = Mutex()
    private val envRecords = LinkedHashMap<String, StoredEnvironmentalRecord>()
    private val assessmentRecords = LinkedHashMap<String, StoredHeatAssessmentRecord>()

    override suspend fun upsertEnvironmentalRecord(record: StoredEnvironmentalRecord): EngineResult<Unit> =
        mutex.withLock {
            envRecords[record.id] = record
            EngineResult.Success(Unit)
        }

    override suspend fun upsertAssessmentRecord(record: StoredHeatAssessmentRecord): EngineResult<Unit> =
        mutex.withLock {
            assessmentRecords[record.id] = record
            EngineResult.Success(Unit)
        }

    override suspend fun getEnvironmentalRecordById(id: String): EngineResult<StoredEnvironmentalRecord?> =
        mutex.withLock {
            EngineResult.Success(envRecords[id])
        }

    override suspend fun getAssessmentRecordById(id: String): EngineResult<StoredHeatAssessmentRecord?> =
        mutex.withLock {
            EngineResult.Success(assessmentRecords[id])
        }

    override suspend fun getAssessmentForEnvironmentalRecord(
        environmentalRecordId: String
    ): EngineResult<StoredHeatAssessmentRecord?> = mutex.withLock {
        val found = assessmentRecords.values
            .filter { it.linkedEnvironmentalRecordId == environmentalRecordId }
            .maxByOrNull { it.assessedAt }
        EngineResult.Success(found)
    }

    override suspend fun queryEnvironmentalRecords(
        dataTypes: Set<HistoricalRecordDataType>,
        validFromInclusive: Instant?,
        validToInclusive: Instant?,
        limit: Int
    ): EngineResult<List<StoredEnvironmentalRecord>> = mutex.withLock {
        val filtered = envRecords.values
            .asSequence()
            .filter { it.dataType in dataTypes }
            .filter { validFromInclusive == null || !it.validAt.isBefore(validFromInclusive) }
            .filter { validToInclusive == null || !it.validAt.isAfter(validToInclusive) }
            .sortedWith(compareByDescending<StoredEnvironmentalRecord> { it.validAt }.thenByDescending { it.retrievedAt })
            .take(limit.coerceAtLeast(0))
            .toList()
        EngineResult.Success(filtered)
    }

    override suspend fun queryRecentAssessments(
        dataTypes: Set<HistoricalRecordDataType>,
        limit: Int
    ): EngineResult<List<StoredHeatAssessmentRecord>> = mutex.withLock {
        val filtered = assessmentRecords.values
            .asSequence()
            .filter { it.sourceDataType in dataTypes }
            .sortedWith(compareByDescending<StoredHeatAssessmentRecord> { it.validAt }.thenByDescending { it.assessedAt })
            .take(limit.coerceAtLeast(0))
            .toList()
        EngineResult.Success(filtered)
    }

    override suspend fun listAllEnvironmentalRecords(): EngineResult<List<StoredEnvironmentalRecord>> =
        mutex.withLock { EngineResult.Success(envRecords.values.toList()) }

    override suspend fun listAllAssessmentRecords(): EngineResult<List<StoredHeatAssessmentRecord>> =
        mutex.withLock { EngineResult.Success(assessmentRecords.values.toList()) }

    override suspend fun deleteEnvironmentalRecordById(id: String): EngineResult<Boolean> =
        mutex.withLock { EngineResult.Success(envRecords.remove(id) != null) }

    override suspend fun deleteAssessmentRecordById(id: String): EngineResult<Boolean> =
        mutex.withLock { EngineResult.Success(assessmentRecords.remove(id) != null) }

    override suspend fun deleteEnvironmentalRecordsByIds(ids: Set<String>): EngineResult<Int> =
        mutex.withLock {
            var count = 0
            for (id in ids) {
                if (envRecords.remove(id) != null) count++
            }
            EngineResult.Success(count)
        }

    override suspend fun deleteAssessmentRecordsByIds(ids: Set<String>): EngineResult<Int> =
        mutex.withLock {
            var count = 0
            for (id in ids) {
                if (assessmentRecords.remove(id) != null) count++
            }
            EngineResult.Success(count)
        }

    override suspend fun deleteAll(): EngineResult<Unit> = mutex.withLock {
        envRecords.clear()
        assessmentRecords.clear()
        EngineResult.Success(Unit)
    }
}
