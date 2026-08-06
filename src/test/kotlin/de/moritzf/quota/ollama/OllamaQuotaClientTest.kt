package de.moritzf.quota.ollama

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class OllamaQuotaClientTest {
    @Test
    fun parseQuotaConvertsUsageFractionsToPercent() {
        val json = """
            {
              "activity": {
                "cost": "0.00000",
                "period": {
                  "type": "last_4_weeks",
                  "starting_at": "2026-07-06T00:00:00Z",
                  "ending_at": "2026-07-29T12:45:50Z"
                },
                "models": []
              },
              "limits": {
                "session": {
                  "usage": 0.046,
                  "models": [
                    { "name": "glm-5.2", "request_count": 34 }
                  ]
                },
                "weekly": {
                  "usage": 0.051,
                  "models": [
                    { "name": "glm-5.2", "request_count": 254 }
                  ]
                }
              }
            }
        """.trimIndent()

        val quota = OllamaQuotaClient.parseQuota(json)

        val session = assertNotNull(quota.sessionUsage)
        assertEquals(4.6, session.usagePercent, absoluteTolerance = 0.0001)
        assertNull(session.resetsAt)
        val weekly = assertNotNull(quota.weeklyUsage)
        assertEquals(5.1, weekly.usagePercent, absoluteTolerance = 0.0001)
    }

    @Test
    fun parseQuotaAcceptsPercentValuesAboveOne() {
        val json = """
            {
              "limits": {
                "session": { "usage": 12.5 },
                "weekly": { "usage": 50 }
              }
            }
        """.trimIndent()

        val quota = OllamaQuotaClient.parseQuota(json)

        assertEquals(12.5, assertNotNull(quota.sessionUsage).usagePercent, absoluteTolerance = 0.0001)
        assertEquals(50.0, assertNotNull(quota.weeklyUsage).usagePercent, absoluteTolerance = 0.0001)
    }

    @Test
    fun parseQuotaWithMissingLimitsThrows() {
        val exception = assertFailsWith<OllamaQuotaException> {
            OllamaQuotaClient.parseQuota("""{"activity":{}}""")
        }
        assertEquals(200, exception.statusCode)
    }

    @Test
    fun parseQuotaAllowsSessionOnly() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"limits":{"session":{"usage":0.9}}}""",
        )
        assertEquals(90.0, assertNotNull(quota.sessionUsage).usagePercent, absoluteTolerance = 0.0001)
        assertNull(quota.weeklyUsage)
    }

    @Test
    fun parseQuotaIgnoresUnknownAndReshapedSiblingSections() {
        val json = """
            {
              "plan": { "name": "pro" },
              "credits": { "granted": "unknown-shape" },
              "activity": "not-an-object",
              "limits": {
                "session": { "usage": { "used": 4, "limit": 100 } },
                "weekly": {
                  "usage": 0.051,
                  "models": [ { "name": "glm-5.2", "request_count": "many" } ],
                  "future_field": { "nested": true }
                },
                "daily": { "usage": 0.2 }
              }
            }
        """.trimIndent()

        val quota = OllamaQuotaClient.parseQuota(json)

        // Reshaped session block drops only itself; weekly usage still shows.
        // Extra root fields (plan/credits/activity) are ignored entirely.
        assertNull(quota.sessionUsage)
        assertEquals(5.1, assertNotNull(quota.weeklyUsage).usagePercent, absoluteTolerance = 0.0001)
    }

    @Test
    fun parseQuotaAcceptsNumericStringUsage() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"limits":{"session":{"usage":"0.25"},"weekly":{"usage":"7.5"}}}""",
        )

        assertEquals(25.0, assertNotNull(quota.sessionUsage).usagePercent, absoluteTolerance = 0.0001)
        assertEquals(7.5, assertNotNull(quota.weeklyUsage).usagePercent, absoluteTolerance = 0.0001)
    }

    @Test
    fun parseQuotaReadsResetTimestampWhenPresent() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"limits":{"session":{"usage":0.1,"resets_at":"2026-07-29T18:00:00Z"},"weekly":{"usage":0.2,"resets_at":"nonsense"}}}""",
        )

        assertEquals(
            Instant.parse("2026-07-29T18:00:00Z"),
            assertNotNull(quota.sessionUsage).resetsAt,
        )
        // An unparsable timestamp only drops the reset time, not the usage value.
        assertNull(assertNotNull(quota.weeklyUsage).resetsAt)
        assertEquals(20.0, assertNotNull(quota.weeklyUsage).usagePercent, absoluteTolerance = 0.0001)
    }

    @Test
    fun parseQuotaClampsOutOfRangeUsage() {
        val quota = OllamaQuotaClient.parseQuota(
            """{"limits":{"session":{"usage":-0.5},"weekly":{"usage":250}}}""",
        )

        assertEquals(0.0, assertNotNull(quota.sessionUsage).usagePercent, absoluteTolerance = 0.0001)
        assertEquals(100.0, assertNotNull(quota.weeklyUsage).usagePercent, absoluteTolerance = 0.0001)
    }
}
