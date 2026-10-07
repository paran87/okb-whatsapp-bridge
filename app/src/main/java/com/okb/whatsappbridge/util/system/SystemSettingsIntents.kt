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

    /** Settings → Accessibility, where the operator enables "OKB Bridge automatic text reports". */
    fun openAccessibilitySettings(context: Context): Boolean =
        startFirst(context, listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS)))

    /** "Alarms & reminders" for this app (Android 12+), so the cut-off alarm is exact. */
    fun openExactAlarmSettings(context: Context): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)))
            }
            add(appDetails(context.packageName))
        }
        return startFirst(context, candidates)
    }

    /** Settings → Security / Screen lock, to set None or Swipe on the dedicated bridge phone. */
    fun openScreenLockSettings(context: Context): Boolean =
        startFirst(context, listOf(Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS)))

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

    /**
     * Manufacturer "Autostart" / background-launch screen (Xiaomi, Redmi, POCO, Oppo, Realme, Vivo,
     * Huawei, Honor). These phones kill or block apps that are not allowed to auto-start, independently of
     * Android's own battery settings. Falls back to this app's details page.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val vendor = autostartComponents.filter { (brand, _) -> Build.MANUFACTURER.equals(brand, ignoreCase = true) }
            .map { (_, component) -> Intent().setComponent(component) }
        return startFirst(context, vendor + appDetails(context.packageName))
    }

    /** Xiaomi, Redmi and POCO (MIUI / HyperOS) add their own background-start and lock-screen permissions. */
    fun isXiaomi(): Boolean = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    /**
     * MIUI / HyperOS "Other permissions" for this app: "Display pop-up windows while running in the background"
     * (needed to open WhatsApp from the background) and "Show on Lock screen". Falls back to the app details page.
     */
    fun openXiaomiOtherPermissions(context: Context): Boolean = startFirst(
        context,
        listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", context.packageName),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                .putExtra("extra_pkgname", context.packageName),
            appDetails(context.packageName),
        ),
    )

    /** True on manufacturers known to have a separate Autostart permission. */
    fun hasAutostartSettings(): Boolean = autostartComponents.any { (brand, _) -> Build.MANUFACTURER.equals(brand, ignoreCase = true) }

    private val autostartComponents: List<Pair<String, ComponentName>> = listOf(
        "xiaomi" to ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "redmi" to ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "poco" to ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "oppo" to ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        "oppo" to ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        "realme" to ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        "vivo" to ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        "vivo" to ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        "huawei" to ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        "honor" to ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
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
