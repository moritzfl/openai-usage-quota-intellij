package de.moritzf.quota.idea.auth

import java.io.IOException

/**
 * Raised when the OAuth token endpoint returns a non-success response.
 */
class OAuthTokenRequestException(
    message: String,
    val statusCode: Int,
    val oauthError: String? = null,
) : IOException(message) {
    fun isTerminalAuthFailure(): Boolean {
        return statusCode == 400 || statusCode == 401 || oauthError == INVALID_GRANT
    }

    companion object {
        private const val INVALID_GRANT = "invalid_grant"
    }
}
