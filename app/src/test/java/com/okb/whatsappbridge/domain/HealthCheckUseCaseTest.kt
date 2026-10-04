package com.okb.whatsappbridge.domain

import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.MonitoringState
import com.okb.whatsappbridge.domain.usecase.BackendUseCases
import com.okb.whatsappbridge.domain.usecase.DeviceInfo
import com.okb.whatsappbridge.domain.usecase.HealthCheckUseCase
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.data.repository.SecureDeviceIdentityRepository
import com.okb.whatsappbridge.fakes.FakeAlerts
import com.okb.whatsappbridge.fakes.FakeBridgeApi
import com.okb.whatsappbridge.fakes.FakeMessageRepository
import com.okb.whatsappbridge.fakes.FakeScheduler
import com.okb.whatsappbridge.fakes.FakeSettingsRepository
import com.okb.whatsappbridge.fakes.FakeSystemStatus
import com.okb.whatsappbridge.fakes.RecordingLogger
import com.okb.whatsappbridge.util.security.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthCheckUseCaseTest {

    private val settings = FakeSettingsRepository(BridgeSettings(monitoringEnabled = true, backendUrl = "https://okb.test"))
    private val messages = FakeMessageRepository()
    private val system = FakeSystemStatus()
    private val scheduler = FakeScheduler()
    private val alerts = FakeAlerts()
    private val api = FakeBridgeApi()
    private val backend = BackendUseCases(
        settings, SecureDeviceIdentityRepository(InMemorySecretStore()), api,
        DeviceInfo("1.0", "Android 14", "Test", "Phone"), RecordingLogger(),
    )
    private val useCase = HealthCheckUseCase(settings, messages, system, scheduler, backend, alerts, RecordingLogger(), clock = { 1_000L })

    @Test
    fun `healthy bridge clears alerts and records the check`() = runTest {
        val report = useCase()
        assertEquals(MonitoringState.ACTIVE, report.monitoringState)
        assertEquals(1, alerts.cleared)
        assertEquals(0, system.rebindRequests)
        assertEquals(1_000L, settings.state.value.lastHealthCheckAt)
        assertEquals(true, settings.state.value.lastBackendCheckOk)
        assertEquals(1, messages.repaired)
    }

    @Test
    fun `disconnected listener with access granted requests a single rebind and alerts`() = runTest {
        system.connected = false
        val report = useCase()
        assertEquals(MonitoringState.LISTENER_DISCONNECTED, report.monitoringState)
        assertTrue(report.rebindRequested)
        assertEquals(1, system.rebindRequests)
        assertEquals(listOf(MonitoringState.LISTENER_DISCONNECTED), alerts.raised)
    }

    @Test
    fun `revoked access alerts the operator and never tries to rebind`() = runTest {
        system.accessGranted = false
        system.connected = false
        val report = useCase()
        assertEquals(MonitoringState.NO_ACCESS, report.monitoringState)
        assertEquals(0, system.rebindRequests)
        assertEquals(listOf(MonitoringState.NO_ACCESS), alerts.raised)
    }

    @Test
    fun `pending messages trigger a reconciliation upload`() = runTest {
        messages.uploadable = 3
        val report = useCase()
        assertTrue(report.uploadScheduled)
        assertEquals(listOf(SyncTrigger.RECONCILE), scheduler.requests)
    }

    @Test
    fun `no upload is scheduled while sync is paused`() = runTest {
        settings.setSyncPaused(true)
        messages.uploadable = 3
        useCase()
        assertTrue(scheduler.requests.isEmpty())
    }
}
