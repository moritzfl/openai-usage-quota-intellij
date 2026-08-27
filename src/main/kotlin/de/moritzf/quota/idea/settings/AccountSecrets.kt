package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import de.moritzf.quota.idea.github.GitHubAuthService
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiAuthService
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.mistral.MistralSessionCookieStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeSessionCookieStore
import de.moritzf.quota.idea.zai.ZaiApiKeyStore

internal object AccountSecrets {
    fun clear(account: ProviderAccount) {
        val id = account.id
        when (account.providerType()) {
            QuotaProviderType.OPEN_AI,
            QuotaProviderType.CLAUDE,
            QuotaProviderType.SUPERGROK,
            -> runCatching {
                val type = account.providerType()!!
                QuotaAuthService.getInstance().clearCredentials(id, type)
                QuotaAuthService.getInstance().forgetAccount(id)
            }
            QuotaProviderType.MISTRAL -> {
                MistralSessionCookieStore.forAccount(id).clear()
                MistralApiKeyStore.forAccount(id).clear()
            }
            QuotaProviderType.OLLAMA -> OllamaApiKeyStore.forAccount(id).clear()
            QuotaProviderType.ZAI -> ZaiApiKeyStore.forAccount(id).clear()
            QuotaProviderType.MINIMAX -> MiniMaxApiKeyStore.forAccount(id).clear()
            QuotaProviderType.CURSOR -> CursorCredentialsStore.forAccount(id).clearSessionCookie()
            QuotaProviderType.OPEN_CODE -> {
                OpenCodeSessionCookieStore.forAccount(id).clear()
                OpenCodeApiKeyStore.forAccount(id).clear()
            }
            QuotaProviderType.GITHUB -> {
                runCatching { GitHubAuthService.forAccount(id).clearCredentials() }
                GitHubCredentialsStore.forAccount(id).clear()
                GitHubAuthService.forgetAccount(id)
            }
            QuotaProviderType.KIMI -> {
                runCatching { KimiAuthService.forAccount(id).clearCredentials() }
                KimiCredentialsStore.forAccount(id).clear()
                KimiAuthService.forgetAccount(id)
            }
            null -> Unit
        }
    }
}
