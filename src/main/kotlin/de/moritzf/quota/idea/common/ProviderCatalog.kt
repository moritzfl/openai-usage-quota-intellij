package de.moritzf.quota.idea.common

import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.mcp.UsageQuotaMcpRegistration
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeSessionCookieStore
import de.moritzf.quota.idea.settings.ClaudeSettingsPanel
import de.moritzf.quota.idea.settings.CursorSettingsPanel
import de.moritzf.quota.idea.settings.GitHubSettingsPanel
import de.moritzf.quota.idea.settings.KimiSettingsPanel
import de.moritzf.quota.idea.settings.MiniMaxSettingsPanel
import de.moritzf.quota.idea.settings.OllamaSettingsPanel
import de.moritzf.quota.idea.settings.OpenAiSettingsPanel
import de.moritzf.quota.idea.settings.OpenCodeSettingsPanel
import de.moritzf.quota.idea.settings.ProviderSettingsPanel
import de.moritzf.quota.idea.settings.ProviderSettingsPanelContext
import de.moritzf.quota.idea.settings.SuperGrokSettingsPanel
import de.moritzf.quota.idea.settings.ZaiSettingsPanel
import de.moritzf.quota.idea.ui.indicator.ClaudeUi
import de.moritzf.quota.idea.ui.indicator.CursorUi
import de.moritzf.quota.idea.ui.indicator.GitHubUi
import de.moritzf.quota.idea.ui.indicator.KimiUi
import de.moritzf.quota.idea.ui.indicator.MiniMaxUi
import de.moritzf.quota.idea.ui.indicator.OllamaUi
import de.moritzf.quota.idea.ui.indicator.OpenAiUi
import de.moritzf.quota.idea.ui.indicator.OpenCodeUi
import de.moritzf.quota.idea.ui.indicator.ProviderUi
import de.moritzf.quota.idea.ui.indicator.SuperGrokUi
import de.moritzf.quota.idea.ui.indicator.ZaiUi
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.supergrok.SuperGrokQuota
import de.moritzf.quota.zai.ZaiQuota

internal enum class WebSearchCapability {
    NONE,
    ANSWER,
    LIST,
}

internal data class ProviderCapabilities(
    val webSearch: WebSearchCapability = WebSearchCapability.NONE,
    val imageGeneration: Boolean = false,
    val videoGeneration: Boolean = false,
    val subscriptionProxy: Boolean = false,
    val oauth: Boolean = false,
)

/**
 * Single registration row for a subscription provider.
 * New providers add one entry here (plus type/enum/docs assets as needed).
 */
internal data class ProviderDescriptor(
    val type: QuotaProviderType,
    val capabilities: ProviderCapabilities = ProviderCapabilities(),
    val quotaFactory: () -> QuotaProvider,
    val snapshotCodec: QuotaCodec<out ProviderQuota>,
    val mcpQuota: UsageQuotaMcpRegistration,
    val settingsPanelFactory: (ProviderSettingsPanelContext) -> ProviderSettingsPanel,
    val ui: ProviderUi,
    /** Blocking credential check for MCP status / background work. */
    val isQuotaConfigured: () -> Boolean,
    val isWebSearchConfigured: () -> Boolean = { false },
    /**
     * Proxy-credential check. [onCredentialsLoaded] is invoked when PasswordSafe finishes an async load
     * (settings UI refresh). Null for blocking-only callers.
     */
    val isProxyConfigured: (onCredentialsLoaded: (() -> Unit)?) -> Boolean = { _ -> false },
    val webSearchMissingReason: String? = null,
    /** IDE subscription-proxy construction; null when [ProviderCapabilities.subscriptionProxy] is false. */
    val ideProxyFactory: ((IdeProxyBuildContext) -> SubscriptionProxyProvider)? = null,
) {
    val webSearchType: String?
        get() = when (capabilities.webSearch) {
            WebSearchCapability.ANSWER -> "answer"
            WebSearchCapability.LIST -> "list"
            WebSearchCapability.NONE -> null
        }
}

/**
 * Canonical provider catalog. Other registries are thin facades over this.
 */
