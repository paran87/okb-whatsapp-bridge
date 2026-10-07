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
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus
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
 * v5 → v6 (automatic PDF sending). The v5 database is created from Room's exported v5 schema with a PDF row,
 * then opened with the real BridgeDatabase (Room validates the migrated schema against the v6 entities).
 */
@RunWith(AndroidJUnit4::class)
class PdfAutoSendMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile = File.createTempFile("okb-migration-v5", ".db").also { it.delete() }

    @After
    fun cleanup() {
        dbFile.delete()
    }

    private fun createV5() {
        val schema = File("schemas/com.okb.whatsappbridge.data.local.database.BridgeDatabase/5.json")
        val database = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject
        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
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

    @Test
    fun `v5 PDF rows survive and keep the automatic sending state after migrating to v6`() = runTest {
        createV5()
        val db = Room.databaseBuilder(context, BridgeDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = RoomConsolidatedDeliveryRepository(db.consolidatedDeliveryDao())
            val row = repo.get("r1")!!
            assertEquals(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, row.status)
            assertEquals(0, row.autoAttempts)
            assertFalse(row.autoPressed)
            assertFalse(row.sentAutomatically)

            repo.save(row.copy(autoAttempts = 2, autoPressed = true))
            assertEquals(listOf("r1"), repo.withStatus(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP).map { it.id })
            repo.save(repo.get("r1")!!.copy(status = ConsolidatedDeliveryStatus.SENT, sentAutomatically = true))
            val sent = repo.get("r1")!!
            assertEquals(2, sent.autoAttempts)
            assertTrue(sent.autoPressed)
            assertTrue(sent.sentAutomatically)
            assertTrue(repo.withStatus(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP).isEmpty())
        } finally {
            db.close()
        }
    }
}
