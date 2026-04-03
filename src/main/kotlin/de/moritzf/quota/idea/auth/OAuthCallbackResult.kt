package de.moritzf.quota.idea.auth

/**
 * Result produced by the local OAuth callback endpoint.
 */
data class OAuthCallbackResult(val code: String? = null, val error: String? = null)
