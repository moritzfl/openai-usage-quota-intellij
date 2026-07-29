package de.moritzf.quota.idea.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger

/**
 * One-off cleanup of persisted data, keyed by the settings version that introduced it.
 *
 * The version describes the shape of the stored data, not the plugin release, so it behaves the
 * same in a release, a sandbox, and a build without a stamped plugin version.
 *
 * Migrations must be idempotent: settings written by a newer plugin can be read back by an older
 * one, which drops fields it does not know, so a migration can be asked to run twice.
 */
internal interface QuotaSettingsMigration {
    /** Stable identifier, used for logging only. */
    val id: String

    /** Settings version this migration belongs to; it runs for state below that version. */
    val version: Int

    fun apply(state: QuotaSettingsState)
}

/**
 * Runs pending migrations while [QuotaSettingsState] loads, before any consumer reads settings,
 * and records the reached version in [QuotaSettingsState.settingsVersion].
 */
internal object QuotaSettingsMigrations {
    /** Raise by one whenever a migration is added, and tag the new migration with that value. */
    const val CURRENT_VERSION: Int = 1

    val ALL: List<QuotaSettingsMigration> = listOf(DropOllamaSessionCookieCredentials)

    fun run(state: QuotaSettingsState, migrations: List<QuotaSettingsMigration> = ALL) {
        migrations
            .filter { it.version > state.settingsVersion }
            .sortedBy { it.version }
            .forEach { migration ->
                runCatching { migration.apply(state) }
                    .onFailure { LOG.warn("Settings migration '${migration.id}' failed", it) }
            }
        // Settings written by a newer plugin keep their version, so its migrations are not
        // replayed once that plugin is used again.
        state.settingsVersion = maxOf(state.settingsVersion, CURRENT_VERSION)
    }

    private val LOG = Logger.getInstance(QuotaSettingsMigrations::class.java)
}

/**
 * Ollama Cloud quota used a browser session cookie before the official usage API. The cookies are
 * no longer read, so drop them from Password Safe instead of leaving them behind.
 */
internal object DropOllamaSessionCookieCredentials : QuotaSettingsMigration {
    override val id = "drop-ollama-session-cookie-credentials"
    override val version = 1

    private val obsoleteCredentials = listOf(
        CredentialAttributes("Ollama Session Cookie", "ollama-session"),
        CredentialAttributes("Ollama CF Clearance", "ollama-cf"),
    )

    override fun apply(state: QuotaSettingsState) {
        val application = ApplicationManager.getApplication() ?: return
        // Password Safe can block on the OS keychain, so keep it off the settings-loading thread.
        application.executeOnPooledThread {
            obsoleteCredentials.forEach { credentialAttributes ->
                runCatching {
                    if (PasswordSafe.instance.get(credentialAttributes) != null) {
                        PasswordSafe.instance.set(credentialAttributes, null)
                    }
                }
            }
        }
    }
}
