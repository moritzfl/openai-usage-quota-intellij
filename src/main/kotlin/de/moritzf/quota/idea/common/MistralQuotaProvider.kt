package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.mistral.MistralQuotaClient
import de.moritzf.quota.mistral.MistralQuotaException

class MistralQuotaProvider(
    private val client: MistralQuotaClient = MistralQuotaClient(),
) : CachedQuotaProvider<MistralQuota>() {
    override val type = QuotaProviderType.MISTRAL
    override val notConfiguredMessage = "Mistral API key missing. Add a Mistral API key in settings."

    override fun refresh() {
        val apiKey = MistralApiKeyStore.getInstance().loadBlocking()
        if (apiKey.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }
        try {
            val quota = client.fetchQuota(apiKey)
            storeQuota(quota, quota.rawJson)
        } catch (exception: MistralQuotaException) {
            storeFetchFailure(
                exception.statusCode,
                exception.message ?: "Request failed. Check your connection.",
                exception.rawBody,
            )
        }
    }
}
