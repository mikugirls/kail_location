package com.kail.location.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_routes ORDER BY timestamp DESC LIMIT 10")
    fun getRecentRoutes(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: HistoryEntity)

    @Query("DELETE FROM history_routes")
    suspend fun clearAll()
}
