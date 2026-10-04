package com.okb.whatsappbridge.util.system

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.okb.whatsappbridge.domain.model.SystemStatus
import com.okb.whatsappbridge.domain.model.SystemStatusProvider
import com.okb.whatsappbridge.service.ListenerConnectionState
import com.okb.whatsappbridge.service.WhatsAppNotificationListenerService
import com.okb.whatsappbridge.whatsapp.WhatsAppPackages

/** Reads real Android state. Nothing here is inferred from whether the UI is open. */
class AndroidSystemStatusProvider(private val context: Context) : SystemStatusProvider {

    private val listenerComponent = ComponentName(context, WhatsAppNotificationListenerService::class.java)

    override fun isNotificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    override fun isListenerConnected(): Boolean = ListenerConnectionState.connected.value

    override fun requestListenerRebind(): Boolean = try {
        NotificationListenerService.requestRebind(listenerComponent)
        true
    } catch (e: RuntimeException) {
        false
    }

    override fun snapshot(): SystemStatus {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return SystemStatus(
            notificationAccessGranted = isNotificationAccessGranted(),
            listenerConnected = isListenerConnected(),
            installedWhatsAppPackages = WhatsAppPackages.ALL.filter(::isInstalled).sorted(),
            ignoringBatteryOptimizations = power?.isIgnoringBatteryOptimizations(context.packageName) ?: false,
            backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                activityManager?.isBackgroundRestricted
            } else {
                null
            },
            standbyBucket = standbyBucket(),
            powerSaveMode = power?.isPowerSaveMode ?: false,
            appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            networkAvailable = isNetworkAvailable(),
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
        )
    }

    private fun isInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun standbyBucket(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        return when (val bucket = usm.appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "Active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "Rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "Restricted"
            else -> "Bucket $bucket"
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
