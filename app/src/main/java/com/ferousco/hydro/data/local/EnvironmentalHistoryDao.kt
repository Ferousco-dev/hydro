package com.ferousco.hydro.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface EnvironmentalHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEnvironmentalRecord(entity: EnvironmentalRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHeatAssessment(entity: HeatAssessmentEntity)

    @Query("SELECT * FROM environmental_records WHERE id = :id LIMIT 1")
    suspend fun getEnvironmentalRecordById(id: String): EnvironmentalRecordEntity?

    @Query("SELECT * FROM heat_assessment_records WHERE id = :id LIMIT 1")
    suspend fun getAssessmentById(id: String): HeatAssessmentEntity?

    @Query(
        """
        SELECT * FROM heat_assessment_records
        WHERE linkedEnvironmentalRecordId = :envRecordId
        ORDER BY assessedAtEpochMillis DESC
        LIMIT 1
        """
    )
    suspend fun getLatestAssessmentForEnvironmentalRecord(envRecordId: String): HeatAssessmentEntity?

    @Query(
        """
        SELECT * FROM environmental_records
        WHERE dataType IN (:dataTypes)
          AND (:fromMillis IS NULL OR validAtEpochMillis >= :fromMillis)
          AND (:toMillis IS NULL OR validAtEpochMillis <= :toMillis)
        ORDER BY validAtEpochMillis DESC, retrievedAtEpochMillis DESC
        LIMIT :limit
        """
    )
    suspend fun queryEnvironmentalRecords(
        dataTypes: List<String>,
        fromMillis: Long?,
        toMillis: Long?,
        limit: Int
    ): List<EnvironmentalRecordEntity>

    @Query(
        """
        SELECT * FROM heat_assessment_records
        WHERE sourceDataType IN (:dataTypes)
        ORDER BY validAtEpochMillis DESC, assessedAtEpochMillis DESC
        LIMIT :limit
        """
    )
    suspend fun queryRecentAssessments(
        dataTypes: List<String>,
        limit: Int
    ): List<HeatAssessmentEntity>

    @Query("SELECT * FROM environmental_records")
    suspend fun listAllEnvironmentalRecords(): List<EnvironmentalRecordEntity>

    @Query("SELECT * FROM heat_assessment_records")
    suspend fun listAllAssessments(): List<HeatAssessmentEntity>

    @Query("DELETE FROM environmental_records WHERE id = :id")
    suspend fun deleteEnvironmentalRecordById(id: String): Int

    @Query("DELETE FROM heat_assessment_records WHERE id = :id")
    suspend fun deleteAssessmentById(id: String): Int

    @Query("DELETE FROM environmental_records WHERE id IN (:ids)")
    suspend fun deleteEnvironmentalRecordsByIds(ids: List<String>): Int

    @Query("DELETE FROM heat_assessment_records WHERE id IN (:ids)")
    suspend fun deleteAssessmentsByIds(ids: List<String>): Int

    @Query("DELETE FROM environmental_records")
    suspend fun clearAllEnvironmentalRecords()

    @Query("DELETE FROM heat_assessment_records")
    suspend fun clearAllAssessments()

    @Transaction
    suspend fun clearAllHistory() {
        clearAllAssessments()
        clearAllEnvironmentalRecords()
    }
}
