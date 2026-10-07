package com.okb.whatsappbridge.automation

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import com.okb.whatsappbridge.domain.usecase.AutomaticPdfSender
import com.okb.whatsappbridge.domain.usecase.AutomaticTextSender
import com.okb.whatsappbridge.service.WhatsAppAutomationService
import com.okb.whatsappbridge.util.logging.BridgeLogger
import com.okb.whatsappbridge.whatsapp.WhatsAppPackages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Automatic TEXT and PDF sending on the dedicated bridge phone:
 *
 *   wake the screen (wake lock) → dismiss a non-secure lock screen (UnlockActivity, else a swipe-up gesture;
 *   see ScreenUnlocker) → drive WhatsApp through the
 *   accessibility service (WhatsAppTextSender, or WhatsAppPdfSender for the PDF) → go back to the home screen → turn the screen off again if it was
 *   off → release the wake lock.
 *
 * Requirements on the phone (shown in the app's "Automatic text reports" checklist): the OKB accessibility
 * service enabled, WhatsApp installed and logged in to an account that is a member of the destination group,
 * and a screen lock of None or Swipe (Android never lets an app unlock a PIN, pattern or password).
 */
class AndroidAutomaticTextSender(
    private val context: Context,
    private val logger: BridgeLogger,
) : AutomaticTextSender, AutomaticPdfSender {

    override fun unavailableReason(): String? {
        if (whatsAppPackage() == null) return "WhatsApp is not installed on the bridge phone"
        if (WhatsAppAutomationService.connected.value == null) {
            return if (WhatsAppAutomationService.isEnabled(context)) {
                "The automatic sending service is enabled but Android has not started it; open the OKB Bridge app or restart the phone"
            } else {
                "Automatic sending is off: enable OKB WhatsApp Bridge in Android Settings → Accessibility"
            }
        }
        return null
    }

    override suspend fun send(request: TextSendRequest, progress: SendProgress): SendOutcome = onScreen { service, pkg ->
        WhatsAppTextSender(service, log = { logger.info(TAG, it) }).send(pkg, request, progress)
    }

    /** The consolidated PDF, through WhatsApp's own "Send to" screen (see WhatsAppPdfSender). */
    override suspend fun send(request: PdfSendRequest, progress: PdfSendProgress): SendOutcome = onScreen { service, pkg ->
        WhatsAppPdfSender(service, log = { logger.info(PDF_TAG, it) }).send(pkg, request, progress)
    }

    /** Wakes and unlocks the phone, runs [block] against WhatsApp, then leaves WhatsApp and restores the screen. */
    private suspend fun onScreen(
        block: suspend (WhatsAppAutomationService, String) -> SendOutcome,
    ): SendOutcome = withContext(Dispatchers.Default) {
        val service = WhatsAppAutomationService.connected.value
            ?: return@withContext SendOutcome.Failed(unavailableReason() ?: "Automatic sending service not running")
        val pkg = whatsAppPackage() ?: return@withContext SendOutcome.Failed("WhatsApp is not installed on the bridge phone")
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val screenWasOff = power?.isInteractive == false
        val wakeLock = screenWakeLock(power)
        try {
            // The wake lock turns the screen on; a non-secure lock screen is dismissed (or swiped away).
            if (screenWasOff) delay(SETTLE_MS)
            val unlocker = ScreenUnlocker(
                isLocked = { keyguard?.isKeyguardLocked == true },
                isSecure = { keyguard?.isDeviceSecure == true },
                dismiss = { UnlockActivity.dismissKeyguard(service) },
                swipeUp = { attempt -> service.swipeUp(attempt) },
                sleep = { delay(it) },
                log = { logger.info(TAG, it) },
            )
            unlocker.unlock()?.let { return@withContext SendOutcome.Failed(it) }
            delay(SETTLE_MS)
            withTimeoutOrNull(SEND_TIMEOUT_MS) { block(service, pkg) } ?: SendOutcome.Failed("Automatic sending timed out")
        } finally {
            // Also after a timeout or cancellation: leave WhatsApp, turn the screen off again, release the lock.
            withContext(NonCancellable) {
                service.home()
                if (screenWasOff) {
                    delay(SETTLE_MS)
                    service.lockScreen()
                }
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    @Suppress("DEPRECATION") // The only API that turns the screen on from a background component.
    private fun screenWakeLock(power: PowerManager?): PowerManager.WakeLock? = power?.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
        "OKBBridge:textReport",
    )?.apply {
        setReferenceCounted(false)
        acquire(SEND_TIMEOUT_MS + 60_000)
    }

    private fun whatsAppPackage(): String? =
        listOf(WhatsAppPackages.WHATSAPP, WhatsAppPackages.WHATSAPP_BUSINESS).firstOrNull { isInstalled(it) }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val TAG = "TextReport"
        const val PDF_TAG = "Delivery"
        const val SETTLE_MS = 700L
        const val SEND_TIMEOUT_MS = 4L * 60 * 1000
    }
}
