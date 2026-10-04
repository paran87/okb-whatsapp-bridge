package com.okb.whatsappbridge.domain

import com.okb.whatsappbridge.domain.model.SystemStatus
import com.okb.whatsappbridge.domain.model.SystemStatusProvider
import com.okb.whatsappbridge.domain.usecase.ListenerRecoveryOutcome
import com.okb.whatsappbridge.domain.usecase.ListenerRecoveryUseCase
import com.okb.whatsappbridge.fakes.RecordingLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenerRecoveryUseCaseTest {

    /** Simulates Android: the listener (re)connects after a rebind request or a component reset, or never. */
    private class FakeSystem(
        var accessGranted: Boolean = true,
        var connected: Boolean = false,
        val connectOnRebind: Boolean = false,
        val connectOnReset: Boolean = false,
    ) : SystemStatusProvider {
        var rebinds = 0
        var resets = 0
        override fun isNotificationAccessGranted() = accessGranted
        override fun isListenerConnected() = connected
        override fun requestListenerRebind(): Boolean { rebinds++; if (connectOnRebind) connected = true; return true }
        override fun resetListenerComponent(): Boolean { resets++; if (connectOnReset) connected = true; return true }
        override fun snapshot(): SystemStatus = throw UnsupportedOperationException()
    }

    private var now = 0L
    private val sleeps = mutableListOf<Long>()
    private fun recovery(system: FakeSystem) =
        ListenerRecoveryUseCase(system, RecordingLogger(), clock = { now }, sleep = { sleeps += it; now += it })

    @Test
    fun `nothing to do when access is missing or the listener is connected`() = runTest {
        val noAccess = FakeSystem(accessGranted = false)
        assertEquals(ListenerRecoveryOutcome.NO_ACCESS, recovery(noAccess)("test"))
        val connected = FakeSystem(connected = true)
        assertEquals(ListenerRecoveryOutcome.ALREADY_CONNECTED, recovery(connected)("test"))
        assertEquals(0, noAccess.rebinds + connected.rebinds + noAccess.resets + connected.resets)
    }

    @Test
    fun `a rebind request is tried first`() = runTest {
        val system = FakeSystem(connectOnRebind = true)
        assertEquals(ListenerRecoveryOutcome.RECONNECTED, recovery(system)("app opened"))
        assertEquals(1, system.rebinds)
        assertEquals(0, system.resets)
    }

    @Test
    fun `after a force stop the component is reset when a rebind is not enough`() = runTest {
        val system = FakeSystem(connectOnReset = true)
        assertEquals(ListenerRecoveryOutcome.RECONNECTED, recovery(system)("app opened"))
        assertEquals(1, system.rebinds)
        assertEquals(1, system.resets)
    }

    @Test
    fun `component reset is rate-limited, never a loop`() = runTest {
        val system = FakeSystem()
        val useCase = recovery(system)
        assertEquals(ListenerRecoveryOutcome.STILL_DISCONNECTED, useCase("first"))
        assertEquals(ListenerRecoveryOutcome.STILL_DISCONNECTED, useCase("second"))
        assertEquals(2, system.rebinds)
        assertEquals("second attempt within the cooldown does not reset again", 1, system.resets)
        now += 60_000
        useCase("later")
        assertEquals(2, system.resets)
    }
}
