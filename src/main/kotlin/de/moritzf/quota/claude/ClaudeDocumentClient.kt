package de.moritzf.quota.claude

import de.moritzf.quota.shared.DocumentMarkdown
import de.moritzf.quota.shared.JsonSupport
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

open class ClaudeDocumentClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val messagesUri: URI = DEFAULT_MESSAGES_URI,
) {
    open fun convertDocument(
        accessToken: String,
        documentUrl: String? = null,
        localFile: Path? = null,
        outputFile: Path? = null,
        model: String = DEFAULT_MODEL,
    ): String {
        val token = accessToken.trim().ifBlank {
            throw ClaudeQuotaException("Claude login required. Log in from Claude settings.")
        }
        val content = documentContent(documentUrl, localFile)
        val markdownOutput = outputFile ?: DocumentMarkdown.defaultOutput(localFile)
        val body = requestJson(model.trim().ifBlank { DEFAULT_MODEL }, content)
        val response = send(postJson(token, body))
        val status = response.statusCode()
        val responseBody = response.body()
        if (status == 401 || status == 403) {
            throw ClaudeQuotaException("Claude auth expired. Log in to Claude again from settings.", status, responseBody)
        }
        if (status !in 200..299) {
            throw ClaudeQuotaException("Claude document conversion failed (HTTP $status). Try again later.", status, responseBody)
        }
        val markdown = parseMarkdown(responseBody)
        return DocumentMarkdown.resultJson(markdown, markdownOutput)
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: HttpTimeoutException) {
            throw ClaudeQuotaException("Claude document request timed out. Try again later.", 0, null, exception)
        } catch (exception: IOException) {
            throw ClaudeQuotaException("Claude document request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ClaudeQuotaException("Claude document request failed. Check your connection.", 0, null, exception)
        }
    }

    private fun postJson(accessToken: String, body: String): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(messagesUri)
            .timeout(Duration.ofSeconds(180))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("anthropic-beta", OAUTH_BETA)
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-4-6"
        val DEFAULT_MESSAGES_URI: URI = URI.create("https://api.anthropic.com/v1/messages")
        private const val DEFAULT_MAX_TOKENS = 8192
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val OAUTH_BETA = "oauth-2025-04-20"
        private const val USER_AGENT = "claude-cli/2.1.87 (external, cli)"
        internal const val DOCUMENT_PROMPT =
            "Convert this document to markdown. Preserve headings, lists, tables, and code. Return only the markdown."
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

        fun createDefault(): ClaudeDocumentClient = ClaudeDocumentClient()

        internal fun requestJson(model: String, document: JsonObject): String {
            return buildJsonObject {
                put("model", model)
                put("max_tokens", DEFAULT_MAX_TOKENS)
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(document)
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", DOCUMENT_PROMPT)
                            })
                        }
                    })
                }
            }.toString()
        }

        internal fun documentContent(documentUrl: String?, localFile: Path?): JsonObject {
            val url = documentUrl?.trim().orEmpty()
            if (url.isNotEmpty()) {
                val type = if (isImageName(url)) "image" else "document"
                return buildJsonObject {
                    put("type", type)
                    putJsonObject("source") {
                        put("type", "url")
                        put("url", url)
                    }
                }
            }
            val path = localFile ?: throw ClaudeQuotaException("Provide documentUrl or a local file path.")
            if (!Files.isRegularFile(path)) {
                throw ClaudeQuotaException("Local document was not found.")
            }
            val bytes = Files.readAllBytes(path)
            val mime = mimeType(path, bytes)
            val type = if (mime.startsWith("image/")) "image" else "document"
            return buildJsonObject {
                put("type", type)
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", mime)
                    put("data", Base64.getEncoder().encodeToString(bytes))
                }
            }
        }

        internal fun parseMarkdown(responseBody: String): String {
            val root = runCatching { JsonSupport.json.parseToJsonElement(responseBody) }.getOrNull() as? JsonObject
                ?: throw ClaudeQuotaException("Claude document response changed.", 200, responseBody)
            val text = root["content"]
                ?.jsonArray
                ?.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    if (obj["type"]?.jsonPrimitive?.contentOrNull != "text") return@mapNotNull null
                    obj["text"]?.jsonPrimitive?.contentOrNull
                }
                ?.joinToString("")
                .orEmpty()
                .trim()
            if (text.isEmpty()) {
                throw ClaudeQuotaException("Claude document conversion returned no output.", 200, responseBody)
            }
            return text
        }

        private fun isImageName(value: String): Boolean {
            val name = value.substringAfterLast('/').substringBefore('?').lowercase(Locale.ROOT)
            return name.substringAfterLast('.', "") in IMAGE_EXTENSIONS
        }

        private fun mimeType(path: Path, bytes: ByteArray): String {
            if (bytes.size >= 5 && bytes.decodeToString(0, 5) == "%PDF-") return "application/pdf"
            if (bytes.size >= 8 && bytes[0] == 0x89.toByte()) return "image/png"
            if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
            return when (path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "application/pdf"
            }
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    }
}
