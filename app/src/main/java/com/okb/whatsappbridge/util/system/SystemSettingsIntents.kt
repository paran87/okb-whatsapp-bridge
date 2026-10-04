package com.okb.whatsappbridge.util.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.okb.whatsappbridge.service.WhatsAppNotificationListenerService

/**
 * Opens Android settings screens on the user's request. The bridge never changes these settings
 * itself – the operator has to make every change.
 */
object SystemSettingsIntents {

    /** Settings → Notification Access (the per-app detail page where supported). */
    fun openNotificationAccess(context: Context): Boolean {
        val component = ComponentName(context, WhatsAppNotificationListenerService::class.java)
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString()),
                )
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        return startFirst(context, candidates)
    }

    /** Battery optimization list, falling back to this app's details page ("Battery → Unrestricted"). */
    fun openBatteryOptimization(context: Context): Boolean = startFirst(
        context,
        listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(context.packageName)),
    )

    fun openAppDetails(context: Context, packageName: String = context.packageName): Boolean =
        startFirst(context, listOf(appDetails(packageName)))

    /** Notification settings of another app, e.g. to verify WhatsApp notifications are enabled. */
    fun openAppNotificationSettings(context: Context, packageName: String): Boolean = startFirst(
        context,
        listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            appDetails(packageName),
        ),
    )

    private fun appDetails(packageName: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    private fun startFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next, more generic screen.
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}
