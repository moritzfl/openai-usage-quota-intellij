package de.moritzf.proxy.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CodexClientVersionResolverTest {
    @Test
    fun fallbackSupportsAdvertisedGpt56Models() {
        assertEquals("0.145.0", CodexClientVersionResolver.FALLBACK_CODEX_CLIENT_VERSION)
    }

    @Test
    fun newestVersionPrefersPluginFallbackOverOlderLocal() {
        assertEquals(
            "0.145.0",
            CodexClientVersionResolver.newestVersion("0.145.0", "0.121.0", null),
        )
    }

    @Test
    fun newestVersionAllowsNewerLocalOrNpm() {
        assertEquals(
            "0.150.0",
            CodexClientVersionResolver.newestVersion("0.145.0", "0.150.0", "0.146.0"),
        )
    }

    @Test
    fun newestVersionIgnoresInvalidCandidates() {
        assertEquals(
            "0.145.0",
            CodexClientVersionResolver.newestVersion("0.145.0", "not-a-version", "  "),
        )
    }
}
