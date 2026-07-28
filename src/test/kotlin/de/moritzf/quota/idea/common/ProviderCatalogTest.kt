package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.mcp.ImageGenerationProvider
import de.moritzf.quota.idea.mcp.ListSearchProvider
import de.moritzf.quota.idea.mcp.UsageQuotaMcpRegistry
import de.moritzf.quota.idea.settings.ProviderSettingsRegistry
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.indicator.ProviderUiRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderCatalogTest {
    @Test
    fun coversEveryQuotaProviderType() {
        assertEquals(QuotaProviderType.entries.toSet(), ProviderCatalog.all.map { it.type }.toSet())
    }

    @Test
    fun facadesExposeSameProviderSet() {
        val catalog = ProviderCatalog.all.map { it.type }.toSet()
        assertEquals(catalog, QuotaProviderRegistry.all.map { it.type }.toSet())
        assertEquals(catalog, ProviderSettingsRegistry.all.keys)
        assertEquals(catalog, ProviderUiRegistry.all.keys)
        assertEquals(catalog, UsageQuotaMcpRegistry.all.keys)
    }

    @Test
    fun proxySupportedMatchesCapabilities() {
        val fromCaps = ProviderCatalog.all.filter { it.capabilities.subscriptionProxy }.map { it.type }
        assertEquals(fromCaps, ProviderCatalog.proxySupportedProviders())
        assertEquals(fromCaps, QuotaSettingsState.SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS)
        assertTrue(QuotaProviderType.CLAUDE !in fromCaps)
        assertTrue(QuotaProviderType.CURSOR !in fromCaps)
        assertTrue(QuotaProviderType.OPEN_AI in fromCaps)
    }

    @Test
    fun mcpSearchEnumsMatchCapabilities() {
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.webSearch == WebSearchCapability.LIST }.map { it.type }.toSet(),
            ListSearchProvider.entries.map { it.providerType }.toSet(),
        )
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.imageGeneration }.map { it.type }.toSet(),
            ImageGenerationProvider.entries.map { it.providerType }.toSet(),
        )
        assertEquals(
            setOf(QuotaProviderType.SUPERGROK),
            ProviderCatalog.all.filter { it.capabilities.videoGeneration }.map { it.type }.toSet(),
        )
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.webSearch == WebSearchCapability.ANSWER }.map { it.type }.toSet(),
            setOf(QuotaProviderType.OPEN_AI, QuotaProviderType.SUPERGROK),
        )
    }

    @Test
    fun oauthProvidersMatchCapability() {
        assertEquals(
            setOf(QuotaProviderType.CLAUDE, QuotaProviderType.OPEN_AI, QuotaProviderType.SUPERGROK),
            ProviderCatalog.oauthProviders().toSet(),
        )
    }
}
