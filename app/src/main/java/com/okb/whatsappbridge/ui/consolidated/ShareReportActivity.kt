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
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedDelivery
import com.okb.whatsappbridge.domain.usecase.ConsolidatedReportCheckUseCase
import com.okb.whatsappbridge.service.AndroidConsolidatedReportNotifier
import com.okb.whatsappbridge.whatsapp.WhatsAppPackages
import kotlinx.coroutines.launch
import java.io.File

/**
 * Target of the "Consolidated report ready" notification (no UI of its own).
 *
 * Opens WhatsApp's share screen with the consolidated PDF attached and the short caption filled in, and
 * copies the caption to the clipboard as a fallback. WhatsApp provides no supported way for another app to
 * pre-select a group chat, so the operator chooses the OKB Command Center group and presses Send — the
 * bridge never sends anything by itself and uses no accessibility or unofficial WhatsApp automation.
 */
class ShareReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        val caption = intent.getStringExtra(EXTRA_CAPTION).orEmpty()
        val group = intent.getStringExtra(EXTRA_GROUP)?.takeIf { it.isNotBlank() } ?: "the OKB Command Center group"
        val container = (application as OkbBridgeApplication).container

        val file = ConsolidatedReportCheckUseCase.pdfFile(container.consolidatedReportDirectory, id, fileName)
        if (file == null || !file.exists()) {
            Toast.makeText(this, "The report file is no longer on this phone. Use Resend in the Command Center.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (caption.isNotBlank()) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OKB report caption", caption))
        }
        if (openShare(file, caption)) {
            Toast.makeText(this, "Choose “$group” in WhatsApp, then press Send. The caption is also copied.", Toast.LENGTH_LONG).show()
            AndroidConsolidatedReportNotifier.cancel(this, id)
            container.appScope.launch { container.consolidatedReports.markShared(id) }
        } else {
            Toast.makeText(this, "WhatsApp could not be opened on this phone.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun openShare(file: File, caption: String): Boolean {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, caption)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        share.clipData = ClipData.newRawUri(file.name, uri)
        val target = listOf(WhatsAppPackages.WHATSAPP, WhatsAppPackages.WHATSAPP_BUSINESS).firstOrNull(::isInstalled)
        return try {
            if (target != null) startActivity(share.setPackage(target))
            else startActivity(Intent.createChooser(share, "Send consolidated report"))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val EXTRA_ID = "okb.consolidated.id"
        private const val EXTRA_FILE_NAME = "okb.consolidated.fileName"
        private const val EXTRA_CAPTION = "okb.consolidated.caption"
        private const val EXTRA_GROUP = "okb.consolidated.group"

        fun intent(context: Context, delivery: ConsolidatedDelivery): Intent =
            Intent(context, ShareReportActivity::class.java)
                .putExtra(EXTRA_ID, delivery.id)
                .putExtra(EXTRA_FILE_NAME, delivery.fileName)
                .putExtra(EXTRA_CAPTION, delivery.caption)
                .putExtra(EXTRA_GROUP, delivery.destinationGroup)
    }
}
