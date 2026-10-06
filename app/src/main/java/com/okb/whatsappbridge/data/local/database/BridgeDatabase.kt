package com.okb.whatsappbridge.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.okb.whatsappbridge.data.local.dao.ConsolidatedDeliveryDao
import com.okb.whatsappbridge.data.local.dao.EventLogDao
import com.okb.whatsappbridge.data.local.dao.GroupDao
import com.okb.whatsappbridge.data.local.dao.MediaDao
import com.okb.whatsappbridge.data.local.dao.MessageDao
import com.okb.whatsappbridge.data.local.dao.SettingsDao
import com.okb.whatsappbridge.data.local.dao.TextDeliveryDao
import com.okb.whatsappbridge.data.local.dao.UploadQueueDao
import com.okb.whatsappbridge.data.local.entity.BridgeEventLogEntity
import com.okb.whatsappbridge.data.local.entity.BridgeSettingsEntity
import com.okb.whatsappbridge.data.local.entity.ConsolidatedDeliveryEntity
import com.okb.whatsappbridge.data.local.entity.MediaAttachmentEntity
import com.okb.whatsappbridge.data.local.entity.MediaUploadQueueEntity
import com.okb.whatsappbridge.data.local.entity.MonitoredGroupEntity
import com.okb.whatsappbridge.data.local.entity.TextDeliveryEntity
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
        MediaAttachmentEntity::class,
        MediaUploadQueueEntity::class,
        ConsolidatedDeliveryEntity::class,
        TextDeliveryEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class BridgeDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun uploadQueueDao(): UploadQueueDao
    abstract fun groupDao(): GroupDao
    abstract fun settingsDao(): SettingsDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun mediaDao(): MediaDao
    abstract fun consolidatedDeliveryDao(): ConsolidatedDeliveryDao
    abstract fun textDeliveryDao(): TextDeliveryDao

    companion object {
        const val NAME = "okb_bridge.db"

        fun create(context: Context): BridgeDatabase =
            Room.databaseBuilder(context.applicationContext, BridgeDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                // Enforce media/message FK cascade so deleting a message removes its media rows.
                .build()
    }
}
