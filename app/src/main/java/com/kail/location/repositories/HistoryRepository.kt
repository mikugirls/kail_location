package com.kail.location.repositories

import com.kail.location.data.local.HistoryDao
import com.kail.location.data.local.HistoryEntity
import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val historyDao: HistoryDao) {
    val recentRoutes: Flow<List<HistoryEntity>> = historyDao.getRecentRoutes()

    suspend fun addRoute(startName: String, endName: String, startLat: Double, startLng: Double, endLat: Double, endLng: Double) {
        val entity = HistoryEntity(
            startName = startName,
            endName = endName,
            startLat = startLat,
            startLng = startLng,
            endLat = endLat,
            endLng = endLng
        )
        historyDao.insertRoute(entity)
    }

    suspend fun clearHistory() {
        historyDao.clearAll()
    }
}
