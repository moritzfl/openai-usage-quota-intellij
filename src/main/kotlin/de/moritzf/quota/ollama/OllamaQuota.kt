package de.moritzf.quota.ollama

import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Ollama Cloud subscription usage from `GET https://ollama.com/api/usage`.
 */
@Serializable
data class OllamaQuota(
    val sessionUsage: OllamaUsageWindow? = null,
    val weeklyUsage: OllamaUsageWindow? = null,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean {
        return sessionUsage != null || weeklyUsage != null
    }

    override fun usageFraction(): Double? {
        val windows = listOfNotNull(sessionUsage?.usagePercent, weeklyUsage?.usagePercent)
        return windows.maxOrNull()?.let { it / 100.0 }
    }

    override fun activityFraction(): Double? {
        val windows = listOfNotNull(sessionUsage?.usagePercent, weeklyUsage?.usagePercent)
        return windows.takeIf { it.isNotEmpty() }?.sum()?.let { it / 100.0 }
    }
}

/**
 * Single Ollama Cloud usage window. [usagePercent] is 0..100.
 */
@Serializable
data class OllamaUsageWindow(
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
)
