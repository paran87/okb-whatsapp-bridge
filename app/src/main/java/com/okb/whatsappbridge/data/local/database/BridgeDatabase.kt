package com.okb.whatsappbridge.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.okb.whatsappbridge.data.local.dao.EventLogDao
import com.okb.whatsappbridge.data.local.dao.GroupDao
import com.okb.whatsappbridge.data.local.dao.MessageDao
import com.okb.whatsappbridge.data.local.dao.SettingsDao
import com.okb.whatsappbridge.data.local.dao.UploadQueueDao
import com.okb.whatsappbridge.data.local.entity.BridgeEventLogEntity
import com.okb.whatsappbridge.data.local.entity.BridgeSettingsEntity
import com.okb.whatsappbridge.data.local.entity.MonitoredGroupEntity
import com.okb.whatsappbridge.data.local.entity.UploadQueueEntity
import com.okb.whatsappbridge.data.local.entity.WhatsAppMessageEntity

/**
 * Local store and source of truth. It is opened by whichever component needs it first
 * (listener service, worker, boot receiver or UI) and does not depend on the UI being alive.
 *
 * Schema changes MUST ship with a Migration; destructive migration is never enabled because
 * it would delete messages that have not been uploaded yet.
 */
@Database(
    entities = [
        WhatsAppMessageEntity::class,
        UploadQueueEntity::class,
        MonitoredGroupEntity::class,
        BridgeSettingsEntity::class,
        BridgeEventLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BridgeDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun uploadQueueDao(): UploadQueueDao
    abstract fun groupDao(): GroupDao
    abstract fun settingsDao(): SettingsDao
    abstract fun eventLogDao(): EventLogDao

    companion object {
        const val NAME = "okb_bridge.db"

        fun create(context: Context): BridgeDatabase =
            Room.databaseBuilder(context.applicationContext, BridgeDatabase::class.java, NAME)
                .build()
    }
}
