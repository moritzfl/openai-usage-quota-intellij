package de.moritzf.quota.idea.settings

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
