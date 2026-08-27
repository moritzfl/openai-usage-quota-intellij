package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.mistral.MistralSessionCookieStore
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.mistral.MistralQuotaClient
import de.moritzf.quota.mistral.MistralQuotaException

class MistralQuotaProvider(
    override val accountId: String = QuotaProviderType.MISTRAL.id,
    private val client: MistralQuotaClient = MistralQuotaClient(),
) : CachedQuotaProvider<MistralQuota>() {
    override val type = QuotaProviderType.MISTRAL
    override val notConfiguredMessage =
        "Mistral session cookie missing. Add the ory_session_* cookie from admin.mistral.ai in settings."

    override fun refresh() {
        val cookie = MistralSessionCookieStore.forAccount(accountId).loadBlocking()
        if (cookie.isNullOrBlank()) {
            clearData(notConfiguredMessage)
            return
        }
        try {
            val quota = client.fetchQuota(cookie, MistralApiKeyStore.forAccount(accountId).loadBlocking())
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
