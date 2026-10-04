package com.okb.whatsappbridge.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Android currently has the notification listener bound *in this process*.
 *
 * This is real system state: the flag is only set from [WhatsAppNotificationListenerService.onListenerConnected]
 * and cleared on disconnect/destroy. If the process is restarted (e.g. by opening the UI) the flag starts
 * as false until Android binds the listener again, so the UI can never show "ACTIVE" just because it is open.
 */
object ListenerConnectionState {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun set(connected: Boolean) {
        _connected.value = connected
    }
}
