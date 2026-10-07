package com.okb.whatsappbridge.automation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenUnlockerTest {

    private class Phone(var locked: Boolean = true, val secure: Boolean = false, val dismissWorks: Boolean = false, val unlockAfterSwipes: Int = 1) {
        var dismissCalls = 0
        var swipes = 0
        fun unlocker() = ScreenUnlocker(
            isLocked = { locked },
            isSecure = { secure },
            dismiss = { dismissCalls++; if (dismissWorks) locked = false; dismissWorks },
            swipeUp = { swipes++; if (swipes >= unlockAfterSwipes) locked = false; true },
            sleep = {},
        )
    }

    @Test
    fun `not locked - nothing to do`() = runTest {
        val phone = Phone(locked = false)
        assertNull(phone.unlocker().unlock())
        assertEquals(0, phone.dismissCalls)
        assertEquals(0, phone.swipes)
    }

    @Test
    fun `Android's dismiss request works - no gesture`() = runTest {
        val phone = Phone(dismissWorks = true)
        assertNull(phone.unlocker().unlock())
        assertEquals(0, phone.swipes)
    }

    @Test
    fun `dismiss request ignored (Xiaomi swipe lock screen) - swiped away`() = runTest {
        val phone = Phone(dismissWorks = false, unlockAfterSwipes = 2)
        assertNull(phone.unlocker().unlock())
        assertEquals(1, phone.dismissCalls)
        assertEquals(2, phone.swipes)
    }

    @Test
    fun `still locked after every swipe - clear reason with the Xiaomi permissions`() = runTest {
        val phone = Phone(dismissWorks = false, unlockAfterSwipes = 99)
        assertEquals(ScreenUnlocker.NOT_DISMISSED, phone.unlocker().unlock())
        assertEquals(ScreenUnlocker.SWIPE_ATTEMPTS, phone.swipes)
    }

    @Test
    fun `PIN or pattern - never attempted`() = runTest {
        val phone = Phone(secure = true)
        assertEquals(ScreenUnlocker.SECURE_LOCK, phone.unlocker().unlock())
        assertEquals(0, phone.dismissCalls)
        assertEquals(0, phone.swipes)
    }
}
