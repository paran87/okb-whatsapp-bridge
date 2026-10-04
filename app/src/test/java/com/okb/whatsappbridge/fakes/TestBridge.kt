package com.okb.whatsappbridge.fakes

import android.content.Context
import androidx.room.Room
import com.okb.whatsappbridge.data.local.database.BridgeDatabase
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.repository.RoomGroupRepository
import com.okb.whatsappbridge.data.repository.RoomMediaRepository
import com.okb.whatsappbridge.data.repository.RoomMessageRepository
import com.okb.whatsappbridge.data.repository.RoomSettingsRepository
import com.okb.whatsappbridge.data.repository.SecureDeviceIdentityRepository
import com.okb.whatsappbridge.domain.usecase.AcquireMediaUseCase
import com.okb.whatsappbridge.domain.usecase.ProcessNotificationUseCase
import com.okb.whatsappbridge.domain.usecase.SyncMediaUseCase
import com.okb.whatsappbridge.domain.usecase.SyncMessagesUseCase
import com.okb.whatsappbridge.util.media.FileSystemMediaFileStore
import com.okb.whatsappbridge.util.security.InMemorySecretStore
import java.io.File
import java.time.ZoneId

/** Real Room-backed pipeline wired like AppContainer, with a test API and scheduler. */
class TestBridge(
    context: Context,
    api: BridgeApi,
    mediaDir: File = File(System.getProperty("java.io.tmpdir"), "okb-media-" + java.util.UUID.randomUUID()),
    val content: FakeMediaContentAccess = FakeMediaContentAccess(),
    val uploader: FakeMediaUploader = FakeMediaUploader(),
    clock: () -> Long = System::currentTimeMillis,
) {
    val db: BridgeDatabase = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val identity = SecureDeviceIdentityRepository(InMemorySecretStore())
    val settings = RoomSettingsRepository(db.settingsDao())
    val groups = RoomGroupRepository(db.groupDao())
    val messages = RoomMessageRepository(db.messageDao(), db.settingsDao(), identity)
    val mediaStore = FileSystemMediaFileStore(mediaDir)
    val media = RoomMediaRepository(db.mediaDao(), mediaStore)
    val scheduler = FakeScheduler()
    val logger = RecordingLogger()
    val acquireMedia = AcquireMediaUseCase(media, settings, content, mediaStore, scheduler, logger, clock = clock)
    val process = ProcessNotificationUseCase(
        settings, groups, messages, scheduler,
        media = media, acquireMedia = acquireMedia, deviceId = { identity.deviceId() }, clock = clock,
    )
    val sync = SyncMessagesUseCase(
        settings, messages, identity, api, logger,
        clock = clock,
        zoneId = { ZoneId.of("Asia/Manila") },
    )
    val mediaSync = SyncMediaUseCase(
        settings, media, identity, api, uploader, logger,
        clock = clock,
        zoneId = { ZoneId.of("Asia/Manila") },
    )
}
