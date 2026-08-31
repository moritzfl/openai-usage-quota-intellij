package de.moritzf.quota.shared

import java.util.UUID

internal object DefaultOutputFiles {
    fun speech(format: String): String = "speech-${UUID.randomUUID()}.$format"

    fun image(): String = "image-${UUID.randomUUID()}.png"
}
