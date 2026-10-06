package com.okb.whatsappbridge.ui.consolidated

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.okb.whatsappbridge.domain.model.AutomationReadiness
import com.okb.whatsappbridge.domain.model.TextDeliveryStatus
import com.okb.whatsappbridge.domain.model.TextReportDelivery
import com.okb.whatsappbridge.ui.components.ButtonRow
import com.okb.whatsappbridge.ui.components.Formatters
import com.okb.whatsappbridge.ui.components.KeyValueLine
import com.okb.whatsappbridge.ui.components.Panel
import com.okb.whatsappbridge.ui.components.StatusLevel
import com.okb.whatsappbridge.ui.components.StatusLine
import com.okb.whatsappbridge.ui.components.color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** TEXT reports get a Dashboard card while active, and for a day after they were sent or failed. */
fun TextReportDelivery.showOnDashboard(now: Long): Boolean = when (status) {
    TextDeliveryStatus.SCHEDULED, TextDeliveryStatus.SENDING -> true
    TextDeliveryStatus.SENT, TextDeliveryStatus.FAILED -> now - updatedAt < 24L * 60 * 60 * 1000
}

/** Scheduled / Sending / Sent / Failed. */
fun TextReportDelivery.statusLabel(): Pair<StatusLevel, String> = when (status) {
    TextDeliveryStatus.SCHEDULED ->
        if (lastError != null) StatusLevel.WARNING to "Scheduled · retrying" else StatusLevel.INFO to "Scheduled"
    TextDeliveryStatus.SENDING -> StatusLevel.INFO to "Sending"
    TextDeliveryStatus.SENT -> StatusLevel.OK to "Sent"
    TextDeliveryStatus.FAILED -> StatusLevel.ERROR to "Failed"
}

private val periodDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val periodTime = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** "Oct 6, 6:00 PM – 12:00 AM" (phone time zone). */
fun periodLabel(start: String?, end: String?): String? {
    val s = start?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()) }.getOrNull() } ?: return null
    val e = end?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()) }.getOrNull() } ?: return null
    return "${periodDay.format(s)}, ${periodTime.format(s)} – ${periodTime.format(e)}"
}

/**
 * AUTOMATIC TEXT REPORT: status only. It is sent to the destination group without any operator action, so
 * there is deliberately no Send button.
 */
@Composable
fun AutomaticTextReportCard(delivery: TextReportDelivery) {
    val (level, label) = delivery.statusLabel()
    Panel(title = if (delivery.isTest) "AUTOMATIC TEXT REPORT · TEST" else "AUTOMATIC TEXT REPORT", accent = level.color()) {
        StatusLine("Status", level, label)
        KeyValueLine("Destination", delivery.destinationGroup, mono = false)
        periodLabel(delivery.periodStart, delivery.periodEnd)?.let { KeyValueLine("Period", it, mono = false) }
        KeyValueLine("Reports", delivery.reportCount?.toString() ?: "—", mono = false)
        when (delivery.status) {
            TextDeliveryStatus.SENT -> {
                KeyValueLine("Sent", Formatters.time(delivery.sentAt), mono = false)
                delivery.verification?.let { Hint(it) }
            }
            TextDeliveryStatus.SENDING -> Hint("Sending in WhatsApp now. Do not use the phone until it finishes.")
            TextDeliveryStatus.SCHEDULED -> Hint(
                delivery.lastError?.let { "Last attempt: $it. Retried automatically." }
                    ?: "Sent automatically; no action needed.",
            )
            TextDeliveryStatus.FAILED -> Hint(
                "${delivery.lastError ?: "Not sent."} Fix the cause, then use Retry in the Command Center " +
                    "(Settings → Automated WhatsApp reports → History).",
            )
        }
        delivery.parts.firstOrNull()?.ref?.let { KeyValueLine("Ref", it) }
    }
}

/**
 * Checklist for unattended sending (12:00 AM, phone locked, operator asleep). Every item is read from Android;
 * buttons open the matching settings screen. The bridge never changes these settings itself.
 */
@Composable
fun AutomaticSendingPanel(
    readiness: AutomationReadiness,
    destinationGroup: String,
    onOpenAccessibility: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenScreenLock: () -> Unit,
) {
    val ok = readiness.ready && destinationGroup.isNotBlank()
    Panel(title = "Automatic Text Reports", accent = (if (ok) StatusLevel.OK else StatusLevel.WARNING).color()) {
        StatusLine(
            "Sending service",
            when {
                readiness.accessibilityEnabled && readiness.accessibilityConnected -> StatusLevel.OK
                else -> StatusLevel.ERROR
            },
            when {
                !readiness.accessibilityEnabled -> "Off"
                !readiness.accessibilityConnected -> "Enabled, not running"
                else -> "On"
            },
            if (!readiness.accessibilityEnabled) "Settings → Accessibility → OKB Bridge automatic text reports" else null,
        )
        StatusLine(
            "Cut-off alarm",
            if (readiness.exactAlarmsAllowed) StatusLevel.OK else StatusLevel.WARNING,
            if (readiness.exactAlarmsAllowed) "Exact" else "Inexact (may be late)",
            readiness.nextCheckAt?.let { "Next check ${Formatters.time(it)} (${if (readiness.nextCheckExact) "exact" else "inexact"})" },
        )
        StatusLine(
            "Screen lock",
            if (readiness.secureLockScreen) StatusLevel.ERROR else StatusLevel.OK,
            if (readiness.secureLockScreen) "PIN / pattern / password" else "None or Swipe",
            if (readiness.secureLockScreen) "A locked phone cannot send: Android does not let apps unlock a PIN, pattern or password." else null,
        )
        StatusLine(
            "Destination",
            if (destinationGroup.isNotBlank()) StatusLevel.OK else StatusLevel.ERROR,
            destinationGroup.ifBlank { "Not set" },
        )
        Hint(
            if (ok) {
                "Ready: the consolidated TEXT report is sent to “$destinationGroup” automatically at each scheduled time, " +
                    "also with the screen off. Keep WhatsApp logged in and the phone charging and online."
            } else {
                "Fix the items above, or the TEXT report cannot be sent while nobody is using the phone."
            },
        )
        if (!readiness.ready) {
            ButtonRow {
                if (!readiness.accessibilityEnabled || !readiness.accessibilityConnected) {
                    OutlinedButton(onClick = onOpenAccessibility) { Text("Accessibility") }
                }
                if (!readiness.exactAlarmsAllowed) OutlinedButton(onClick = onOpenAlarms) { Text("Alarms") }
                if (readiness.secureLockScreen) OutlinedButton(onClick = onOpenScreenLock) { Text("Screen lock") }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}
