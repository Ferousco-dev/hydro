package com.ferousco.hydro.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EnvironmentalRecordEntity::class,
        HeatAssessmentEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class HydroDatabase : RoomDatabase() {

    abstract fun environmentalHistoryDao(): EnvironmentalHistoryDao

    companion object {
        private const val DATABASE_NAME = "hydro_environmental_history.db"

        @Volatile
        private var instance: HydroDatabase? = null

        fun getInstance(context: Context): HydroDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HydroDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
