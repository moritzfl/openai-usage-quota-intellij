package de.moritzf.proxy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CodexReserveHopTest {
    private val hop = CodexReserveHop()

    @Test
    fun rewritesNonReserveResponsesPostToGptReserve() {
        assertTrue(hop.isEligibleRequest("/responses", "POST", """{"model":"gpt-5.6-sol"}"""))
        val rewritten = hop.rewriteRequestToReserve(
            """{"model":"gpt-5.6-sol","reasoning":{"effort":"ultra"},"stream":true}""",
        )
        val root = de.moritzf.proxy.util.Json.INSTANCE.parseToJsonElement(rewritten!!).jsonObject
        assertEquals("gpt-reserve", root["model"]!!.jsonPrimitive.content)
        assertEquals("max", root["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun doesNotHopWhenAlreadyReserve() {
        assertFalse(hop.isEligibleRequest("/responses", "POST", """{"model":"gpt-reserve"}"""))
        assertNull(hop.rewriteRequestToReserve("""{"model":"gpt-reserve"}"""))
    }

    @Test
    fun detectsUsageLimitBodies() {
        assertTrue(hop.isUsageLimit("""{"detail":"You've hit your usage limit. usage_limit_reached"}"""))
        assertFalse(hop.isUsageLimit("""{"detail":"model not found"}"""))
    }
}
