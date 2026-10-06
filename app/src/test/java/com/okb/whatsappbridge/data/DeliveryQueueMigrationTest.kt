package com.okb.whatsappbridge.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.data.local.database.BridgeDatabase
import com.okb.whatsappbridge.data.local.database.Migrations
import com.okb.whatsappbridge.data.repository.RoomConsolidatedDeliveryRepository
import com.okb.whatsappbridge.data.repository.RoomGroupRepository
import com.okb.whatsappbridge.data.repository.RoomSettingsRepository
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus
import com.okb.whatsappbridge.domain.model.ConsolidatedReportDelivery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v3 → v4 (consolidated report delivery queue). The v3 database is created from the schema Room exported for
 * v3 (app/schemas/.../3.json) with real rows, then opened with the real BridgeDatabase: Room validates that the
 * migrated schema matches the v4 entities, and messages, Recycle Bin state, groups and settings survive.
 */
@RunWith(AndroidJUnit4::class)
class DeliveryQueueMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile = File.createTempFile("okb-migration-v3", ".db").also { it.delete() }

    @After
    fun cleanup() {
        dbFile.delete()
    }

    private fun createV3FromExportedSchema() {
        val schema = File("schemas/com.okb.whatsappbridge.data.local.database.BridgeDatabase/3.json")
        val database = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                for (entity in database["entities"]!!.jsonArray) {
                    val e = entity.jsonObject
                    val table = e["tableName"]!!.jsonPrimitive.content
                    db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    e["indices"]?.jsonArray?.forEach { index ->
                        db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    }
                }
                database["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL(
                    """
                    INSERT INTO messages (id, serverId, deviceId, groupName, senderName, messageText, timestamp, mediaType,
                        mediaStatus, fingerprint, uploadStatus, createdAt, packageName, notificationKey, deletedAt, purgedAt)
                    VALUES ('m1', NULL, 'OKB-ANDROID-A82F19', 'NMDEO FLOOD MONITORING', 'Juan', 'Flood at Mel Lopez Blvd 0.45m',
                        1000, 'TEXT', 'NONE', 'fp1', 'PENDING_UPLOAD', 1000, 'com.whatsapp', NULL, NULL, NULL),
                        ('m2', NULL, 'OKB-ANDROID-A82F19', 'NMDEO FLOOD MONITORING', 'Ana', 'Binned message',
                        2000, 'TEXT', 'NONE', 'fp2', 'UPLOADED', 2000, 'com.whatsapp', NULL, 5000, NULL)
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO bridge_settings (`key`, value, updatedAt) VALUES ('backend_url', 'https://okb.test', 1)")
                db.execSQL(
                    "INSERT INTO monitored_groups (name, normalizedName, authorized, discoveredAutomatically, createdAt) " +
                        "VALUES ('NMDEO FLOOD MONITORING', 'nmdeo flood monitoring', 1, 0, 1)",
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(dbFile.absolutePath).callback(callback).build(),
        )
        helper.writableDatabase.close()
        helper.close()
    }

    @Test
    fun `v3 data survives and the delivery queue is usable after migrating to v4`() = runTest {
        createV3FromExportedSchema()
        val db = Room.databaseBuilder(context, BridgeDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("Flood at Mel Lopez Blvd 0.45m", db.messageDao().getById("m1")!!.messageText)
            assertEquals(5000L, db.messageDao().getById("m2")!!.deletedAt)
            assertEquals(1, db.messageDao().observeBinCount().first())
            assertEquals("https://okb.test", RoomSettingsRepository(db.settingsDao()).current().backendUrl)
            assertEquals(listOf("NMDEO FLOOD MONITORING"), RoomGroupRepository(db.groupDao()).authorizedGroupNames())

            val repo = RoomConsolidatedDeliveryRepository(db.consolidatedDeliveryDao())
            assertNull(repo.get("5f0c6a2e-1b7d"))
            repo.save(
                ConsolidatedReportDelivery(
                    id = "5f0c6a2e-1b7d", kind = "scheduled", fileName = "r.pdf", caption = "c", sourceGroup = "S",
                    destinationGroup = "D", periodStart = null, periodEnd = null, reportCount = 1, pdfPath = "p",
                    status = ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, createdAt = 1, updatedAt = 1,
                ),
            )
            assertEquals(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, repo.get("5f0c6a2e-1b7d")!!.status)
            assertEquals(1, repo.observeCounts().first().ready)
        } finally {
            db.close()
        }
    }

    @Test
    fun `source and destination groups are stored as separate settings and survive reopening`() = runTest {
        val db = Room.databaseBuilder(context, BridgeDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val settings = RoomSettingsRepository(db.settingsDao())
            settings.setSourceGroupName("  NMDEO FLOOD MONITORING ")
            settings.setDestinationGroupName("OKB COMMAND CENTER")
            assertEquals("NMDEO FLOOD MONITORING", settings.getSourceGroupName())
            assertEquals("OKB COMMAND CENTER", settings.getDestinationGroupName())
            val keys = db.settingsDao().getAll().associate { it.key to it.value }
            assertEquals("NMDEO FLOOD MONITORING", keys["source_group_name"])
            assertEquals("OKB COMMAND CENTER", keys["destination_group_name"])
        } finally {
            db.close()
        }
    }
}
