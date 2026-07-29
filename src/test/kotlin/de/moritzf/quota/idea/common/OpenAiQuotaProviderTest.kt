package de.moritzf.quota.idea.common

import de.moritzf.quota.openai.OpenAiCodexQuota
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class OpenAiQuotaProviderTest {
    @Test
    fun refreshKeepsLoginAndLastQuotaWhenTokenIsTemporarilyUnavailable() {
        val quota = OpenAiCodexQuota(allowed = true).apply { rawJson = "{\"ok\":true}" }
        var token: String? = "token"
        val provider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ -> quota },
            accessTokenProvider = { token },
            accountIdProvider = { "account-1" },
            tokenRefresher = { null },
            loggedInProvider = { true },
        )

        provider.refresh()
        token = null
        provider.refresh()

        assertSame(quota, provider.getLastQuota(), "a failed refresh must not drop the last reading")
        assertEquals(
            "OpenAI token could not be refreshed. Trying again with the next update.",
            provider.getLastError(),
        )
    }

    @Test
    fun refreshClearsDataWhenNotLoggedIn() {
        val provider = OpenAiQuotaProvider(
            quotaFetcher = { _, _ -> error("must not be called") },
            accessTokenProvider = { null },
            accountIdProvider = { null },
            tokenRefresher = { null },
            loggedInProvider = { false },
        )

        provider.refresh()

        assertNull(provider.getLastQuota())
        assertEquals("Not logged in", provider.getLastError())
    }
}
