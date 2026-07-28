package de.moritzf.quota.idea.common

import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.quota.github.GitHubQuotaClient
import de.moritzf.quota.github.proxy.GitHubCopilotSubscriptionProxyProvider
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.proxy.KimiSubscriptionProxyProvider
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.minimax.proxy.MiniMaxSubscriptionProxyProvider
import de.moritzf.quota.ollama.proxy.OllamaSubscriptionProxyProvider
import de.moritzf.quota.openai.proxy.OpenAiCodexSubscriptionProxyProvider
import de.moritzf.quota.opencode.proxy.OpenCodeZenSubscriptionProxyProvider
import de.moritzf.quota.supergrok.proxy.SuperGrokSubscriptionProxyProvider
import de.moritzf.quota.zai.proxy.ZaiSubscriptionProxyProvider
import java.net.URI

/** Dependencies needed to construct IDE subscription-proxy providers. */
internal data class IdeProxyBuildContext(
    val settings: QuotaSettingsState,
    val logRequests: Boolean,
    val requestLogDir: String,
    val authService: () -> QuotaAuthService = { QuotaAuthService.getInstance() },
    val githubCredentials: () -> GitHubCredentialsStore = { GitHubCredentialsStore.getInstance() },
    val kimiCredentials: () -> KimiCredentialsStore = { KimiCredentialsStore.getInstance() },
    val miniMaxApiKey: () -> MiniMaxApiKeyStore = { MiniMaxApiKeyStore.getInstance() },
    val ollamaApiKey: () -> OllamaApiKeyStore = { OllamaApiKeyStore.getInstance() },
    val openCodeApiKey: () -> OpenCodeApiKeyStore = { OpenCodeApiKeyStore.getInstance() },
    val zaiApiKey: () -> ZaiApiKeyStore = { ZaiApiKeyStore.getInstance() },
)

internal object IdeProxyFactories {
    fun openAi(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return OpenAiCodexSubscriptionProxyProvider(
            accessTokenProvider = { ctx.authService().getAccessTokenBlocking(QuotaProviderType.OPEN_AI) },
            accountIdProvider = { ctx.authService().getAccountId(QuotaProviderType.OPEN_AI) },
            // Upstream 401s route back to the IDE auth service, which owns refresh
            // and persistence; the stale token lets it dedupe concurrent refreshes.
            tokenRefresher = { staleToken ->
                ctx.authService().forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleToken)
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun superGrok(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return SuperGrokSubscriptionProxyProvider(
            accessTokenProvider = { ctx.authService().getAccessTokenBlocking(QuotaProviderType.SUPERGROK) },
            tokenRefresher = { staleToken ->
                ctx.authService().forceRefreshBlocking(QuotaProviderType.SUPERGROK, staleToken)
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun github(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return GitHubCopilotSubscriptionProxyProvider(
            accessTokenProvider = { ctx.githubCredentials().loadBlocking()?.accessToken },
            upstreamBaseUri = githubCopilotBaseUri(ctx.settings.githubEnterpriseHost),
            persistentModelCacheProvider = {
                ctx.settings.subscriptionProxyModelCatalogJson(GitHubCopilotSubscriptionProxyProvider.ID)
            },
            persistentModelCacheSaver = { json ->
                ctx.settings.setSubscriptionProxyModelCatalogJson(GitHubCopilotSubscriptionProxyProvider.ID, json)
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun kimi(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return KimiSubscriptionProxyProvider(
            credentialsProvider = { ctx.kimiCredentials().loadBlocking() },
            credentialsSaver = { credentials -> ctx.kimiCredentials().save(credentials) },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun miniMax(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return MiniMaxSubscriptionProxyProvider(
            apiKeyProvider = { ctx.miniMaxApiKey().loadBlocking() },
            regionProvider = { miniMaxProxyRegion(ctx.settings.miniMaxRegionPreference()) },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun ollama(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return OllamaSubscriptionProxyProvider(
            apiKeyProvider = { ctx.ollamaApiKey().loadBlocking() },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun openCode(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return OpenCodeZenSubscriptionProxyProvider(
            apiKeyProvider = { ctx.openCodeApiKey().loadBlocking() },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun zai(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return ZaiSubscriptionProxyProvider(
            apiKeyProvider = { ctx.zaiApiKey().loadBlocking() },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun githubCopilotBaseUri(enterpriseHost: String): URI {
        val host = GitHubQuotaClient.normalizedEnterpriseHost(enterpriseHost)
        if (host == "github.com") return GitHubCopilotSubscriptionProxyProvider.DEFAULT_UPSTREAM_BASE_URI
        return URI.create("https://copilot-api.$host")
    }

    fun miniMaxProxyRegion(preference: MiniMaxRegionPreference): MiniMaxRegion {
        return when (preference) {
            MiniMaxRegionPreference.CN -> MiniMaxRegion.CN
            MiniMaxRegionPreference.GLOBAL,
            MiniMaxRegionPreference.AUTO -> MiniMaxRegion.GLOBAL
        }
    }
}
