package com.okb.whatsappbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.okb.whatsappbridge.OkbBridgeApplication
import com.okb.whatsappbridge.worker.ConsolidatedReportWorker

/** The cut-off alarm fired: run the consolidated-report check now (expedited work, survives Doze). */
class ReportAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val container = (context.applicationContext as OkbBridgeApplication).container
        container.logger.info("TextReport", "Report check alarm fired")
        ConsolidatedReportWorker.runNow(container.workManager, "alarm")
    }

    companion object {
        const val ACTION = "com.okb.whatsappbridge.action.REPORT_CHECK"
    }
}
