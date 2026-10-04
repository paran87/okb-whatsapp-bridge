package com.okb.whatsappbridge.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {
    private val clock = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.US)
    private val shortClock = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)
    private val dateTime = DateTimeFormatter.ofPattern("MMM d, hh:mm:ss a", Locale.US)

    private fun zoned(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())

    /** "08:42:13 AM", prefixed with the date when not today. */
    fun time(epochMillis: Long?, never: String = "Never"): String {
        if (epochMillis == null || epochMillis <= 0) return never
        val z = zoned(epochMillis)
        return if (z.toLocalDate() == LocalDate.now()) clock.format(z) else dateTime.format(z)
    }

    fun shortTime(epochMillis: Long?, never: String = "—"): String {
        if (epochMillis == null || epochMillis <= 0) return never
        val z = zoned(epochMillis)
        return if (z.toLocalDate() == LocalDate.now()) shortClock.format(z) else dateTime.format(z)
    }

    /** "just now", "2 minutes ago", "3 hours ago". */
    fun relative(epochMillis: Long?, now: Long, never: String = "Never"): String {
        if (epochMillis == null || epochMillis <= 0) return never
        val seconds = ((now - epochMillis) / 1000).coerceAtLeast(0)
        return when {
            seconds < 45 -> "just now"
            seconds < 90 -> "1 minute ago"
            seconds < 3600 -> "${seconds / 60} minutes ago"
            seconds < 7200 -> "1 hour ago"
            seconds < 86_400 -> "${seconds / 3600} hours ago"
            else -> "${seconds / 86_400} days ago"
        }
    }

    fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Human-readable byte size, e.g. "0 B", "4.2 KB", "317 MB", "1.3 GB". */
    fun bytes(value: Long?): String {
        val v = value ?: return "—"
        if (v < 1024) return "$v B"
        val units = listOf("KB", "MB", "GB", "TB")
        var size = v.toDouble() / 1024
        var i = 0
        while (size >= 1024 && i < units.lastIndex) { size /= 1024; i++ }
        return (if (size >= 100) "%.0f" else "%.1f").format(size) + " " + units[i]
    }
}
