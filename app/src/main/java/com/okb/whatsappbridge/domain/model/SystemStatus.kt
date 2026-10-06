package com.okb.whatsappbridge.domain.model

/** Snapshot of real Android system state relevant to background reliability. */
data class SystemStatus(
    val notificationAccessGranted: Boolean,
    val listenerConnected: Boolean,
    val installedWhatsAppPackages: List<String>,
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean?,
    val standbyBucket: String?,
    val powerSaveMode: Boolean,
    val appNotificationsEnabled: Boolean,
    val networkAvailable: Boolean,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    /** Viber is optional: only needed when Viber groups are monitored. */
    val installedViberPackages: List<String> = emptyList(),
) {
    val whatsAppInstalled: Boolean get() = installedWhatsAppPackages.isNotEmpty()
}

/** Effective monitoring state, derived from the user setting *and* actual system state. */
enum class MonitoringState {
    /** Enabled, Notification Access granted, and the listener is bound by Android. */
    ACTIVE,

    /** The user paused monitoring. */
    PAUSED,

    /** Enabled, but Android Notification Access is not granted. */
    NO_ACCESS,

    /** Enabled and access granted, but Android has not (re)bound the listener in this process. */
    LISTENER_DISCONNECTED;

    companion object {
        fun from(enabled: Boolean, accessGranted: Boolean, listenerConnected: Boolean): MonitoringState = when {
            !enabled -> PAUSED
            !accessGranted -> NO_ACCESS
            !listenerConnected -> LISTENER_DISCONNECTED
            else -> ACTIVE
        }
    }
}

interface SystemStatusProvider {
    fun snapshot(): SystemStatus
    fun isNotificationAccessGranted(): Boolean
    fun isListenerConnected(): Boolean
    /** Asks Android to rebind the listener (supported API; it does not bypass user consent). */
    fun requestListenerRebind(): Boolean

    /**
     * Disables and re-enables the app's own listener component, which makes Android's notification
     * service bind it again. Used only when [requestListenerRebind] did not help. Access stays as granted.
     */
    fun resetListenerComponent(): Boolean = false
}

/** What unattended (screen off, operator asleep) automatic TEXT sending needs on this phone. */
data class AutomationReadiness(
    /** The operator enabled the OKB accessibility service in Android settings. */
    val accessibilityEnabled: Boolean = false,
    /** Android has the service running right now. */
    val accessibilityConnected: Boolean = false,
    /** "Alarms & reminders" allowed: the cut-off alarm is exact. */
    val exactAlarmsAllowed: Boolean = false,
    /** A PIN, pattern or password is set: Android never lets an app unlock it, so a locked phone cannot send. */
    val secureLockScreen: Boolean = false,
    val nextCheckAt: Long? = null,
    val nextCheckExact: Boolean = false,
) {
    val ready: Boolean get() = accessibilityEnabled && accessibilityConnected && exactAlarmsAllowed && !secureLockScreen
}
