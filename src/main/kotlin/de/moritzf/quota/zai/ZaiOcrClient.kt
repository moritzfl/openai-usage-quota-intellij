package de.moritzf.quota.zai

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

open class ZaiOcrClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val layoutParsingUri: URI = LAYOUT_PARSING_URI,
) {
    open fun convertDocument(
        apiKey: String,
        documentUrl: String? = null,
        localFile: Path? = null,
        outputFile: Path? = null,
        includeImages: Boolean = true,
        model: String = DEFAULT_MODEL,
    ): String {
        val token = apiKey.trim().ifBlank {
            throw ZaiQuotaException("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        val file = resolveFile(documentUrl, localFile)
        val markdownOutput = outputFile ?: defaultMarkdownOutput(localFile)
        val body = JsonSupport.json.encodeToString(
            ZaiLayoutParsingRequestDto(
                model = model.trim().ifBlank { DEFAULT_MODEL },
                file = file,
                returnCropImages = includeImages,
            ),
        )
        val response = send(postJson(token, body))
        val status = response.statusCode()
        val responseBody = response.body()
        if (status == 401 || status == 403) {
            throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", status, responseBody)
        }
        if (status !in 200..299) {
            throw ZaiQuotaException("Z.ai OCR failed (HTTP $status). Try again later.", status, responseBody)
        }
        if (markdownOutput == null) {
            return McpJson.providerJsonOrRaw(responseBody)
        }
        val written = writeMarkdown(responseBody, markdownOutput)
        return JsonSupport.json.encodeToString(written)
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    private fun postJson(apiKey: String, body: String): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(layoutParsingUri)
            .timeout(Duration.ofSeconds(180))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    companion object {
        const val DEFAULT_MODEL = "glm-ocr"
        private val LAYOUT_PARSING_URI = URI.create("https://api.z.ai/api/paas/v4/layout_parsing")

        fun createDefault(): ZaiOcrClient = ZaiOcrClient()

        internal fun resolveFile(documentUrl: String?, localFile: Path?): String {
            val url = documentUrl?.trim().orEmpty()
            if (url.isNotEmpty()) return url
            val path = localFile ?: throw ZaiQuotaException("Provide documentUrl or a local file path.")
            if (!Files.isRegularFile(path)) {
                throw ZaiQuotaException("Local document was not found.")
            }
            val bytes = Files.readAllBytes(path)
            return "data:${mimeType(path, bytes)};base64,${Base64.getEncoder().encodeToString(bytes)}"
        }

        internal fun defaultMarkdownOutput(localFile: Path?): Path? {
            if (localFile == null) return null
            val name = localFile.fileName.toString()
            val stem = name.substringBeforeLast('.', name).ifBlank { name }
            return localFile.resolveSibling("$stem.md")
        }

        internal fun writeMarkdown(responseBody: String, outputFile: Path): ZaiOcrWriteResult {
            val parsed = try {
                JsonSupport.json.decodeFromString<ZaiLayoutParsingResponseDto>(responseBody)
            } catch (exception: Exception) {
                throw ZaiQuotaException("Could not parse OCR response.", 200, responseBody, exception)
            }
            val markdown = parsed.mdResults.trim()
            if (markdown.isEmpty()) {
                throw ZaiQuotaException("Z.ai OCR returned no markdown.", 200, responseBody)
            }
            val parent = outputFile.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
            Files.writeString(outputFile, markdown)
            return ZaiOcrWriteResult(
                outputFile = outputFile.toString(),
                pages = parsed.dataInfo?.numPages ?: 0,
            )
        }

        private fun mimeType(path: Path, bytes: ByteArray): String {
            if (bytes.size >= 5 && bytes.decodeToString(0, 5) == "%PDF-") return "application/pdf"
            if (bytes.size >= 8 && bytes[0] == 0x89.toByte()) return "image/png"
            if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
            return when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                else -> "application/octet-stream"
            }
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    }
}

@Serializable
internal data class ZaiLayoutParsingRequestDto(
    val model: String,
    val file: String,
    @SerialName("return_crop_images") val returnCropImages: Boolean = false,
)

@Serializable
internal data class ZaiLayoutParsingResponseDto(
    @SerialName("md_results") val mdResults: String = "",
    @SerialName("data_info") val dataInfo: ZaiOcrDataInfoDto? = null,
)

@Serializable
internal data class ZaiOcrDataInfoDto(
    @SerialName("num_pages") val numPages: Int = 0,
)

@Serializable
internal data class ZaiOcrWriteResult(
    @SerialName("output_file") val outputFile: String,
    val pages: Int,
)
