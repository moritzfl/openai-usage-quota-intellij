package de.moritzf.quota.idea

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * UI formatting helpers for timestamps and relative quota time values.
 */
object QuotaUiUtil {
    private val monthAbbreviations = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    @JvmStatic
    fun formatReset(resetsAt: Instant?): String? {
        if (resetsAt == null) {
            return null
        }

        val duration = (resetsAt.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()).milliseconds
        val remaining = formatDuration(duration)
        if (remaining != null) {
            return "Resets $remaining"
        }

        val at = formatAbsoluteInstant(resetsAt)
        return "Resets at $at"
    }

    @JvmStatic
    fun formatResetCompact(resetsAt: Instant?): String? {
        if (resetsAt == null) {
            return null
        }

        val duration = formatDuration((resetsAt.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()).milliseconds) ?: return null
        return duration.removePrefix("in ")
    }

    @JvmStatic
    fun formatInstant(instant: Instant?): String? {
        if (instant == null) {
            return null
        }

        val ago = formatAgo((Clock.System.now().toEpochMilliseconds() - instant.toEpochMilliseconds()).milliseconds)
        return ago ?: formatAbsoluteInstant(instant)
    }

    private fun formatDuration(duration: Duration): String? {
        if (duration.isNegative()) {
            return null
        }

        val minutes = duration.inWholeMinutes
        if (minutes < 1) {
            return "in <1m"
        }

        val days = minutes / (60 * 24)
        val hours = (minutes % (60 * 24)) / 60
        val mins = minutes % 60
        val builder = StringBuilder("in ")
        if (days > 0) {
            builder.append(days).append('d')
        }
        if (hours > 0) {
            if (builder.length > 3) {
                builder.append(' ')
            }
            builder.append(hours).append('h')
        }
        if (mins > 0 && days == 0L) {
            if (builder.length > 3) {
                builder.append(' ')
            }
            builder.append(mins).append('m')
        }
        return builder.toString()
    }

    private fun formatAgo(duration: Duration): String? {
        if (duration.isNegative()) {
            return null
        }

        val minutes = duration.inWholeMinutes
        if (minutes < 1) {
            return "just now"
        }

        val days = minutes / (60 * 24)
        if (days > 0) {
            return if (days == 1L) "1 day ago" else "$days days ago"
        }

        val hours = minutes / 60
        if (hours > 0) {
            return if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }

        return if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
    }

    private fun formatAbsoluteInstant(instant: Instant): String {
        val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val month = monthAbbreviations[dateTime.monthNumber - 1]
        val hour = dateTime.hour.toString().padStart(2, '0')
        val minute = dateTime.minute.toString().padStart(2, '0')
        return "$month ${dateTime.dayOfMonth}, ${dateTime.year} $hour:$minute"
    }
}
