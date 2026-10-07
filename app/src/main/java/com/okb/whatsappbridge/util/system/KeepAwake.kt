package com.okb.whatsappbridge.util.system

import android.content.Context
import android.os.PowerManager

/**
 * A partial wake lock (CPU only, screen stays off) that is renewed for a short time on every use. The report
 * watcher renews it before each 30-second poll, so it keeps running with the screen off; if the loop stops,
 * the lock expires on its own within [holdMs].
 */
class KeepAwake(context: Context, tag: String) {
    private val lock: PowerManager.WakeLock? = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        ?.apply { setReferenceCounted(false) }

    fun renew(holdMs: Long = DEFAULT_HOLD_MS) {
        runCatching { lock?.acquire(holdMs) }
    }

    fun release() {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }

    companion object {
        const val DEFAULT_HOLD_MS = 2L * 60_000
    }
}
