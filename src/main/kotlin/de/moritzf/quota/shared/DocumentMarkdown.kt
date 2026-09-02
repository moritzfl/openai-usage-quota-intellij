package de.moritzf.quota.shared

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal object DocumentMarkdown {
    private val FENCE = Regex("^```(?:markdown|md)?\\s*\\n([\\s\\S]*?)\\n```\\s*$", RegexOption.IGNORE_CASE)

    fun unwrap(text: String): String {
        val trimmed = text.trim()
        return FENCE.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
    }

    fun defaultOutput(localFile: Path?): Path? {
        if (localFile == null) return null
        val name = localFile.fileName.toString()
        val stem = name.substringBeforeLast('.', name).ifBlank { name }
        return localFile.resolveSibling("$stem.md")
    }

    fun resultJson(
        markdown: String,
        outputFile: Path?,
        imageFiles: List<String> = emptyList(),
        pageCount: Int? = null,
        pageFrom: Int? = null,
        pageTo: Int? = null,
    ): String {
        val cleaned = unwrap(markdown)
        if (outputFile != null) {
            val parent = outputFile.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
            Files.writeString(outputFile, cleaned)
            return JsonSupport.json.encodeToString(
                DocumentMarkdownWriteResult(outputFile.toString(), imageFiles, pageCount, pageFrom, pageTo),
            )
        }
        return JsonSupport.json.encodeToString(DocumentMarkdownTextResult(cleaned, pageCount, pageFrom, pageTo))
    }
}

@Serializable
internal data class DocumentMarkdownWriteResult(
    @SerialName("output_file") val outputFile: String,
    @SerialName("image_files") val imageFiles: List<String> = emptyList(),
    @SerialName("page_count") val pageCount: Int? = null,
    @SerialName("page_from") val pageFrom: Int? = null,
    @SerialName("page_to") val pageTo: Int? = null,
)

@Serializable
internal data class DocumentMarkdownTextResult(
    val markdown: String,
    @SerialName("page_count") val pageCount: Int? = null,
    @SerialName("page_from") val pageFrom: Int? = null,
    @SerialName("page_to") val pageTo: Int? = null,
)
