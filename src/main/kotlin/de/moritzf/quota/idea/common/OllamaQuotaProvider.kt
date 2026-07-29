package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaQuotaClient
import de.moritzf.quota.ollama.OllamaQuotaException

/**
 * Fetches and caches Ollama Cloud quota data via the official usage API.
 */
class OllamaQuotaProvider(
    private val ollamaClient: OllamaQuotaClient = OllamaQuotaClient(),
    private val apiKeyProvider: () -> String? = { OllamaApiKeyStore.getInstance().loadBlocking() },
) : CachedQuotaProvider<OllamaQuota>() {
    override val type = QuotaProviderType.OLLAMA
    override val notConfiguredMessage = "No Ollama API key configured"

    override fun refresh() {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }

        try {
            val quota = ollamaClient.fetchQuota(apiKey)
            storeQuota(quota, quota.rawJson)
        } catch (exception: OllamaQuotaException) {
            if (exception.statusCode == 429 && lastQuotaRef.get() != null) return
            storeError(exception.message ?: "Request failed (${exception.statusCode})", exception.rawBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }
}
