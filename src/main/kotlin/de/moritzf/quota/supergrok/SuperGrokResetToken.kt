package de.moritzf.quota.supergrok

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class SuperGrokResetToken(
    val tokenId: String = "",
    val expiresAt: Instant? = null,
)
