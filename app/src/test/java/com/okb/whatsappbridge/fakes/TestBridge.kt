package com.okb.whatsappbridge.fakes

import android.content.Context
import androidx.room.Room
import com.okb.whatsappbridge.data.local.database.BridgeDatabase
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.repository.RoomGroupRepository
import com.okb.whatsappbridge.data.repository.RoomMessageRepository
import com.okb.whatsappbridge.data.repository.RoomSettingsRepository
import com.okb.whatsappbridge.data.repository.SecureDeviceIdentityRepository
import com.okb.whatsappbridge.domain.usecase.ProcessNotificationUseCase
import com.okb.whatsappbridge.domain.usecase.SyncMessagesUseCase
import com.okb.whatsappbridge.util.security.InMemorySecretStore
import java.time.ZoneId

/** Real Room-backed pipeline wired like AppContainer, with a test API and scheduler. */
class TestBridge(context: Context, api: BridgeApi, clock: () -> Long = System::currentTimeMillis) {
    val db: BridgeDatabase = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val identity = SecureDeviceIdentityRepository(InMemorySecretStore())
    val settings = RoomSettingsRepository(db.settingsDao())
    val groups = RoomGroupRepository(db.groupDao())
    val messages = RoomMessageRepository(db.messageDao(), db.settingsDao(), identity)
    val scheduler = FakeScheduler()
    val logger = RecordingLogger()
    val process = ProcessNotificationUseCase(settings, groups, messages, scheduler, clock = clock)
    val sync = SyncMessagesUseCase(
        settings, messages, identity, api, logger,
        clock = clock,
        zoneId = { ZoneId.of("Asia/Manila") },
    )
}
