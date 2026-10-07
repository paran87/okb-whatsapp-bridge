package com.okb.whatsappbridge.ui.consolidated

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import com.okb.whatsappbridge.OkbBridgeApplication
import com.okb.whatsappbridge.service.AndroidConsolidatedReportNotifier
import com.okb.whatsappbridge.util.logging.BridgeLogger
import com.okb.whatsappbridge.whatsapp.WhatsAppPackages
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Send as PDF" (from the "PDF Ready" notification or Dashboard card): the MANUAL PDF delivery, used when the
 * phone could not send the PDF automatically (or the operator wants to send it now). No UI of its own.
 *
 * Opens WhatsApp's share screen with the consolidated PDF attached and the short caption filled in, and copies
 * the caption to the clipboard as a fallback. The operator selects the configured DESTINATION group and presses
 * Send. Opening the share screen is recorded as "opened in WhatsApp" only; the operator confirms "sent" in the
 * app afterwards. From then on the report is not sent automatically (no duplicates). Automatic sending is
 * ConsolidatedReportCheckUseCase / WhatsAppPdfSender.
 */
class ShareReportActivity : Activity() {

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val container = (application as OkbBridgeApplication).container
        val useCase = container.consolidatedReports

        scope.launch {
            val (target, problem) = useCase.prepareShare(id)
            if (target == null) {
                Toast.makeText(this@ShareReportActivity, problem ?: "The report could not be prepared.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            val destination = target.delivery.destinationGroup?.takeIf { it.isNotBlank() }
                ?: container.settingsRepository.getDestinationGroupName().takeIf { it.isNotBlank() }
            val caption = target.delivery.caption
            if (caption.isNotBlank()) {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OKB report caption", caption))
            }
            when (val result = WhatsAppShare.launch(this@ShareReportActivity, target.file, caption, container.logger)) {
                null -> {
                    Toast.makeText(
                        this@ShareReportActivity,
                        AndroidConsolidatedReportNotifier.shareInstruction(destination) + " Then come back and confirm.",
                        Toast.LENGTH_LONG,
                    ).show()
                    AndroidConsolidatedReportNotifier.cancel(this@ShareReportActivity, id)
                    container.appScope.launch { useCase.markOpened(id) }
                }
                else -> {
                    Toast.makeText(this@ShareReportActivity, result, Toast.LENGTH_LONG).show()
                    container.appScope.launch { useCase.recordShareProblem(id, result) }
                }
            }
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "okb.consolidated.id"

        fun intent(context: Context, deliveryId: String): Intent =
            Intent(context, ShareReportActivity::class.java).putExtra(EXTRA_ID, deliveryId)
    }
}

/** The standard Android share flow for a PDF, preferring WhatsApp (then WhatsApp Business) when installed. */
object WhatsAppShare {

    /** Starts the share flow. Returns null on success, or a message describing why it could not start. */
    fun launch(activity: Activity, file: File, caption: String, logger: BridgeLogger? = null): String? {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, caption)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        share.clipData = ClipData.newRawUri(file.name, uri)
        val whatsApp = listOf(WhatsAppPackages.WHATSAPP, WhatsAppPackages.WHATSAPP_BUSINESS).firstOrNull { isInstalled(activity, it) }
        return try {
            if (whatsApp != null) {
                activity.startActivity(share.setPackage(whatsApp))
                logger?.info("Delivery", "Share intent launched (${WhatsAppPackages.displayName(whatsApp)}) for ${file.name}")
            } else {
                // WhatsApp is not installed: offer the system share sheet so the PDF can still be passed on.
                activity.startActivity(Intent.createChooser(share, "Send consolidated report"))
                logger?.warn("Delivery", "WhatsApp is not installed; system share sheet opened for ${file.name}")
            }
            null
        } catch (_: ActivityNotFoundException) {
            "No app on this phone can share a PDF (is WhatsApp installed?)."
        } catch (e: SecurityException) {
            "The PDF could not be shared: ${e.javaClass.simpleName}"
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
