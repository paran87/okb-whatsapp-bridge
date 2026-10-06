package com.okb.whatsappbridge.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.okb.whatsappbridge.automation.MessagePart
import com.okb.whatsappbridge.data.local.dao.TextDeliveryDao
import com.okb.whatsappbridge.data.local.entity.TextDeliveryEntity
import com.okb.whatsappbridge.data.remote.dto.TextMessagePart
import com.okb.whatsappbridge.domain.model.TextDeliveryStatus
import com.okb.whatsappbridge.domain.model.TextReportDelivery
import com.okb.whatsappbridge.domain.repository.TextDeliveryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class RoomTextDeliveryRepository(private val dao: TextDeliveryDao) : TextDeliveryRepository {

    override suspend fun get(id: String): TextReportDelivery? = dao.get(id)?.toDomain()

    override suspend fun getByDedupeKey(key: String): TextReportDelivery? = dao.getByDedupeKey(key)?.toDomain()

    override suspend fun insert(delivery: TextReportDelivery): Boolean = try {
        dao.insert(delivery.toEntity())
        true
    } catch (_: SQLiteConstraintException) {
        false
    }

    override suspend fun update(delivery: TextReportDelivery) = dao.update(delivery.toEntity())

    override fun observeRecent(limit: Int): Flow<List<TextReportDelivery>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun withPendingResult(): List<TextReportDelivery> = dao.withPendingResult().map { it.toDomain() }

    override suspend fun deleteFinishedBefore(before: Long): Int = dao.deleteFinishedBefore(before)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val partsSerializer = ListSerializer(TextMessagePart.serializer())

        fun refs(value: String): Set<String> = value.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

        fun TextDeliveryEntity.toDomain() = TextReportDelivery(
            id = id, reportId = reportId, kind = kind, dedupeKey = dedupeKey, destinationGroup = destinationGroup,
            sourceGroup = sourceGroup,
            parts = runCatching { json.decodeFromString(partsSerializer, partsJson) }.getOrDefault(emptyList())
                .map { MessagePart(it.text, it.ref) },
            periodStart = periodStart, periodEnd = periodEnd, reportCount = reportCount,
            status = TextDeliveryStatus.of(status), attempt = attempt, lastError = lastError, verification = verification,
            sentRefs = refs(sentRefs), pressedRefs = refs(pressedRefs), pendingResult = pendingResult,
            pendingRetryable = pendingRetryable, createdAt = createdAt, updatedAt = updatedAt,
            lastAttemptAt = lastAttemptAt, sentAt = sentAt,
        )

        fun TextReportDelivery.toEntity() = TextDeliveryEntity(
            id = id, reportId = reportId, kind = kind, dedupeKey = dedupeKey, destinationGroup = destinationGroup,
            sourceGroup = sourceGroup,
            partsJson = json.encodeToString(partsSerializer, parts.map { TextMessagePart(it.text, it.ref) }),
            periodStart = periodStart, periodEnd = periodEnd, reportCount = reportCount, status = status.name,
            attempt = attempt, lastError = lastError, verification = verification,
            sentRefs = sentRefs.joinToString(","), pressedRefs = pressedRefs.joinToString(","),
            pendingResult = pendingResult, pendingRetryable = pendingRetryable, createdAt = createdAt,
            updatedAt = updatedAt, lastAttemptAt = lastAttemptAt, sentAt = sentAt,
        )
    }
}
