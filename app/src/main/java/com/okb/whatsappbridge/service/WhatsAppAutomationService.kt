package com.okb.whatsappbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.FileProvider
import com.okb.whatsappbridge.OkbBridgeApplication
import com.okb.whatsappbridge.automation.AccessibilityUiNode
import com.okb.whatsappbridge.automation.UiNode
import com.okb.whatsappbridge.automation.WhatsAppUi
import com.okb.whatsappbridge.whatsapp.WhatsAppPackages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Accessibility service used ONLY to send the automatic consolidated reports (TEXT and PDF) into the configured
 * WhatsApp destination group (see WhatsAppTextSender, WhatsAppPdfSender). It is limited to the WhatsApp packages
 * (accessibility config), reads no events, collects nothing and does nothing unless a report is being sent. The
 * operator enables it once in Android Settings → Accessibility → OKB WhatsApp Bridge.
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

    override fun root(): UiNode? = whatsAppRoot()?.let(::AccessibilityUiNode)

    override fun foregroundPackage(): String? =
        whatsAppRoot()?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString()

    /**
     * WhatsApp's window when WhatsApp is the app on screen. Input focus can sit on a system overlay (notification
     * shade, a manufacturer pop-up) while WhatsApp is open underneath, so the topmost application window is used
     * when the focused window is not WhatsApp's.
     */
    private fun whatsAppRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (WhatsAppPackages.isWhatsApp(it.packageName?.toString())) return it }
        val topApp = runCatching { windows }.getOrNull()
            ?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return null
        val root = topApp.root ?: return null
        return root.takeIf { WhatsAppPackages.isWhatsApp(it.packageName?.toString()) }
    }

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

    override fun shareFile(packageName: String, file: File, caption: String): Boolean = try {
        val uri = FileProvider.getUriForFile(this, "${this.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .setPackage(packageName)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, caption)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        share.clipData = ClipData.newRawUri(file.name, uri)
        startActivity(share)
        true
    } catch (_: RuntimeException) {
        false
    }

    override fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    override fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Swipes up from the lower part of the screen, as a person dismisses a Swipe lock screen. Later attempts
     * start a little higher (clear of a gesture-navigation bar). True when Android completed the gesture.
     */
    suspend fun swipeUp(attempt: Int): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * (0.85f - 0.1f * attempt.coerceIn(0, 2))
        val endY = metrics.heightPixels * 0.25f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return suspendCancellableCoroutine { cont ->
            val started = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!started && cont.isActive) cont.resume(false)
        }
    }

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
