package de.moritzf.quota.idea

import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.OpenAiCodexQuotaException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuotaUsageServiceTest {
    @Test
    fun refreshStoresRawResponseFromQuotaException() {
        val rawJson = """{"unexpected":"shape"}"""
        val service = createService(
            quotaFetcher = { _, _ ->
                throw OpenAiCodexQuotaException("Usage response could not be parsed", 200, rawJson)
            },
        )

        try {
            service.refreshNowBlocking()

            assertNull(service.getLastQuota())
            assertEquals("Request failed (200)", service.getLastError())
            assertEquals(rawJson, service.getLastResponseJson())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun clearUsageDataRemovesCachedRawResponse() {
        val rawJson = """{"rate_limit":{"allowed":true}}"""
        val service = createService(
            quotaFetcher = { _, _ ->
                OpenAiCodexQuota(allowed = true).apply {
                    this.rawJson = rawJson
                }
            },
        )

        try {
            service.refreshNowBlocking()
            assertEquals(rawJson, service.getLastResponseJson())

            service.clearUsageData("Not logged in")

            assertNull(service.getLastQuota())
            assertEquals("Not logged in", service.getLastError())
            assertNull(service.getLastResponseJson())
        } finally {
            service.dispose()
        }
    }

    private fun createService(
        quotaFetcher: (String, String?) -> OpenAiCodexQuota,
    ): QuotaUsageService {
        return QuotaUsageService(
            quotaFetcher = quotaFetcher,
            accessTokenProvider = { "token" },
            accountIdProvider = { "account-1" },
            updatePublisher = { _, _ -> },
            scheduleOnInit = false,
        )
    }
}
