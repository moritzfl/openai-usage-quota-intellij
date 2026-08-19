package de.moritzf.quota.mistral

class MistralQuotaException(
    message: String,
    val statusCode: Int = 0,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
