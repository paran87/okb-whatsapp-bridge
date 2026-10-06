package com.okb.whatsappbridge.data.repository

import com.okb.whatsappbridge.data.local.dao.ConsolidatedDeliveryDao
import com.okb.whatsappbridge.data.local.entity.ConsolidatedDeliveryEntity
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryCounts
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus
import com.okb.whatsappbridge.domain.model.ConsolidatedReportDelivery
import com.okb.whatsappbridge.domain.repository.ConsolidatedDeliveryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomConsolidatedDeliveryRepository(private val dao: ConsolidatedDeliveryDao) : ConsolidatedDeliveryRepository {

    override suspend fun get(id: String): ConsolidatedReportDelivery? = dao.get(id)?.toDomain()

    override suspend fun save(delivery: ConsolidatedReportDelivery) = dao.upsert(delivery.toEntity())

    override fun observeRecent(limit: Int): Flow<List<ConsolidatedReportDelivery>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeCounts(): Flow<ConsolidatedDeliveryCounts> = dao.observeStatusCounts().map { rows ->
        val by = rows.associate { ConsolidatedDeliveryStatus.of(it.status) to it.count }
        fun n(vararg s: ConsolidatedDeliveryStatus) = s.sumOf { by[it] ?: 0 }
        ConsolidatedDeliveryCounts(
            pending = n(ConsolidatedDeliveryStatus.READY_TO_SEND, ConsolidatedDeliveryStatus.DOWNLOADING),
            ready = n(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, ConsolidatedDeliveryStatus.OPENED_IN_WHATSAPP),
            sent = n(ConsolidatedDeliveryStatus.SENT),
            failed = n(ConsolidatedDeliveryStatus.FAILED),
        )
    }

    override suspend fun withPendingAck(): List<ConsolidatedReportDelivery> = dao.withPendingAck().map { it.toDomain() }

    override suspend fun deleteFinishedBefore(before: Long): Int = dao.deleteFinishedBefore(before)
}

private fun ConsolidatedDeliveryEntity.toDomain() = ConsolidatedReportDelivery(
    id = id, kind = kind, fileName = fileName, caption = caption, sourceGroup = sourceGroup,
    destinationGroup = destinationGroup, periodStart = periodStart, periodEnd = periodEnd, reportCount = reportCount,
    pdfPath = pdfPath, status = ConsolidatedDeliveryStatus.of(status), errorMessage = errorMessage,
    downloadAttempts = downloadAttempts, pendingAck = pendingAck, pendingAckError = pendingAckError,
    createdAt = createdAt, updatedAt = updatedAt, downloadedAt = downloadedAt, openedAt = openedAt, sentAt = sentAt,
)

private fun ConsolidatedReportDelivery.toEntity() = ConsolidatedDeliveryEntity(
    id = id, kind = kind, fileName = fileName, caption = caption, sourceGroup = sourceGroup,
    destinationGroup = destinationGroup, periodStart = periodStart, periodEnd = periodEnd, reportCount = reportCount,
    pdfPath = pdfPath, status = status.name, errorMessage = errorMessage, downloadAttempts = downloadAttempts,
    pendingAck = pendingAck, pendingAckError = pendingAckError, createdAt = createdAt, updatedAt = updatedAt,
    downloadedAt = downloadedAt, openedAt = openedAt, sentAt = sentAt,
)
