package de.moritzf.quota.mistral

import de.moritzf.quota.shared.ProviderQuota
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Duration

@Serializable
data class MistralQuota(
    val email: String = "",
    val organization: String = "",
    val workspace: String = "",
    val apiKeyName: String = "",
    val monthlyUsage: MistralUsageWindow? = null,
    val tokenUsage: MistralUsageWindow? = null,
    val requestUsage: MistralUsageWindow? = null,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean =
        monthlyUsage != null || organization.isNotBlank() || email.isNotBlank() ||
            tokenUsage != null || requestUsage != null

    override fun usageFraction(): Double? =
        monthlyUsage?.usagePercent?.let { it / 100.0 }
            ?: listOfNotNull(tokenUsage?.usagePercent, requestUsage?.usagePercent).maxOrNull()?.let { it / 100.0 }

    override fun activityWindows(): Map<String, Double> = buildMap {
        monthlyUsage?.usagePercent?.let { put("monthly", it / 100.0) }
        tokenUsage?.usagePercent?.let { put("tokensMinute", it / 100.0) }
        requestUsage?.usagePercent?.let { put("requestsMinute", it / 100.0) }
    }
}

@Serializable
data class MistralUsageWindow(
    val used: Long = 0,
    val limit: Long = 0,
    val remaining: Long = 0,
    val usagePercent: Double = 0.0,
    val resetsAt: Instant? = null,
    val periodDurationMs: Long? = null,
) {
    @Transient
    val periodDuration: Duration? = periodDurationMs?.let(Duration::ofMillis)
}
