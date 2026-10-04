package com.okb.whatsappbridge.data.repository

import com.okb.whatsappbridge.data.local.dao.GroupDao
import com.okb.whatsappbridge.data.local.entity.MonitoredGroupEntity
import com.okb.whatsappbridge.domain.model.MonitoredGroup
import com.okb.whatsappbridge.domain.repository.GroupRepository
import com.okb.whatsappbridge.whatsapp.GroupAllowlist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomGroupRepository(private val dao: GroupDao) : GroupRepository {

    override fun observeGroups(): Flow<List<MonitoredGroup>> = dao.observeAll().map { rows ->
        rows.map {
            MonitoredGroup(
                id = it.id,
                name = it.name,
                authorized = it.authorized,
                discoveredAutomatically = it.discoveredAutomatically,
                createdAt = it.createdAt,
                lastSeenAt = it.lastSeenAt,
            )
        }
    }

    override suspend fun authorizedGroupNames(): List<String> = dao.authorizedNames()

    override suspend fun addAuthorizedGroup(name: String, at: Long): Boolean {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return false
        val normalized = GroupAllowlist.normalize(clean)
        val existing = dao.findByNormalizedName(normalized)
        if (existing != null) {
            dao.setAuthorized(existing.id, true)
        } else {
            dao.insert(
                MonitoredGroupEntity(
                    name = clean,
                    normalizedName = normalized,
                    authorized = true,
                    discoveredAutomatically = false,
                    createdAt = at,
                ),
            )
        }
        return true
    }

    override suspend fun setAuthorized(id: Long, authorized: Boolean) = dao.setAuthorized(id, authorized)

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun recordSeen(name: String, at: Long) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val normalized = GroupAllowlist.normalize(clean)
        if (dao.findByNormalizedName(normalized) == null) {
            dao.insert(
                MonitoredGroupEntity(
                    name = clean,
                    normalizedName = normalized,
                    authorized = false,
                    discoveredAutomatically = true,
                    createdAt = at,
                    lastSeenAt = at,
                ),
            )
        } else {
            dao.touch(normalized, at)
        }
    }
}
