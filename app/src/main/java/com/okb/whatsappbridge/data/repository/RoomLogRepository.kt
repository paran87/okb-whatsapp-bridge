package com.okb.whatsappbridge.data.repository

import com.okb.whatsappbridge.data.local.dao.EventLogDao
import com.okb.whatsappbridge.domain.model.BridgeLogEntry
import com.okb.whatsappbridge.domain.repository.LogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLogRepository(private val dao: EventLogDao) : LogRepository {
    override fun observeRecent(limit: Int): Flow<List<BridgeLogEntry>> = dao.observeRecent(limit).map { rows ->
        rows.map { BridgeLogEntry(it.id, it.timestamp, it.level, it.tag, it.message) }
    }

    override suspend fun clear() = dao.clear()
}
