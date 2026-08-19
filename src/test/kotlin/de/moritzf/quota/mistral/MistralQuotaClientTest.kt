package de.moritzf.quota.mistral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class MistralQuotaClientTest {
    @Test
    fun parseIdentityReadsOrgWorkspaceAndKeyName() {
        val body = """
            {
              "id": "user-1",
              "email": "user@example.com",
              "workspace": {"id": "ws-1", "name": "Default Workspace"},
              "organization": {"id": "org-1", "name": "Example Org"},
              "api_key": {"id": "key-1", "name": "Test-usage-plugin"}
            }
        """.trimIndent()

        val identity = MistralQuotaClient.parseIdentity(body)

        assertEquals("user@example.com", identity.email)
        assertEquals("Example Org", identity.organization?.name)
        assertEquals("Default Workspace", identity.workspace?.name)
        assertEquals("Test-usage-plugin", identity.apiKey?.name)
    }

    @Test
    fun parseIdentityIgnoresUnknownFields() {
        val identity = MistralQuotaClient.parseIdentity("""{"email":"a@b.c","extra":true}""")
        assertEquals("a@b.c", identity.email)
        assertNull(identity.organization)
    }

    @Test
    fun parseIdentityRejectsUnreadablePayload() {
        assertFailsWith<MistralQuotaException> {
            MistralQuotaClient.parseIdentity("not-json")
        }
    }

    @Test
    fun windowFromValuesComputesUsedPercentAndMinuteReset() {
        val now = Instant.fromEpochMilliseconds(1_780_000_030_000L)
        val window = assertNotNull(MistralQuotaClient.windowFromValues(limit = 500_000, remaining = 499_000, now = now))

        assertEquals(1_000, window.used)
        assertEquals(500_000, window.limit)
        assertEquals(499_000, window.remaining)
        assertEquals(0.2, window.usagePercent, 0.0001)
        assertEquals(60_000, window.periodDurationMs)
        assertEquals(Instant.fromEpochMilliseconds(1_780_000_080_000L), window.resetsAt)
    }

    @Test
    fun windowFromValuesReturnsNullWhenLimitMissing() {
        val now = Instant.fromEpochMilliseconds(1_780_000_000_000L)
        assertNull(MistralQuotaClient.windowFromValues(limit = 0, remaining = 0, now = now))
    }
}
