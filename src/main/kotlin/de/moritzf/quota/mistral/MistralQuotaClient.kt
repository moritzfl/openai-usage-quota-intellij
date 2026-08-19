package de.moritzf.quota.mistral

import de.moritzf.quota.shared.JsonSupport
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

open class MistralQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    open fun fetchQuota(apiKey: String): MistralQuota {
        require(apiKey.isNotBlank()) { "apiKey must not be null or blank" }
        val identityBody = getJson(apiKey, IDENTITY_URI)
        val identity = parseIdentity(identityBody)
        val probe = runCatching { probeRateLimits(apiKey) }.getOrNull()
        val now = clock()
        val tokenUsage = probe?.let {
            windowFromHeaders(it, HEADER_LIMIT_TOKENS, HEADER_REMAINING_TOKENS, now)
        }
        val requestUsage = probe?.let {
            windowFromHeaders(it, HEADER_LIMIT_REQUESTS, HEADER_REMAINING_REQUESTS, now)
        }
        val quota = MistralQuota(
            email = identity.email.orEmpty(),
            organization = identity.organization?.name.orEmpty(),
            workspace = identity.workspace?.name.orEmpty(),
            apiKeyName = identity.apiKey?.name.orEmpty(),
            tokenUsage = tokenUsage,
            requestUsage = requestUsage,
            fetchedAt = now,
        )
        quota.rawJson = encodeSnapshot(identity, tokenUsage, requestUsage)
        return quota
    }

    private fun probeRateLimits(apiKey: String): HttpHeaders {
        val request = HttpRequest.newBuilder()
            .uri(CHAT_URI)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(PROBE_BODY))
            .build()
        val response = send(request)
        val status = response.statusCode()
        if (status == 401 || status == 403) {
            throw MistralQuotaException("Session expired. Check your Mistral API key.", status, response.body())
        }
        if (status !in 200..299 && status != 429) {
            throw MistralQuotaException("Request failed (HTTP $status). Try again later.", status, response.body())
        }
        return response.headers()
    }

    private fun getJson(apiKey: String, endpoint: URI): String {
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
            throw MistralQuotaException("Session expired. Check your Mistral API key.", status, body)
        }
        if (status !in 200..299) {
            throw MistralQuotaException("Request failed (HTTP $status). Try again later.", status, body)
        }
        return body
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        private val IDENTITY_URI: URI = URI.create("https://api.mistral.ai/v1/users/me")
        private val CHAT_URI: URI = URI.create("https://api.mistral.ai/v1/chat/completions")
        private const val HEADER_LIMIT_TOKENS = "x-ratelimit-limit-tokens-minute"
        private const val HEADER_REMAINING_TOKENS = "x-ratelimit-remaining-tokens-minute"
        private const val HEADER_LIMIT_REQUESTS = "x-ratelimit-limit-req-minute"
        private const val HEADER_REMAINING_REQUESTS = "x-ratelimit-remaining-req-minute"
        private const val MINUTE_MS = 60_000L
        private const val PROBE_BODY =
            """{"model":"mistral-small-latest","messages":[{"role":"user","content":"."}],"max_tokens":1}"""

        internal fun parseIdentity(body: String): MistralIdentityDto {
            return try {
                JsonSupport.json.decodeFromString<MistralIdentityDto>(body)
            } catch (exception: Exception) {
                throw MistralQuotaException("Could not parse usage data.", 200, body, exception)
            }
        }

        internal fun windowFromHeaders(
            headers: HttpHeaders,
            limitName: String,
            remainingName: String,
            now: Instant,
        ): MistralUsageWindow? {
            val limit = headerLong(headers, limitName) ?: return null
            val remaining = headerLong(headers, remainingName) ?: return null
            if (limit <= 0L) return null
            val used = (limit - remaining).coerceAtLeast(0L)
            return MistralUsageWindow(
                used = used,
                limit = limit,
                remaining = remaining.coerceAtLeast(0L),
                usagePercent = used.toDouble() / limit.toDouble() * 100.0,
                resetsAt = nextMinute(now),
                periodDurationMs = MINUTE_MS,
            )
        }

        internal fun windowFromValues(limit: Long, remaining: Long, now: Instant): MistralUsageWindow? {
            if (limit <= 0L) return null
            val used = (limit - remaining).coerceAtLeast(0L)
            return MistralUsageWindow(
                used = used,
                limit = limit,
                remaining = remaining.coerceAtLeast(0L),
                usagePercent = used.toDouble() / limit.toDouble() * 100.0,
                resetsAt = nextMinute(now),
                periodDurationMs = MINUTE_MS,
            )
        }

        private fun encodeSnapshot(
            identity: MistralIdentityDto,
            tokenUsage: MistralUsageWindow?,
            requestUsage: MistralUsageWindow?,
        ): String {
            return JsonSupport.json.encodeToString(
                MistralQuotaSnapshot.serializer(),
                MistralQuotaSnapshot(
                    identity = identity,
                    rateLimits = MistralRateLimitsDto(
                        tokensMinute = tokenUsage?.let { MistralRateLimitDto(it.used, it.limit, it.remaining) },
                        requestsMinute = requestUsage?.let { MistralRateLimitDto(it.used, it.limit, it.remaining) },
                    ),
                ),
            )
        }

        private fun headerLong(headers: HttpHeaders, name: String): Long? {
            return headers.firstValue(name).orElse(null)?.trim()?.toLongOrNull()
        }

        private fun nextMinute(now: Instant): Instant {
            val remainder = now.toEpochMilliseconds() % MINUTE_MS
            val delta = if (remainder == 0L) MINUTE_MS else MINUTE_MS - remainder
            return Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + delta)
        }
    }
}

@Serializable
internal data class MistralIdentityDto(
    val id: String? = null,
    val email: String? = null,
    val workspace: MistralNamedIdDto? = null,
    val organization: MistralNamedIdDto? = null,
    @SerialName("api_key") val apiKey: MistralNamedIdDto? = null,
)

@Serializable
internal data class MistralNamedIdDto(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
private data class MistralQuotaSnapshot(
    val identity: MistralIdentityDto,
    @SerialName("rate_limits") val rateLimits: MistralRateLimitsDto,
)

@Serializable
private data class MistralRateLimitsDto(
    @SerialName("tokens_minute") val tokensMinute: MistralRateLimitDto? = null,
    @SerialName("requests_minute") val requestsMinute: MistralRateLimitDto? = null,
)

@Serializable
private data class MistralRateLimitDto(
    val used: Long,
    val limit: Long,
    val remaining: Long,
)
