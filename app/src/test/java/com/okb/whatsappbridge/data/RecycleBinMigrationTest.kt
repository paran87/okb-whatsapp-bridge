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
 * v2 → v3 (Recycle Bin). The v2 database is created from the schema Room exported for v2
 * (app/schemas/.../2.json), then opened with the real BridgeDatabase: Room itself validates that the
 * migrated schema matches the v3 entities, and existing messages must survive untouched.
 */
@RunWith(AndroidJUnit4::class)
class RecycleBinMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile = File.createTempFile("okb-migration-v2", ".db").also { it.delete() }

    @After
    fun cleanup() {
        dbFile.delete()
    }

    private fun createV2FromExportedSchema() {
        val schema = File("schemas/com.okb.whatsappbridge.data.local.database.BridgeDatabase/2.json")
        val database = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
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
                        mediaStatus, fingerprint, uploadStatus, createdAt, packageName, notificationKey)
                    VALUES ('m1', NULL, 'OKB-ANDROID-A82F19', 'OKB Monitoring', 'Juan', 'Flood at Molino', 1000, 'TEXT',
                        'NONE', 'fp1', 'PENDING_UPLOAD', 1000, 'com.whatsapp', NULL)
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO upload_queue (messageId, enqueuedAt, attemptCount) VALUES ('m1', 1000, 0)")
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
    fun `existing messages survive the migration and are not in the Recycle Bin`() = runTest {
        createV2FromExportedSchema()
        val db = Room.databaseBuilder(context, BridgeDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            val row = db.messageDao().getById("m1")!!
            assertEquals("Flood at Molino", row.messageText)
            assertNull(row.deletedAt)
            assertNull(row.purgedAt)
            assertEquals(1, db.messageDao().observeRecent(null, 10).first().size)
            assertEquals(0, db.messageDao().observeBinCount().first())
            assertEquals(1, db.messageDao().countUploadable(includeFailed = false, maxFailedAttempts = 20))
        } finally {
            db.close()
        }
    }
}
