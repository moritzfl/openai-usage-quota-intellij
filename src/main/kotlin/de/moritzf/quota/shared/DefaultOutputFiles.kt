package de.moritzf.quota.shared

import java.util.UUID

internal object DefaultOutputFiles {
    fun speech(format: String): String = "speech-${UUID.randomUUID()}.$format"
}
