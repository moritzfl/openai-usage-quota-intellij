package de.moritzf.quota.idea.common

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeQuotaClient
import de.moritzf.quota.claude.ClaudeQuotaException
import de.moritzf.quota.idea.auth.QuotaAuthService

class ClaudeQuotaProvider(
    private val client: ClaudeQuotaClient = ClaudeQuotaClient(),
    private val tokenProvider: () -> String? = {
        QuotaAuthService.getInstance().getAccessTokenBlocking(QuotaProviderType.CLAUDE)
    },
    private val tokenRefresher: (staleAccessToken: String?) -> String? = { staleToken ->
        QuotaAuthService.getInstance().forceRefreshBlocking(QuotaProviderType.CLAUDE, staleToken)
    },
    private val loggedInProvider: () -> Boolean = {
        QuotaAuthService.getInstance().isLoggedIn(QuotaProviderType.CLAUDE)
    },
) : CachedQuotaProvider<ClaudeQuota>() {
    override val type = QuotaProviderType.CLAUDE
    override val notConfiguredMessage = "Claude login required. Log in from Claude settings."

    override fun refresh() {
        val accessToken = tokenProvider()
        if (accessToken.isNullOrBlank()) {
            storeMissingAccessToken(loggedInProvider(), TOKEN_UNAVAILABLE_MESSAGE)
            return
        }

        try {
            val quota = fetchQuotaWithAuthRetry(accessToken)
            storeQuota(quota, quota.rawJson)
        } catch (exception: ClaudeQuotaException) {
            storeFetchFailure(exception.statusCode, exception.message ?: "Request failed", exception.rawBody)
        } catch (exception: Exception) {
            storeError(exception.message ?: "Request failed")
        }
    }

    private fun fetchQuotaWithAuthRetry(accessToken: String): ClaudeQuota {
        return try {
            client.fetchQuota(accessToken)
        } catch (exception: ClaudeQuotaException) {
            val missingProfileScope = exception.statusCode == 403 &&
                exception.rawBody?.contains("user:profile", ignoreCase = true) == true
            if (missingProfileScope || (exception.statusCode != 401 && exception.statusCode != 403)) throw exception
            val refreshed = tokenRefresher(accessToken)?.takeIf { it.isNotBlank() && it != accessToken }
                ?: throw exception
            client.fetchQuota(refreshed)
        }
    }

    private companion object {
        private const val TOKEN_UNAVAILABLE_MESSAGE =
            "Claude token could not be refreshed. Trying again with the next update."
    }
}
