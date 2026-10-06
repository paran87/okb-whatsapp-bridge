package com.okb.whatsappbridge.util.logging

import android.util.Log
import com.okb.whatsappbridge.data.local.dao.EventLogDao
import com.okb.whatsappbridge.data.local.entity.BridgeEventLogEntity
import com.okb.whatsappbridge.util.security.Redactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Diagnostic logger. Writes to Logcat and to the local event log (Diagnostics → View Logs).
 *
 * Callers must never pass message content, tokens or passwords; [Redactor] is applied as a safety net.
 */
interface BridgeLogger {
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

class RoomBridgeLogger(
    private val dao: EventLogDao,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : BridgeLogger {

    override fun info(tag: String, message: String) = write("INFO", tag, message, null)
    override fun warn(tag: String, message: String) = write("WARN", tag, message, null)
    override fun error(tag: String, message: String, throwable: Throwable?) = write("ERROR", tag, message, throwable)

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val safe = Redactor.redact(
            if (throwable != null) "$message (${throwable.javaClass.simpleName}: ${throwable.message})" else message,
        )
        // One Logcat tag for the whole bridge (filter: tag:OKBBridge); the area is kept in the message.
        val line = "[$tag] $safe"
        when (level) {
            "ERROR" -> Log.e(LOGCAT_TAG, line)
            "WARN" -> Log.w(LOGCAT_TAG, line)
            else -> Log.i(LOGCAT_TAG, line)
        }
        scope.launch {
            runCatching {
                val id = dao.insert(BridgeEventLogEntity(timestamp = clock(), level = level, tag = tag, message = safe))
                if (id % PRUNE_EVERY == 0L) dao.prune(KEEP_ENTRIES)
            }
        }
    }

    companion object {
        const val LOGCAT_TAG = "OKBBridge"
        const val KEEP_ENTRIES = 1000
        private const val PRUNE_EVERY = 100L
    }
}
