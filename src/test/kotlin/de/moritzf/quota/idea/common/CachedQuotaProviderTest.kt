package de.moritzf.quota.idea.common

import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.ollama.OllamaUsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CachedQuotaProviderTest {
    @Test
    fun transientFailureKeepsTheReadingAndIsMarkedTransient() {
        val provider = TestProvider()
        val quota = quota()
        provider.store(quota)

        provider.fail(statusCode = 0, error = "Request failed. Check your connection.")

        assertSame(quota, provider.getLastQuota(), "an offline refresh must not drop the reading")
        // The error stays available for the settings page, but is marked as self-resolving.
        assertEquals("Request failed. Check your connection.", provider.getLastError())
        assertTrue(provider.isLastErrorTransient())
    }

    @Test
    fun failureThatNeedsUserActionIsNotTransient() {
        val provider = TestProvider()
        provider.store(quota())

        provider.fail(statusCode = 401, error = "API key invalid.")

        assertEquals("API key invalid.", provider.getLastError())
        assertFalse(provider.isLastErrorTransient())
    }

    @Test
    fun aSuccessfulRefreshClearsTheTransientMark() {
        val provider = TestProvider()
        provider.store(quota())
        provider.fail(statusCode = 503, error = "server error")
        assertTrue(provider.isLastErrorTransient())

        provider.store(quota())

        assertNull(provider.getLastError())
        assertFalse(provider.isLastErrorTransient())
    }

    @Test
    fun classifiesFailuresThatPassOnTheirOwn() {
        // No connection or timeout, rate limit, server errors.
        listOf(0, 408, 429, 500, 502, 503).forEach {
            assertTrue(isTransientFetchFailure(it), "$it should be transient")
        }
        // Auth, bad request, missing resource, unreadable payload.
        listOf(200, 400, 401, 403, 404, 422).forEach {
            assertFalse(isTransientFetchFailure(it), "$it should not be transient")
        }
    }

    private fun quota() = OllamaQuota(sessionUsage = OllamaUsageWindow(usagePercent = 10.0))

    private class TestProvider : CachedQuotaProvider<OllamaQuota>() {
        override val type = QuotaProviderType.OLLAMA

        override fun refresh() = Unit

        fun store(quota: OllamaQuota) = storeQuota(quota, quota.rawJson)

        fun fail(statusCode: Int, error: String) = storeFetchFailure(statusCode, error)
    }
}
