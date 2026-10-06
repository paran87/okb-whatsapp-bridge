package com.okb.whatsappbridge.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.okb.whatsappbridge.OkbBridgeApplication
import com.okb.whatsappbridge.automation.AccessibilityUiNode
import com.okb.whatsappbridge.automation.UiNode
import com.okb.whatsappbridge.automation.WhatsAppUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Accessibility service used ONLY to send the automatic consolidated TEXT report into the configured WhatsApp
 * destination group (see WhatsAppTextSender). It is limited to the WhatsApp packages (accessibility config),
 * reads no events, collects nothing and does nothing unless a TEXT delivery is being sent. The operator enables
 * it once in Android Settings → Accessibility → OKB WhatsApp Bridge.
 */
class WhatsAppAutomationService : AccessibilityService(), WhatsAppUi {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService.value = this
        (application as? OkbBridgeApplication)?.container?.logger?.info("TextReport", "Automatic sending service connected")
    }

    // The send flow polls the active window itself; events are not used or stored.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (connectedService.value === this) connectedService.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (connectedService.value === this) connectedService.value = null
        super.onDestroy()
    }

    override fun root(): UiNode? = rootInActiveWindow?.let(::AccessibilityUiNode)

    override fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    override fun launch(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            // A fresh task always starts on WhatsApp's chat list.
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    override fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Turns the screen off again after an automatic send that woke the phone (Android 9+). */
    fun lockScreen(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        private val connectedService = MutableStateFlow<WhatsAppAutomationService?>(null)

        /** The running service, or null when it is off (or Android has not bound it yet). */
        val connected: StateFlow<WhatsAppAutomationService?> = connectedService

        /** True when the operator enabled the service in Android's accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(context, WhatsAppAutomationService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
