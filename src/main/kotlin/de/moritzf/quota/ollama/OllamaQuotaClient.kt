package de.moritzf.quota.ollama

import de.moritzf.quota.shared.JsonSupport
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * HTTP client for Ollama Cloud subscription usage via the official API key endpoint.
 *
 * `GET https://ollama.com/api/usage` with `Authorization: Bearer <api-key>`.
 * See https://github.com/ollama/ollama/issues/12532 and https://docs.ollama.com/api/authentication
 */
open class OllamaQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val endpoint: URI = DEFAULT_ENDPOINT,
) {
    open fun fetchQuota(apiKey: String): OllamaQuota {
        val token = apiKey.trim().takeIf { it.isNotBlank() }
            ?: throw OllamaQuotaException("Ollama API key missing. Add an Ollama API key in settings.")

        val body = getUsageJson(token)
        val quota = try {
            parseQuota(body)
        } catch (exception: OllamaQuotaException) {
            throw exception
        } catch (exception: Exception) {
            throw OllamaQuotaException("Ollama usage response changed.", 200, body, exception)
        }
        quota.fetchedAt = Clock.System.now()
        quota.rawJson = body
        return quota
    }

    private fun getUsageJson(apiKey: String): String {
        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = send(request)
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw OllamaQuotaException(
                "Ollama API key invalid. Check your Ollama API key in settings.",
                status,
                body,
            )
        }
        if (status == 429) {
            throw OllamaQuotaException("Ollama usage API rate limited. Try again later.", status, body)
        }
        if (status !in 200..299) {
            throw OllamaQuotaException(
                "Ollama usage request failed (HTTP $status). Try again later.",
                status,
                body,
            )
        }
        return body
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: HttpTimeoutException) {
            throw OllamaQuotaException("Ollama usage request timed out. Try again later.", 0, null, exception)
        } catch (exception: IOException) {
            throw OllamaQuotaException("Ollama usage request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OllamaQuotaException("Ollama usage request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        @JvmField
        val DEFAULT_ENDPOINT: URI = URI.create("https://ollama.com/api/usage")

        fun parseQuota(usageJson: String): OllamaQuota {
            // Each limit window is decoded on its own so one reshaped or unparsable block (for
            // example a changed session entry, per-model details, activity/cost extras, or unknown
            // attributes) only drops that block instead of hiding the whole quota.
            val root = runCatching { JsonSupport.json.parseToJsonElement(usageJson) }.getOrNull() as? JsonObject
                ?: throw OllamaQuotaException("Ollama usage response changed.", 200, usageJson)

            val limits = root["limits"] as? JsonObject

            fun window(key: String): OllamaUsageWindow? =
                JsonSupport.decodeSectionOrNull(limits?.get(key), OllamaLimitWindowDto.serializer())?.toWindow()

            val sessionUsage = window("session")
            val weeklyUsage = window("weekly")
            if (sessionUsage == null && weeklyUsage == null) {
                throw OllamaQuotaException("Ollama usage response changed.", 200, usageJson)
            }

            return OllamaQuota(
                sessionUsage = sessionUsage,
                weeklyUsage = weeklyUsage,
            )
        }

        private fun OllamaLimitWindowDto.toWindow(): OllamaUsageWindow? {
            val usage = usage ?: return null
            // Usage arrives as a 0..1 fraction (0.046 = 4.6%). Anything above 1 is treated as an
            // already-percent value so a unit change upstream still shows a sane number.
            val percent = if (usage <= 1.0) usage * 100.0 else usage
            return OllamaUsageWindow(
                usagePercent = percent.coerceIn(0.0, 100.0),
                resetsAt = resetsAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }
    }
}

@Serializable
private data class OllamaLimitWindowDto(
    @Serializable(with = LenientUsageSerializer::class)
    val usage: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
)

/**
 * Usage values arrive as decimals (`0.046`), integers, or numeric strings depending on the backend
 * variant. Anything non-numeric is treated as absent instead of failing the window.
 */
private object LenientUsageSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("OllamaLenientUsage")

    override fun deserialize(decoder: Decoder): Double? {
        val jsonDecoder = decoder as? JsonDecoder ?: error("LenientUsageSerializer requires JsonDecoder")
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        error("Serialization of Ollama usage values is not supported")
    }
}
