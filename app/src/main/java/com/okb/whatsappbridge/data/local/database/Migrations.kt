package com.okb.whatsappbridge.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history. Destructive migration is never used: it would drop messages or acquired media that
 * have not been uploaded yet. Each migration is additive and preserves existing rows.
 */
object Migrations {

    /** v1 → v2 (Phase 2): adds the media and media_upload_queue tables. Messages are untouched. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `media` (
                    `id` TEXT NOT NULL,
                    `messageId` TEXT NOT NULL,
                    `deviceId` TEXT NOT NULL,
                    `groupName` TEXT,
                    `senderName` TEXT,
                    `mediaType` TEXT NOT NULL,
                    `mimeType` TEXT,
                    `originalFileName` TEXT,
                    `localPath` TEXT,
                    `fileSizeBytes` INTEGER,
                    `sha256` TEXT,
                    `acquisitionStatus` TEXT NOT NULL,
                    `uploadStatus` TEXT NOT NULL,
                    `statusDetail` TEXT,
                    `uploadAttempts` INTEGER NOT NULL,
                    `lastError` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `r2ObjectKey` TEXT,
                    `r2ETag` TEXT,
                    `remoteRef` TEXT,
                    `uploadedAt` INTEGER DEFAULT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_messageId` ON `media` (`messageId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_sha256` ON `media` (`sha256`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_acquisitionStatus` ON `media` (`acquisitionStatus`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_uploadStatus` ON `media` (`uploadStatus`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_createdAt` ON `media` (`createdAt`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `media_upload_queue` (
                    `mediaId` TEXT NOT NULL,
                    `enqueuedAt` INTEGER NOT NULL,
                    `attemptCount` INTEGER NOT NULL,
                    `lastAttemptAt` INTEGER,
                    `lastError` TEXT,
                    PRIMARY KEY(`mediaId`),
                    FOREIGN KEY(`mediaId`) REFERENCES `media`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
