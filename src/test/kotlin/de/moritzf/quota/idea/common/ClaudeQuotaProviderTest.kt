package de.moritzf.quota.idea.common

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeQuotaClient
import de.moritzf.quota.claude.ClaudeQuotaException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClaudeQuotaProviderTest {
    @Test
    fun refreshStoresQuotaOnSuccess() {
        val quota = ClaudeQuota(fiveHourUsage = null, sevenDayUsage = null, rawJson = "{\"ok\":true}")
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient { quota },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()

        assertSame(quota, provider.getLastQuota())
        assertNull(provider.getLastError())
    }

    @Test
    fun refreshClearsDataWhenNotLoggedIn() {
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient { throw ClaudeQuotaException("unused") },
            tokenProvider = { null },
            tokenRefresher = { null },
            loggedInProvider = { false },
        )

        provider.refresh()

        assertNull(provider.getLastQuota())
        assertEquals(provider.notConfiguredMessage, provider.getLastError())
    }

    @Test
    fun refreshKeepsLoginAndLastQuotaWhenTokenIsTemporarilyUnavailable() {
        val quota = ClaudeQuota(rawJson = "{\"ok\":true}")
        var token: String? = "token"
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient { quota },
            tokenProvider = { token },
            tokenRefresher = { null },
            loggedInProvider = { true },
        )

        provider.refresh()
        token = null
        provider.refresh()

        // A failed refresh must not look like a logout, and must not drop the last reading.
        assertSame(quota, provider.getLastQuota())
        assertNotEquals(provider.notConfiguredMessage, provider.getLastError())
        assertEquals(
            "Claude token could not be refreshed. Trying again with the next update.",
            provider.getLastError(),
        )
    }

    @Test
    fun refreshKeepsLastQuotaOnRateLimitWhenPreviousDataExists() {
        val firstQuota = ClaudeQuota(rawJson = "{\"first\":true}")
        var fetchCount = 0
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient {
                fetchCount++
                if (fetchCount == 1) firstQuota
                else throw ClaudeQuotaException("Claude usage API rate limited. Try again later.", 429)
            },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()
        assertSame(firstQuota, provider.getLastQuota())
        assertNull(provider.getLastError())

        provider.refresh()
        assertSame(firstQuota, provider.getLastQuota())
        assertTrue(
            provider.isLastErrorTransient(),
            "a rate limit must not replace the last quota on screen",
        )
    }

    @Test
    fun refreshSurfacesErrorWhenRateLimitHasNoPreviousData() {
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient { throw ClaudeQuotaException("Claude usage API rate limited.", 429) },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()

        assertNull(provider.getLastQuota())
        assertEquals("Claude usage API rate limited.", provider.getLastError())
    }

    @Test
    fun refreshSurfacesNonRateLimitErrorEvenWithPreviousData() {
        val firstQuota = ClaudeQuota(rawJson = "{\"first\":true}")
        var fetchCount = 0
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient {
                fetchCount++
                if (fetchCount == 1) firstQuota
                else throw ClaudeQuotaException("Claude usage request failed (HTTP 500).", 500)
            },
            tokenProvider = { "token" },
            tokenRefresher = { null },
        )

        provider.refresh()
        provider.refresh()

        assertSame(firstQuota, provider.getLastQuota())
        assertEquals("Claude usage request failed (HTTP 500).", provider.getLastError())
    }

    @Test
    fun refreshRetriesOnceAfterUnauthorizedResponse() {
        val quota = ClaudeQuota(rawJson = "{\"ok\":true}")
        var fetchCount = 0
        var refreshCount = 0
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient {
                fetchCount++
                if (fetchCount == 1) throw ClaudeQuotaException("expired", 401)
                quota
            },
            tokenProvider = { "old-token" },
            tokenRefresher = {
                refreshCount++
                "new-token"
            },
        )

        provider.refresh()

        assertSame(quota, provider.getLastQuota())
        assertEquals(1, refreshCount)
        assertEquals(2, fetchCount)
    }

    @Test
    fun refreshRetriesOnceAfterGenericForbiddenResponse() {
        val quota = ClaudeQuota(rawJson = "{\"ok\":true}")
        var fetchCount = 0
        var refreshCount = 0
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient {
                fetchCount++
                if (fetchCount == 1) throw ClaudeQuotaException("expired", 403, "forbidden")
                quota
            },
            tokenProvider = { "old-token" },
            tokenRefresher = {
                refreshCount++
                "new-token"
            },
        )

        provider.refresh()

        assertSame(quota, provider.getLastQuota())
        assertEquals(1, refreshCount)
        assertEquals(2, fetchCount)
    }

    @Test
    fun refreshDoesNotRotateTokenAfterForbiddenResponse() {
        var refreshCount = 0
        val provider = ClaudeQuotaProvider(
            client = FakeClaudeClient {
                throw ClaudeQuotaException(
                    "Claude token is missing the user:profile scope required for usage.",
                    403,
                    "missing user:profile",
                )
            },
            tokenProvider = { "token" },
            tokenRefresher = {
                refreshCount++
                "new-token"
            },
        )

        provider.refresh()

        assertEquals(0, refreshCount)
        assertEquals("Claude token is missing the user:profile scope required for usage.", provider.getLastError())
    }

    private class FakeClaudeClient(private val fetch: () -> ClaudeQuota) : ClaudeQuotaClient() {
        override fun fetchQuota(accessToken: String?): ClaudeQuota = fetch()
    }
}
