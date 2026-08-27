package de.moritzf.quota.idea.common

import de.moritzf.proxy.subscription.SubscriptionProxyProvider
import de.moritzf.quota.github.GitHubQuotaClient
import de.moritzf.quota.github.proxy.GitHubCopilotSubscriptionProxyProvider
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.proxy.KimiSubscriptionProxyProvider
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.minimax.proxy.MiniMaxSubscriptionProxyProvider
import de.moritzf.quota.mistral.proxy.MistralSubscriptionProxyProvider
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
    val mistralApiKey: () -> MistralApiKeyStore = { MistralApiKeyStore.getInstance() },
    val ollamaApiKey: () -> OllamaApiKeyStore = { OllamaApiKeyStore.getInstance() },
    val openCodeApiKey: () -> OpenCodeApiKeyStore = { OpenCodeApiKeyStore.getInstance() },
    val zaiApiKey: () -> ZaiApiKeyStore = { ZaiApiKeyStore.getInstance() },
)

internal object IdeProxyFactories {
    fun openAi(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return OpenAiCodexSubscriptionProxyProvider(
            accessTokenProvider = {
                resolvedAccount(QuotaProviderType.OPEN_AI)?.let { account ->
                    ctx.authService().getAccessTokenBlocking(account.id, QuotaProviderType.OPEN_AI)
                }
            },
            accountIdProvider = {
                resolvedAccount(QuotaProviderType.OPEN_AI)?.let { account ->
                    ctx.authService().getAccountId(account.id, QuotaProviderType.OPEN_AI)
                }
            },
            // Upstream 401s route back to the IDE auth service, which owns refresh
            // and persistence; the stale token lets it dedupe concurrent refreshes.
            tokenRefresher = { staleToken ->
                refreshToken(ctx, QuotaProviderType.OPEN_AI, staleToken)
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun superGrok(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return SuperGrokSubscriptionProxyProvider(
            accessTokenProvider = {
                resolvedAccount(QuotaProviderType.SUPERGROK)?.let { account ->
                    ctx.authService().getAccessTokenBlocking(account.id, QuotaProviderType.SUPERGROK)
                }
            },
            tokenRefresher = { staleToken ->
                refreshToken(ctx, QuotaProviderType.SUPERGROK, staleToken)
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun github(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return GitHubCopilotSubscriptionProxyProvider(
            accessTokenProvider = {
                resolvedAccount(QuotaProviderType.GITHUB)?.let { account ->
                    GitHubCredentialsStore.forAccount(account.id).loadBlocking()?.accessToken
                }
            },
            upstreamBaseUri = githubCopilotBaseUri(
                ctx.settings.githubHostFor(
                    resolvedAccount(QuotaProviderType.GITHUB)?.id ?: QuotaProviderType.GITHUB.id,
                ),
            ),
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
            credentialsProvider = {
                resolvedAccount(QuotaProviderType.KIMI)?.let { account ->
                    KimiCredentialsStore.forAccount(account.id).loadBlocking()
                }
            },
            credentialsSaver = { credentials ->
                resolvedAccount(QuotaProviderType.KIMI)?.let { account ->
                    KimiCredentialsStore.forAccount(account.id).save(credentials)
                }
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun miniMax(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return MiniMaxSubscriptionProxyProvider(
            apiKeyProvider = {
                resolvedAccount(QuotaProviderType.MINIMAX)?.let { account ->
                    MiniMaxApiKeyStore.forAccount(account.id).loadBlocking()
                }
            },
            regionProvider = {
                val account = resolvedAccount(QuotaProviderType.MINIMAX)
                miniMaxProxyRegion(ctx.settings.miniMaxRegionFor(account?.id ?: QuotaProviderType.MINIMAX.id))
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun mistral(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return MistralSubscriptionProxyProvider(
            apiKeyProvider = {
                resolvedAccount(QuotaProviderType.MISTRAL)?.let { account ->
                    MistralApiKeyStore.forAccount(account.id).loadBlocking()
                }
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun ollama(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return OllamaSubscriptionProxyProvider(
            apiKeyProvider = {
                resolvedAccount(QuotaProviderType.OLLAMA)?.let { account ->
                    OllamaApiKeyStore.forAccount(account.id).loadBlocking()
                }
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun openCode(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return OpenCodeZenSubscriptionProxyProvider(
            apiKeyProvider = {
                resolvedAccount(QuotaProviderType.OPEN_CODE)?.let { account ->
                    OpenCodeApiKeyStore.forAccount(account.id).loadBlocking()
                }
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    fun zai(ctx: IdeProxyBuildContext): SubscriptionProxyProvider {
        return ZaiSubscriptionProxyProvider(
            apiKeyProvider = {
                resolvedAccount(QuotaProviderType.ZAI)?.let { account ->
                    ZaiApiKeyStore.forAccount(account.id).loadBlocking()
                }
            },
            fullRequestLogging = ctx.logRequests,
            requestLogDir = ctx.requestLogDir,
        )
    }

    private fun resolvedAccount(type: QuotaProviderType): de.moritzf.quota.idea.settings.ProviderAccount? {
        return de.moritzf.quota.idea.settings.AccountResolver.resolveOrNull(
            type,
            capability = de.moritzf.quota.idea.settings.AccountCapability.PROXY,
        )
    }

    private fun refreshToken(
        ctx: IdeProxyBuildContext,
        type: QuotaProviderType,
        staleToken: String?,
    ): String? {
        val auth = ctx.authService()
        val owner = staleToken?.let { token ->
            ctx.settings.accountsOf(type).firstOrNull { account ->
                auth.peekAccessToken(account.id, type) == token
            }
        }
        val account = owner ?: resolvedAccount(type) ?: return null
        return auth.forceRefreshBlocking(account.id, type, staleToken)
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