internal object ProviderCatalog {
    val all: List<ProviderDescriptor> = listOf(
        descriptor(
            type = QuotaProviderType.CLAUDE,
            capabilities = ProviderCapabilities(oauth = true),
            quotaFactory = ::ClaudeQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(ClaudeQuota.serializer()),
            mcpEmpty = "No Claude usage response available",
            settings = { ctx -> ClaudeSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = ClaudeUi,
            isQuotaConfigured = { oauthAccessTokenPresent(QuotaProviderType.CLAUDE) },
        ),
        descriptor(
            type = QuotaProviderType.CURSOR,
            quotaFactory = ::CursorQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(CursorQuota.serializer()),
            mcpQuota = UsageQuotaMcpRegistration(
                emptyMessage = "No Cursor usage response available",
                json = { service, type -> service.getLastResponseJson(type)?.let(CursorQuotaClient::normalizeRawJson) },
            ),
            settings = { ctx -> CursorSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = CursorUi,
            isQuotaConfigured = {
                !CursorCredentialsStore.getInstance().loadBlocking()?.accessToken.isNullOrBlank()
            },
        ),
        descriptor(
            type = QuotaProviderType.GITHUB,
            capabilities = ProviderCapabilities(subscriptionProxy = true),
            quotaFactory = ::GitHubQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(GitHubQuota.serializer()),
            mcpEmpty = "No GitHub usage response available",
            settings = { ctx -> GitHubSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = GitHubUi,
            isQuotaConfigured = {
                !GitHubCredentialsStore.getInstance().loadBlocking()?.accessToken.isNullOrBlank()
            },
            isProxyConfigured = { onLoaded ->
                GitHubCredentialsStore.getInstance().load(onLoaded = onLoaded)?.isUsable() == true
            },
            ideProxyFactory = IdeProxyFactories::github,
        ),
        descriptor(
            type = QuotaProviderType.KIMI,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.LIST,
                subscriptionProxy = true,
            ),
            quotaFactory = ::KimiQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(KimiQuota.serializer()),
            mcpEmpty = "No Kimi usage response available",
            settings = { ctx -> KimiSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = KimiUi,
            isQuotaConfigured = {
                KimiCredentialsStore.getInstance().loadBlocking()?.isUsable() == true
            },
            isWebSearchConfigured = {
                KimiCredentialsStore.getInstance().loadBlocking()?.isUsable() == true
            },
            isProxyConfigured = { onLoaded ->
                KimiCredentialsStore.getInstance().load(onLoaded = onLoaded)?.isUsable() == true
            },
            webSearchMissingReason = "Kimi login required. Log in from settings.",
            ideProxyFactory = IdeProxyFactories::kimi,
        ),
        descriptor(
            type = QuotaProviderType.MINIMAX,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.LIST,
                subscriptionProxy = true,
            ),
            quotaFactory = ::MiniMaxQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(MiniMaxQuota.serializer()),
            mcpEmpty = "No MiniMax usage response available",
            settings = { ctx -> MiniMaxSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = MiniMaxUi,
            isQuotaConfigured = { !MiniMaxApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isWebSearchConfigured = { !MiniMaxApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isProxyConfigured = { onLoaded ->
                !MiniMaxApiKeyStore.getInstance().load(onLoaded = onLoaded).isNullOrBlank()
            },
            webSearchMissingReason = "MiniMax API key missing. Add a MiniMax API key in settings.",
            ideProxyFactory = IdeProxyFactories::miniMax,
        ),
        descriptor(
            type = QuotaProviderType.OLLAMA,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.LIST,
                subscriptionProxy = true,
            ),
            quotaFactory = ::OllamaQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(OllamaQuota.serializer()),
            mcpEmpty = "No Ollama usage response available",
            settings = { ctx -> OllamaSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = OllamaUi,
            // Quota, proxy, and web search all use the Ollama API key.
            isQuotaConfigured = { !OllamaApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isWebSearchConfigured = { !OllamaApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isProxyConfigured = { onLoaded ->
                !OllamaApiKeyStore.getInstance().load(onLoaded = onLoaded).isNullOrBlank()
            },
            webSearchMissingReason = "Ollama API key missing. Add an Ollama API key in settings.",
            ideProxyFactory = IdeProxyFactories::ollama,
        ),
        descriptor(
            type = QuotaProviderType.OPEN_AI,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.ANSWER,
                imageGeneration = true,
                subscriptionProxy = true,
                oauth = true,
            ),
            quotaFactory = ::OpenAiQuotaProvider,
            snapshotCodec = OpenAiQuotaCodec,
            mcpEmpty = "No usage response available",
            settings = { ctx -> OpenAiSettingsPanel(ctx.modalityComponentProvider) },
            ui = OpenAiUi,
            isQuotaConfigured = { oauthAccessTokenPresent(QuotaProviderType.OPEN_AI) },
            isWebSearchConfigured = { oauthAccessTokenPresent(QuotaProviderType.OPEN_AI) },
            isProxyConfigured = { _ -> QuotaAuthService.getInstance().isLoggedIn(QuotaProviderType.OPEN_AI) },
            webSearchMissingReason = "OpenAI login required. Log in from settings.",
            ideProxyFactory = IdeProxyFactories::openAi,
        ),
        descriptor(
            type = QuotaProviderType.OPEN_CODE,
            capabilities = ProviderCapabilities(subscriptionProxy = true),
            quotaFactory = ::OpenCodeQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(OpenCodeQuota.serializer()),
            mcpQuota = UsageQuotaMcpRegistration(
                emptyMessage = "No OpenCode usage response available",
                json = { service, _ ->
                    val quota = service.getLastQuota(QuotaProviderType.OPEN_CODE) as? OpenCodeQuota
                    quota?.let { runCatching { JsonSupport.json.encodeToString(OpenCodeQuota.serializer(), it) }.getOrNull() }
                },
            ),
            settings = { ctx -> OpenCodeSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = OpenCodeUi,
            // Quota uses session cookie; proxy uses API key.
            isQuotaConfigured = {
                !OpenCodeSessionCookieStore.getInstance().loadBlocking().isNullOrBlank()
            },
            isProxyConfigured = { onLoaded ->
                !OpenCodeApiKeyStore.getInstance().load(onLoaded = onLoaded).isNullOrBlank()
            },
            ideProxyFactory = IdeProxyFactories::openCode,
        ),
        descriptor(
            type = QuotaProviderType.SUPERGROK,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.ANSWER,
                imageGeneration = true,
                videoGeneration = true,
                subscriptionProxy = true,
                oauth = true,
            ),
            quotaFactory = ::SuperGrokQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(SuperGrokQuota.serializer()),
            mcpEmpty = "No SuperGrok usage response available",
            settings = { ctx -> SuperGrokSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = SuperGrokUi,
            isQuotaConfigured = { oauthAccessTokenPresent(QuotaProviderType.SUPERGROK) },
            isWebSearchConfigured = { oauthAccessTokenPresent(QuotaProviderType.SUPERGROK) },
            isProxyConfigured = { _ -> QuotaAuthService.getInstance().isLoggedIn(QuotaProviderType.SUPERGROK) },
            webSearchMissingReason = "Grok login required. Log in from SuperGrok settings.",
            ideProxyFactory = IdeProxyFactories::superGrok,
        ),
        descriptor(
            type = QuotaProviderType.ZAI,
            capabilities = ProviderCapabilities(
                webSearch = WebSearchCapability.LIST,
                subscriptionProxy = true,
            ),
            quotaFactory = ::ZaiQuotaProvider,
            snapshotCodec = EnvelopeQuotaCodec(ZaiQuota.serializer()),
            mcpEmpty = "No Z.ai usage response available",
            settings = { ctx -> ZaiSettingsPanel(ctx.modalityComponentProvider, ctx.statusLabelDefaultForeground) },
            ui = ZaiUi,
            isQuotaConfigured = { !ZaiApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isWebSearchConfigured = { !ZaiApiKeyStore.getInstance().loadBlocking().isNullOrBlank() },
            isProxyConfigured = { onLoaded ->
                !ZaiApiKeyStore.getInstance().load(onLoaded = onLoaded).isNullOrBlank()
            },
            webSearchMissingReason = "Z.ai API key missing. Add a Z.ai API key in settings.",
            ideProxyFactory = IdeProxyFactories::zai,
        ),
    )

    private val byType: Map<QuotaProviderType, ProviderDescriptor> = all.associateBy { it.type }

    init {
        val missing = QuotaProviderType.entries.filter { it !in byType }
        check(missing.isEmpty()) { "ProviderCatalog missing types: $missing" }
        val proxyWithoutFactory = all.filter { it.capabilities.subscriptionProxy && it.ideProxyFactory == null }
        check(proxyWithoutFactory.isEmpty()) {
            "subscriptionProxy providers missing ideProxyFactory: ${proxyWithoutFactory.map { it.type }}"
        }
        val factoryWithoutFlag = all.filter { !it.capabilities.subscriptionProxy && it.ideProxyFactory != null }
        check(factoryWithoutFlag.isEmpty()) {
            "ideProxyFactory set without subscriptionProxy: ${factoryWithoutFlag.map { it.type }}"
        }
    }

    fun get(type: QuotaProviderType): ProviderDescriptor = byType.getValue(type)

    fun getOrNull(type: QuotaProviderType): ProviderDescriptor? = byType[type]

    fun createQuotaProviders(): List<QuotaProvider> = all.map { it.quotaFactory() }

    fun defaultProviderOrder(): List<QuotaProviderType> = all.map { it.type }.sortedBy { it.displayName }

    fun defaultProviderOrderStorageValue(): String = defaultProviderOrder().joinToString(",") { it.id }

    fun proxySupportedProviders(): List<QuotaProviderType> =
        all.filter { it.capabilities.subscriptionProxy }.map { it.type }

    fun oauthProviders(): List<QuotaProviderType> =
        all.filter { it.capabilities.oauth }.map { it.type }

    /**
     * Builds IDE subscription-proxy providers for [enabled] types (catalog order among proxy-capable entries).
     */
    fun createIdeProxyProviders(
        context: IdeProxyBuildContext,
        enabled: Set<QuotaProviderType>,
    ): List<SubscriptionProxyProvider> {
        return all.mapNotNull { descriptor ->
            if (descriptor.type !in enabled) return@mapNotNull null
            val factory = descriptor.ideProxyFactory ?: return@mapNotNull null
            factory(context)
        }
    }

    fun mergeProviderOrder(storedOrder: List<QuotaProviderType>): List<QuotaProviderType> {
        val allProviders = defaultProviderOrder()
        val validStored = storedOrder.filter { it in allProviders }
        if (validStored.isEmpty()) {
            return allProviders
        }

        val result = validStored.toMutableList()
        val missing = allProviders.filter { it !in result }
        for (provider in missing) {
            val providerIndex = allProviders.indexOf(provider)
            if (providerIndex == 0) {
                result.add(0, provider)
                continue
            }

            val predecessor = allProviders[providerIndex - 1]
            val insertAfter = result.indexOfLast { it == predecessor }
            val insertIndex = if (insertAfter >= 0) {
                insertAfter + 1
            } else {
                val fallback = result.indexOfLast { allProviders.indexOf(it) < providerIndex }
                if (fallback >= 0) fallback + 1 else 0
            }
            result.add(insertIndex, provider)
        }
        return result
    }

    private fun oauthAccessTokenPresent(type: QuotaProviderType): Boolean {
        return QuotaAuthService.getInstance().hasCredentialsBlocking(type)
    }

    private fun descriptor(
        type: QuotaProviderType,
        capabilities: ProviderCapabilities = ProviderCapabilities(),
        quotaFactory: () -> QuotaProvider,
        snapshotCodec: QuotaCodec<out ProviderQuota>,
        mcpEmpty: String? = null,
        mcpQuota: UsageQuotaMcpRegistration? = null,
        settings: (ProviderSettingsPanelContext) -> ProviderSettingsPanel,
        ui: ProviderUi,
        isQuotaConfigured: () -> Boolean,
        isWebSearchConfigured: () -> Boolean = { false },
        isProxyConfigured: (onCredentialsLoaded: (() -> Unit)?) -> Boolean = { _ -> false },
        webSearchMissingReason: String? = null,
        ideProxyFactory: ((IdeProxyBuildContext) -> SubscriptionProxyProvider)? = null,
    ): ProviderDescriptor {
        return ProviderDescriptor(
            type = type,
            capabilities = capabilities,
            quotaFactory = quotaFactory,
            snapshotCodec = snapshotCodec,
            mcpQuota = mcpQuota ?: UsageQuotaMcpRegistration(mcpEmpty ?: "No usage response available"),
            settingsPanelFactory = settings,
            ui = ui,
            isQuotaConfigured = isQuotaConfigured,
            isWebSearchConfigured = isWebSearchConfigured,
            isProxyConfigured = isProxyConfigured,
            webSearchMissingReason = webSearchMissingReason,
            ideProxyFactory = ideProxyFactory,
        )
    }
}
