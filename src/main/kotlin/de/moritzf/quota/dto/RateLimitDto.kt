package de.moritzf.quota.dto

import de.moritzf.quota.UsageWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO describing rate-limit availability and its primary/secondary windows.
 */
@Serializable
data class RateLimitDto(
    val allowed: Boolean? = null,
    @SerialName("limit_reached") val limitReached: Boolean? = null,
    @SerialName("primary_window") val primaryWindow: UsageWindowDto? = null,
    @SerialName("secondary_window") val secondaryWindow: UsageWindowDto? = null,
) {
    fun primaryUsageWindow(): UsageWindow? = primaryWindow?.toUsageWindow()

    fun secondaryUsageWindow(): UsageWindow? = secondaryWindow?.toUsageWindow()
}
