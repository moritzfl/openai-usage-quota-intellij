package de.moritzf.quota.ollama

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Global Ollama Cloud limit reset schedule. Session and weekly windows reset at the same
 * wall-clock boundaries for every account (not per-user rolling windows), so remaining time
 * can be computed when `/api/usage` omits `resets_at`.
 *
 * Session: every 5 hours on the Unix epoch grid (`18000 - now%18000`).
 * Weekly: every Monday 00:00 UTC (`604800 - (now - 4d)%604800`).
 */
object OllamaResetSchedule {
    const val SESSION_PERIOD_SECONDS: Long = 5L * 60L * 60L
    const val WEEKLY_PERIOD_SECONDS: Long = 7L * 24L * 60L * 60L
    /** Shift Unix epoch (Thu) so week boundaries land on Monday 00:00 UTC. */
    const val WEEKLY_EPOCH_OFFSET_SECONDS: Long = 4L * 24L * 60L * 60L

    fun sessionResetsAt(now: Instant = Clock.System.now()): Instant {
        val nowSec = now.epochSeconds
        val intoPeriod = positiveMod(nowSec, SESSION_PERIOD_SECONDS)
        val remaining = SESSION_PERIOD_SECONDS - intoPeriod
        return Instant.fromEpochSeconds(nowSec + remaining)
    }

    fun weeklyResetsAt(now: Instant = Clock.System.now()): Instant {
        val nowSec = now.epochSeconds
        val intoPeriod = positiveMod(nowSec - WEEKLY_EPOCH_OFFSET_SECONDS, WEEKLY_PERIOD_SECONDS)
        val remaining = WEEKLY_PERIOD_SECONDS - intoPeriod
        return Instant.fromEpochSeconds(nowSec + remaining)
    }

    private fun positiveMod(value: Long, modulus: Long): Long {
        val rem = value % modulus
        return if (rem >= 0L) rem else rem + modulus
    }
}
