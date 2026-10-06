package com.okb.whatsappbridge.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.okb.whatsappbridge.domain.usecase.ReportWakeScheduler
import com.okb.whatsappbridge.service.ReportAlarmReceiver
import com.okb.whatsappbridge.util.logging.BridgeLogger

/**
 * Wakes the phone just after each scheduled cut-off (6:00 AM, 6:00 PM, 12:00 AM by default, as reported by the
 * backend) so the consolidated TEXT report goes out on time even in Doze, instead of whenever the 15-minute
 * periodic check is allowed to run. Uses an exact alarm when "Alarms & reminders" is allowed for the app, else
 * an inexact one (may be late by several minutes in Doze; the checklist asks the operator to allow it).
 */
class AlarmReportWakeScheduler(
    private val context: Context,
    private val logger: BridgeLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReportWakeScheduler {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun scheduleNext(nextCutoffAtMillis: Long?, nextRetryAtMillis: Long?) {
        prefs.edit().putInt(KEY_FAILURES, 0).apply()
        val candidates = listOfNotNull(
            nextCutoffAtMillis?.plus(AFTER_CUTOFF_MS),
            nextRetryAtMillis?.plus(AFTER_RETRY_MS),
        ).filter { it > clock() }
        val at = candidates.minOrNull() ?: return
        set(at, if (at == nextRetryAtMillis?.plus(AFTER_RETRY_MS)) "text report retry" else "next scheduled report")
    }

    override fun scheduleRetry() {
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        prefs.edit().putInt(KEY_FAILURES, failures).apply()
        // Bounded: after MAX_RETRIES quick retries the 15-minute check (and the next cut-off alarm) take over.
        if (failures > MAX_RETRIES) return
        set(clock() + RETRY_AFTER_FAILURE_MS, "backend unreachable, retry $failures/$MAX_RETRIES")
    }

    private fun set(at: Long, reason: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, ReportAlarmReceiver::class.java).setAction(ReportAlarmReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exact = canScheduleExact(context)
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            prefs.edit().putLong(KEY_NEXT_AT, at).putBoolean(KEY_NEXT_EXACT, exact).apply()
            logger.info(TAG, "Report check alarm set (${if (exact) "exact" else "inexact"}) in ${(at - clock()) / 60_000} min: $reason")
        } catch (e: SecurityException) {
            logger.warn(TAG, "Report check alarm not set: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "TextReport"
        private const val PREFS = "okb_report_alarm"
        private const val KEY_FAILURES = "failures"
        private const val KEY_NEXT_AT = "next_at"
        private const val KEY_NEXT_EXACT = "next_exact"
        private const val REQUEST_CODE = 4100
        /** Reports finish AI processing before the cut-off; one minute after it the backend can build the report. */
        const val AFTER_CUTOFF_MS = 60_000L
        const val AFTER_RETRY_MS = 5_000L
        const val RETRY_AFTER_FAILURE_MS = 4L * 60_000
        const val MAX_RETRIES = 6

        fun canScheduleExact(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        }

        /** Next alarm time and whether it is exact, for the app's status screen. */
        fun nextAlarm(context: Context): Pair<Long, Boolean>? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val at = prefs.getLong(KEY_NEXT_AT, 0L).takeIf { it > 0 } ?: return null
            return at to prefs.getBoolean(KEY_NEXT_EXACT, false)
        }
    }
}
