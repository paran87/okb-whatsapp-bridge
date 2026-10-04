package com.okb.whatsappbridge.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.data.local.database.Migrations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the v1→v2 (Phase 2) migration preserves existing messages and adds the media tables. The
 * migration is applied directly to a v1-shaped database so the test is self-contained (no schema
 * assets required). Destructive migration is never used, so no message/media loss is possible.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbFile = File.createTempFile("okb-migration", ".db").also { it.delete() }

    @After
    fun cleanup() {
        dbFile.delete()
    }

    private fun openV1(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                // Minimal v1 shape needed for this test: the messages table (media's FK target).
                db.execSQL(
                    """
                    CREATE TABLE messages (
                        id TEXT NOT NULL PRIMARY KEY, serverId TEXT, deviceId TEXT NOT NULL, groupName TEXT,
                        senderName TEXT, messageText TEXT, timestamp INTEGER NOT NULL, mediaType TEXT NOT NULL,
                        mediaStatus TEXT NOT NULL, fingerprint TEXT NOT NULL, uploadStatus TEXT NOT NULL,
                        createdAt INTEGER NOT NULL, packageName TEXT NOT NULL, notificationKey TEXT,
                        uploadedAt INTEGER DEFAULT NULL, lastError TEXT DEFAULT NULL)
                    """.trimIndent(),
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
            .name(dbFile.absolutePath)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun migrate1To2_keepsMessages_andAddsMediaTables() {
        openV1().use { db ->
            val fp = "a".repeat(64)
            db.execSQL(
                "INSERT INTO messages (id, serverId, deviceId, groupName, senderName, messageText, timestamp, " +
                    "mediaType, mediaStatus, fingerprint, uploadStatus, createdAt, packageName, notificationKey) " +
                    "VALUES ('m1', NULL, 'OKB-ANDROID-A82F19', 'G', 'Ana', 'hi', 1000, 'IMAGE', 'UNAVAILABLE', '$fp', " +
                    "'PENDING_UPLOAD', 1010, 'com.whatsapp', 'k')",
            )

            Migrations.MIGRATION_1_2.migrate(db)

            // Message survived.
            db.query("SELECT COUNT(*) FROM messages WHERE id = 'm1'").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            // Media tables exist and enforce the cascade FK.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO media (id, messageId, deviceId, groupName, senderName, mediaType, mimeType, " +
                    "originalFileName, localPath, fileSizeBytes, sha256, acquisitionStatus, uploadStatus, statusDetail, " +
                    "uploadAttempts, lastError, createdAt, updatedAt, r2ObjectKey, r2ETag, remoteRef, uploadedAt) " +
                    "VALUES ('md1','m1','d','G','Ana','IMAGE','image/jpeg',NULL,NULL,NULL,NULL,'DETECTED','PENDING',NULL,0,NULL,1,1,NULL,NULL,NULL,NULL)",
            )
            db.execSQL("INSERT INTO media_upload_queue (mediaId, enqueuedAt, attemptCount) VALUES ('md1', 1, 0)")
            db.query("SELECT COUNT(*) FROM media").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }

            // Deleting the message cascades to its media rows.
            db.execSQL("DELETE FROM messages WHERE id = 'm1'")
            db.query("SELECT COUNT(*) FROM media").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM media_upload_queue").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }
}
