package com.okb.whatsappbridge.automation

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.okb.whatsappbridge.domain.model.AutomationReadiness
import com.okb.whatsappbridge.service.WhatsAppAutomationService
import com.okb.whatsappbridge.worker.AlarmReportWakeScheduler

/** Reads the real Android state behind [AutomationReadiness]. */
object AutomationReadinessProbe {
    fun read(context: Context): AutomationReadiness {
        val next = AlarmReportWakeScheduler.nextAlarm(context)
        return AutomationReadiness(
            accessibilityEnabled = WhatsAppAutomationService.isEnabled(context),
            accessibilityConnected = WhatsAppAutomationService.connected.value != null,
            exactAlarmsAllowed = AlarmReportWakeScheduler.canScheduleExact(context),
            secureLockScreen = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true,
            nextCheckAt = next?.first?.takeIf { it > System.currentTimeMillis() },
            nextCheckExact = next?.second == true,
            backgroundUnrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
        )
    }
}
