package de.moritzf.quota.idea.auth

import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.JsonSupport
import de.moritzf.quota.dto.OAuthTokenResponseDto
import de.moritzf.quota.idea.QuotaTokenUtil
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Performs OAuth token exchange and refresh requests against the configured token endpoint.
 */
class OAuthTokenClient(
    private val httpClient: HttpClient,
    private val config: OAuthClientConfig,
) {
    fun exchangeAuthorizationCode(code: String, codeVerifier: String): OAuthCredentials {
        LOG.info("Exchanging authorization code for tokens")
        val body = OAuthUrlCodec.formEncode(
            linkedMapOf(
                "grant_type" to "authorization_code",
                "client_id" to config.clientId,
                "code" to code,
                "redirect_uri" to config.redirectUri,
                "code_verifier" to codeVerifier,
            ),
        )
        val response = postForm(body)
        if (!isSuccessful(response.statusCode())) {
            LOG.warn("Token exchange failed: ${response.statusCode()}")
            throw IOException("Token exchange failed: ${response.statusCode()} ${response.body()}")
        }

        val tokenResponse = parseResponse(response.body())
        if (tokenResponse.refreshToken.isNullOrBlank()) {
            throw IOException("Token exchange did not return a refresh token")
        }

        return createCredentials(tokenResponse).also {
            it.refreshToken = tokenResponse.refreshToken
        }
    }

    fun refreshCredentials(existing: OAuthCredentials): OAuthCredentials {
        LOG.info("Refreshing OAuth token")
        val body = OAuthUrlCodec.formEncode(
            linkedMapOf(
                "grant_type" to "refresh_token",
                "client_id" to config.clientId,
                "refresh_token" to (existing.refreshToken ?: ""),
            ),
        )
        val response = postForm(body)
        if (!isSuccessful(response.statusCode())) {
            LOG.warn("Token refresh failed: ${response.statusCode()}")
            throw IOException("Token refresh failed: HTTP ${response.statusCode()}")
        }

        val tokenResponse = parseResponse(response.body())
        return createCredentials(tokenResponse).also { credentials ->
            credentials.refreshToken = tokenResponse.refreshToken?.takeUnless { it.isBlank() } ?: existing.refreshToken
            if (credentials.accountId == null) {
                credentials.accountId = existing.accountId
            }
        }
    }

    private fun postForm(body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(config.tokenEndpoint))
            .timeout(30.seconds.toJavaDuration())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun createCredentials(tokenResponse: OAuthTokenResponseDto): OAuthCredentials {
        val accessToken = tokenResponse.accessToken?.takeUnless { it.isBlank() }
            ?: throw IOException("Token response did not return an access token")
        return OAuthCredentials(
            accessToken = accessToken,
            expiresAt = System.currentTimeMillis() + tokenResponse.expiresIn * 1000L,
            accountId = resolveAccountId(tokenResponse),
        )
    }

    private fun parseResponse(body: String): OAuthTokenResponseDto {
        return JsonSupport.json.decodeFromString(body)
    }

    private fun resolveAccountId(response: OAuthTokenResponseDto): String? {
        return QuotaTokenUtil.extractChatGptAccountId(response.idToken)
            ?: QuotaTokenUtil.extractChatGptAccountId(response.accessToken)
    }

    private fun isSuccessful(statusCode: Int): Boolean = statusCode in 200..299

    companion object {
        private val LOG = Logger.getInstance(OAuthTokenClient::class.java)
    }
}
