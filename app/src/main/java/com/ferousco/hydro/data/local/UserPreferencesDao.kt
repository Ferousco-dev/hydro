package com.ferousco.hydro.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserPreferencesDao {

    @Query("SELECT * FROM user_preferences WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): UserPreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserPreferencesEntity)

    @Query("DELETE FROM user_preferences")
    suspend fun clear()
}
