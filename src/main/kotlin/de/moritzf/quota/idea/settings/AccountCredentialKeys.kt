package de.moritzf.quota.idea.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

internal object AccountCredentialKeys {
    fun userName(accountId: String, typeId: String, base: String): String {
        return if (accountId == typeId) base else "$base-$accountId"
    }

    fun <T : Any> store(
        accountId: String,
        typeId: String,
        baseService: String,
        baseUser: String,
        extras: java.util.concurrent.ConcurrentHashMap<String, T>,
        defaultStore: () -> T,
        create: (serviceName: String, userName: String) -> T,
    ): T {
        if (accountId == typeId) return defaultStore()
        return extras.computeIfAbsent(accountId) {
            create("$baseService ($accountId)", "$baseUser-$accountId")
        }
    }
}

internal class AccountSecretSlot(
    serviceName: String,
    private val userName: String,
) {
    private val attributes = CredentialAttributes(serviceName, userName)

    fun load(): String? {
        return try {
            PasswordSafe.instance.get(attributes)?.getPasswordAsString()?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun save(value: String?) {
        val stored = value?.trim()?.takeIf { it.isNotEmpty() }
        PasswordSafe.instance.set(attributes, stored?.let { Credentials(userName, it) })
    }

    fun clear() {
        PasswordSafe.instance.set(attributes, null)
    }
}
