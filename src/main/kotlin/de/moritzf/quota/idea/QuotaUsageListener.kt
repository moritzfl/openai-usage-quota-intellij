package de.moritzf.quota.idea

import com.intellij.util.messages.Topic
import de.moritzf.quota.OpenAiCodexQuota

/**
 * Message bus listener for quota refresh updates.
 */
fun interface QuotaUsageListener {
    fun onQuotaUpdated(quota: OpenAiCodexQuota?, error: String?)

    companion object {
        @JvmField
        val TOPIC: Topic<QuotaUsageListener> = Topic.create("OpenAI Usage Quota Updated", QuotaUsageListener::class.java)
    }
}
