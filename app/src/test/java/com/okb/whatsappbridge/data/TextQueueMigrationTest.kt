package com.okb.whatsappbridge.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.automation.MessagePart
import com.okb.whatsappbridge.data.local.database.BridgeDatabase
import com.okb.whatsappbridge.data.local.database.Migrations
import com.okb.whatsappbridge.data.repository.RoomConsolidatedDeliveryRepository
import com.okb.whatsappbridge.data.repository.RoomTextDeliveryRepository
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus
import com.okb.whatsappbridge.domain.model.TextDeliveryStatus
import com.okb.whatsappbridge.domain.model.TextReportDelivery
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v4 → v5 (automatic TEXT report queue). The v4 database is created from Room's exported v4 schema with real rows,
 * then opened with the real BridgeDatabase (Room validates the migrated schema against the v5 entities).
 */
@RunWith(AndroidJUnit4::class)
class TextQueueMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile = File.createTempFile("okb-migration-v4", ".db").also { it.delete() }

    @After
    fun cleanup() {
        dbFile.delete()
    }

    private fun createV4() {
        val schema = File("schemas/com.okb.whatsappbridge.data.local.database.BridgeDatabase/4.json")
        val database = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject
        val callback = object : SupportSQLiteOpenHelper.Callback(4) {
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
                    "INSERT INTO messages (id, serverId, deviceId, groupName, senderName, messageText, timestamp, mediaType, " +
                        "mediaStatus, fingerprint, uploadStatus, createdAt, packageName, notificationKey, deletedAt, purgedAt) " +
                        "VALUES ('m1', NULL, 'OKB-ANDROID-A82F19', 'NMDEO FLOOD MONITORING', 'Juan', 'Flood 0.45m', 1000, 'TEXT', " +
                        "'NONE', 'fp1', 'PENDING_UPLOAD', 1000, 'com.whatsapp', NULL, NULL, NULL)",
                )
                db.execSQL(
                    "INSERT INTO consolidated_report_deliveries (id, kind, fileName, caption, sourceGroup, destinationGroup, " +
                        "periodStart, periodEnd, reportCount, pdfPath, status, errorMessage, downloadAttempts, pendingAck, " +
                        "pendingAckError, createdAt, updatedAt, downloadedAt, openedAt, sentAt) VALUES ('r1', 'scheduled', " +
                        "'r.pdf', 'c', 'S', 'D', NULL, NULL, 1, 'p', 'READY_FOR_WHATSAPP', NULL, 0, NULL, NULL, 1, 1, 1, NULL, NULL)",
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

    private fun delivery(id: String, key: String) = TextReportDelivery(
        id = id, reportId = "r1", kind = "scheduled", dedupeKey = key, destinationGroup = "OKB COMMAND CENTER",
        sourceGroup = "NMDEO FLOOD MONITORING", parts = listOf(MessagePart("📋 report\nRef: OKB-1", "OKB-1")),
        periodStart = null, periodEnd = null, reportCount = 1, status = TextDeliveryStatus.SCHEDULED, createdAt = 1, updatedAt = 1,
    )

    @Test
    fun `v4 data survives and the TEXT queue works after migrating to v5`() = runTest {
        createV4()
        val db = Room.databaseBuilder(context, BridgeDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("Flood 0.45m", db.messageDao().getById("m1")!!.messageText)
            assertEquals(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, RoomConsolidatedDeliveryRepository(db.consolidatedDeliveryDao()).get("r1")!!.status)

            val text = RoomTextDeliveryRepository(db.textDeliveryDao())
            assertTrue(text.insert(delivery("t1", "scheduled|p|okb command center")))
            // The same period and group can never be queued a second time.
            assertFalse(text.insert(delivery("t2", "scheduled|p|okb command center")))
            val stored = text.get("t1")!!
            assertEquals(listOf(MessagePart("📋 report\nRef: OKB-1", "OKB-1")), stored.parts)
            text.update(stored.copy(status = TextDeliveryStatus.SENT, sentRefs = setOf("OKB-1"), pressedRefs = setOf("OKB-1"), pendingResult = "sent"))
            val sent = text.get("t1")!!
            assertEquals(TextDeliveryStatus.SENT, sent.status)
            assertEquals(setOf("OKB-1"), sent.sentRefs)
            assertEquals(listOf("t1"), text.withPendingResult().map { it.id })
        } finally {
            db.close()
        }
    }
}
