package de.moritzf.quota.idea

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.idea.auth.OAuthClientConfig
import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.auth.OAuthCredentialsStore
import de.moritzf.quota.idea.auth.OAuthLoginFlow
import de.moritzf.quota.idea.auth.OAuthTokenClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Coordinates OAuth login, credential storage, and token refresh for quota requests.
 */
@Service(Service.Level.APP)
class QuotaAuthService {
    private val httpClient = HttpClient.newHttpClient()
    private val tokenClient = OAuthTokenClient(httpClient, OAUTH_CONFIG)
    private val credentialsStore = OAuthCredentialsStore(SERVICE_NAME, USER_NAME)
    private val cachedCredentials = AtomicReference<OAuthCredentials?>()
    private val cacheLoading = AtomicBoolean(false)
    private val authInProgress = AtomicBoolean(false)
    private val pendingFlow = AtomicReference<OAuthLoginFlow?>()
    private val credentialClearCounter = AtomicLong(0)

    init {
        refreshCacheAsync()
    }

    fun startLoginFlow(callback: (LoginResult) -> Unit) {
        if (!authInProgress.compareAndSet(false, true)) {
            LOG.warn("Login requested while another login is already in progress")
            callback(LoginResult.error("Login already in progress"))
            return
        }

        AppExecutorUtil.getAppExecutorService().execute {
            val result = try {
                runLoginFlow()
            } catch (exception: Exception) {
                LOG.warn("Login flow failed", exception)
                var message = exception.message
                if (message != null && message.lowercase().contains("address already in use")) {
                    message = "Port ${OAUTH_CONFIG.callbackPort} is already in use. Close the other app using it and try again."
                }
                LoginResult.error(message ?: "Login failed")
            } finally {
                authInProgress.set(false)
            }
            callback(result)
        }
    }

    fun isLoginInProgress(): Boolean = authInProgress.get()

    fun abortLogin(reason: String?): Boolean {
        val flow = pendingFlow.getAndSet(null) ?: return false
        authInProgress.set(false)
        val message = if (reason.isNullOrBlank()) "Login canceled" else reason
        flow.cancel(message)
        LOG.info("Login flow aborted: $message")
        return true
    }

    fun clearCredentials() {
        credentialClearCounter.incrementAndGet()
        abortLogin("Logged out")
        cachedCredentials.set(null)
        credentialsStore.clear()
        LOG.info("Cleared stored OAuth credentials")
    }

    fun isLoggedIn(): Boolean {
        val credentials = cachedCredentials.get()
        if (credentials == null && !cacheLoading.get()) {
            refreshCacheAsync()
        }
        return credentials?.accessToken?.isNotBlank() == true
    }

    fun getAccessTokenBlocking(): String? {
        var credentials = getCredentialsBlocking() ?: return null
        if (isExpired(credentials)) {
            credentials = refreshCredentialsBlocking(credentials) ?: return null
        }
        return credentials.accessToken
    }

    fun getAccountId(): String? = cachedCredentials.get()?.accountId

    fun refreshCacheAsync() {
        if (!cacheLoading.compareAndSet(false, true)) {
            return
        }

        AppExecutorUtil.getAppExecutorService().execute {
            try {
                getCredentialsBlocking()
            } finally {
                cacheLoading.set(false)
            }
        }
    }

    private fun runLoginFlow(): LoginResult {
        LOG.info("Starting OAuth login flow")
        val flow = OAuthLoginFlow.start(OAUTH_CONFIG)
        pendingFlow.set(flow)

        val callbackError = pingCallbackEndpoint()
        if (callbackError != null) {
            pendingFlow.compareAndSet(flow, null)
            flow.stopServerNow()
            return LoginResult.error(callbackError)
        }

        openAuthorizationUi(flow.authorizationUrl)
        val callback = flow.waitForCallback()
        pendingFlow.compareAndSet(flow, null)
        LOG.info("OAuth callback received; success=${callback.error == null}")

        if (callback.error != null) {
            return LoginResult.error(callback.error)
        }
        if (callback.code.isNullOrBlank()) {
            return LoginResult.error("No authorization code received")
        }

        val credentials = tokenClient.exchangeAuthorizationCode(callback.code, flow.codeVerifier)
        saveCredentials(credentials)
        cachedCredentials.set(credentials)
        return LoginResult.success()
    }

    private fun pingCallbackEndpoint(): String? {
        return try {
            val request = HttpRequest.newBuilder(URI.create("http://localhost:${OAUTH_CONFIG.callbackPort}/auth/ping"))
                .timeout(3.seconds.toJavaDuration())
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                null
            } else {
                "Callback test failed (HTTP ${response.statusCode()})"
            }
        } catch (exception: Exception) {
            "Callback not reachable: ${exception::class.java.simpleName}"
        }
    }

    private fun openAuthorizationUi(url: String) {
        LOG.info("Opening authorization UI: $url")
        AppExecutorUtil.getAppExecutorService().execute { BrowserUtil.browse(url) }
    }

    private fun getCredentialsBlocking(): OAuthCredentials? {
        val clearMarker = credentialClearCounter.get()
        val credentials = credentialsStore.load()
        if (credentialClearCounter.get() != clearMarker) {
            cachedCredentials.set(null)
            return null
        }
        cachedCredentials.set(credentials)
        return credentials
    }

    private fun saveCredentials(credentials: OAuthCredentials) {
        credentialsStore.save(credentials)
    }

    private fun refreshCredentialsBlocking(existing: OAuthCredentials): OAuthCredentials? {
        return try {
            tokenClient.refreshCredentials(existing).also { refreshed ->
                saveCredentials(refreshed)
                cachedCredentials.set(refreshed)
            }
        } catch (exception: Exception) {
            LOG.warn("Token refresh failed", exception)
            clearCredentials()
            null
        }
    }

    class LoginResult private constructor(@JvmField val success: Boolean, @JvmField val message: String?) {
        companion object {
            @JvmStatic
            fun success(): LoginResult = LoginResult(true, null)

            @JvmStatic
            fun error(message: String): LoginResult = LoginResult(false, message)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(QuotaAuthService::class.java)
        private const val SERVICE_NAME = "OpenAI Usage Quota OAuth"
        private const val USER_NAME = "openai-oauth"
        private val OAUTH_CONFIG = OAuthClientConfig.openAiUsageQuotaDefaults()

        @JvmStatic
        fun getInstance(): QuotaAuthService {
            return ApplicationManager.getApplication().getService(QuotaAuthService::class.java)
        }

        @JvmStatic
        fun parseQuery(query: String): Map<String, String> = OAuthLoginFlow.parseQuery(query)

        @JvmStatic
        fun parseUri(value: String): URI = OAuthLoginFlow.parseUri(value, OAUTH_CONFIG.redirectUri)

        private fun isExpired(credentials: OAuthCredentials): Boolean {
            return System.currentTimeMillis() >= credentials.expiresAt - 5.minutes.inWholeMilliseconds
        }
    }
}
