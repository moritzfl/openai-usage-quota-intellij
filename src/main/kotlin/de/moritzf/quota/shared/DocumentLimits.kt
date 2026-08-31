package de.moritzf.quota.shared

import java.nio.file.Files
import java.nio.file.Path

internal object DocumentLimits {
    const val MAX_INLINE_BYTES = 1024L * 1024 * 1024

    fun inlineOverflowMessage(path: Path): String? = inlineOverflowMessage(Files.size(path))

    fun inlineOverflowMessage(size: Long): String? {
        if (size <= MAX_INLINE_BYTES) {
            return null
        }
        return "Local document is too large ($size bytes). Inline conversion supports up to 1 GB. " +
            "Use a public documentUrl instead."
    }
}
