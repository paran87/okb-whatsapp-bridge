package com.okb.whatsappbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.okb.whatsappbridge.OkbBridgeApplication
import com.okb.whatsappbridge.worker.ConsolidatedReportWorker
import kotlinx.coroutines.launch

/**
 * The alarm for the next date of sending fired: run the report check at once in the app (the monitoring
 * service keeps the process alive; a wake lock keeps the CPU on). The expedited work request is the fallback
 * when the process cannot run it; both share one lock, so the report is never handled twice at once.
 */
class ReportAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val container = (context.applicationContext as OkbBridgeApplication).container
        container.logger.info("TextReport", "Report check alarm fired")
        container.reportKeepAwake.renew(5L * 60_000)
        container.appScope.launch { runCatching { container.consolidatedReports() } }
        ConsolidatedReportWorker.runNow(container.workManager, "alarm")
    }

    companion object {
        const val ACTION = "com.okb.whatsappbridge.action.REPORT_CHECK"
    }
}
