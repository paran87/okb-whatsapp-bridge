package com.okb.whatsappbridge.ui

import com.okb.whatsappbridge.domain.model.MonitoringState
import com.okb.whatsappbridge.ui.components.StatusLevel
import com.okb.whatsappbridge.whatsapp.WhatsAppPackages
import com.okb.whatsappbridge.worker.SyncWorkerState

/** Maps real state to label + level. Kept in one place so every screen reports the same thing. */
data class Presented(val level: StatusLevel, val label: String, val detail: String? = null)

object StatusPresentation {

    fun monitoring(state: MonitoringState): Presented = when (state) {
        MonitoringState.ACTIVE -> Presented(
            StatusLevel.OK, "Active",
            "WhatsApp notifications are being monitored even when the app is closed.",
        )
        MonitoringState.PAUSED -> Presented(StatusLevel.NEUTRAL, "Paused", "WhatsApp monitoring is currently disabled.")
        MonitoringState.NO_ACCESS -> Presented(
            StatusLevel.ERROR, "Not receiving",
            "Monitoring is enabled, but Android Notification Access is not granted.",
        )
        MonitoringState.LISTENER_DISCONNECTED -> Presented(
            StatusLevel.WARNING, "Waiting for Android",
            "Monitoring is enabled and access is granted, but Android has not connected the listener yet.",
        )
    }

    fun notificationAccess(granted: Boolean?, connected: Boolean): Presented = when {
        granted == null -> Presented(StatusLevel.NEUTRAL, "Checking")
        !granted -> Presented(StatusLevel.ERROR, "Not enabled", "Notification Access is required for background WhatsApp monitoring.")
        connected -> Presented(StatusLevel.OK, "Active", "Listener bound by Android")
        else -> Presented(StatusLevel.WARNING, "Granted", "Access granted; listener not yet connected by Android")
    }

    fun whatsApp(installed: List<String>?, lastNotificationAt: Long?): Presented = when {
        installed == null -> Presented(StatusLevel.NEUTRAL, "Checking")
        installed.isEmpty() -> Presented(StatusLevel.ERROR, "Not installed", "Neither WhatsApp nor WhatsApp Business is installed.")
        else -> Presented(
            StatusLevel.OK, "Detected",
            installed.joinToString { WhatsAppPackages.displayName(it) } +
                if (lastNotificationAt == null) " · no notification received yet" else "",
        )
    }

    /** Viber is optional, so its absence is neutral rather than an error. */
    fun viber(installed: List<String>?): Presented = when {
        installed == null -> Presented(StatusLevel.NEUTRAL, "Checking")
        installed.isEmpty() -> Presented(StatusLevel.NEUTRAL, "Not installed", "Only needed to monitor Viber groups.")
        else -> Presented(StatusLevel.OK, "Detected", "Authorized Viber groups are captured like WhatsApp groups.")
    }

    fun backend(
        configured: Boolean,
        lastCheckOk: Boolean?,
        lastCheckAt: Long?,
        lastCheckMessage: String?,
        lastUploadSuccessAt: Long?,
        lastUploadFailureAt: Long?,
    ): Presented {
        if (!configured) return Presented(StatusLevel.NEUTRAL, "Not configured", "Set the backend URL in Settings.")
        val uploadConfirmed = lastUploadSuccessAt != null &&
            lastUploadSuccessAt >= (lastCheckAt ?: 0) && lastUploadSuccessAt >= (lastUploadFailureAt ?: 0)
        return when {
            uploadConfirmed -> Presented(StatusLevel.OK, "Connected", "Last upload confirmed by the server")
            lastCheckOk == true -> Presented(StatusLevel.OK, "Connected", lastCheckMessage)
            lastCheckOk == false -> Presented(StatusLevel.ERROR, "Unreachable", lastCheckMessage)
            else -> Presented(StatusLevel.NEUTRAL, "Not checked", "Run Test Backend to verify connectivity.")
        }
    }

    fun syncWorker(state: SyncWorkerState, paused: Boolean, pending: Int): Presented = when {
        paused -> Presented(StatusLevel.NEUTRAL, "Paused", "Synchronization paused by operator")
        state == SyncWorkerState.RUNNING -> Presented(StatusLevel.INFO, "Running", "Uploading queued messages")
        state == SyncWorkerState.BACKING_OFF -> Presented(StatusLevel.WARNING, "Retrying", "Waiting to retry with exponential backoff")
        state == SyncWorkerState.WAITING -> Presented(StatusLevel.INFO, "Scheduled", "Waiting for network / WorkManager")
        pending > 0 -> Presented(StatusLevel.WARNING, "Idle", "$pending message(s) waiting – next attempt at the periodic check")
        else -> Presented(StatusLevel.OK, "Idle", "Queue empty")
    }

    fun database(healthy: Boolean?): Presented = when (healthy) {
        null -> Presented(StatusLevel.NEUTRAL, "Checking")
        true -> Presented(StatusLevel.OK, "OK")
        false -> Presented(StatusLevel.ERROR, "Error", "Local database could not be queried")
    }

    fun battery(ignoring: Boolean?, backgroundRestricted: Boolean?, bucket: String?): Presented = when {
        ignoring == null -> Presented(StatusLevel.NEUTRAL, "Checking")
        backgroundRestricted == true -> Presented(
            StatusLevel.ERROR, "Restricted",
            "Android restricts this app in the background. Set battery usage to Unrestricted.",
        )
        bucket == "Restricted" || bucket == "Rare" -> Presented(
            StatusLevel.WARNING, "Standby: $bucket",
            "Android may defer background work. Exempt the app from battery optimization.",
        )
        ignoring -> Presented(StatusLevel.OK, "Recommended configuration", "App is exempt from battery optimization")
        else -> Presented(
            StatusLevel.WARNING, "Optimized",
            "Battery optimization may delay uploads. Exempt OKB Bridge for reliable background operation.",
        )
    }
}
