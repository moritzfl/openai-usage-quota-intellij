package de.moritzf.quota.idea.auth

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.intellij.lang.annotations.Language
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the browser-based OAuth login flow including local callback server handling.
 */
class OAuthLoginFlow private constructor(
    private val config: OAuthClientConfig,
    val codeVerifier: String,
    private val state: String,
    val authorizationUrl: String,
) {
    private val callbackFuture = CompletableFuture<OAuthCallbackResult>()
    private var server: HttpServer? = null
    private var serverExecutor: ExecutorService? = null

    fun waitForCallback(): OAuthCallbackResult {
        return try {
            callbackFuture.get(CALLBACK_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            OAuthCallbackResult(error = "Authentication timed out")
        } finally {
            scheduleStopServer()
        }
    }

    fun stopServerNow() {
        stopServer()
    }

    fun cancel(reason: String) {
        if (!callbackFuture.isDone) {
            callbackFuture.complete(OAuthCallbackResult(error = reason))
        }
        stopServer()
    }

    private fun startServer() {
        try {
            server = HttpServer.create(InetSocketAddress(config.callbackPort), 0)
        } catch (exception: IOException) {
            LOG.warn("Failed to bind OAuth callback server to ${config.redirectUri}", exception)
            throw exception
        }

        server!!.createContext("/auth/ping") { exchange ->
            val body = "OK".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { os -> os.write(body) }
        }
        server!!.createContext("/auth/callback", this::handleCallback)
        serverExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "openai-oauth-callback").apply { isDaemon = true }
        }
        server!!.executor = serverExecutor
        server!!.start()
        LOG.info("OAuth callback server started at ${config.redirectUri}")
    }

    private fun handleCallback(exchange: HttpExchange) {
        val responseText = try {
            if (exchange.remoteAddress?.address != null && !exchange.remoteAddress.address.isLoopbackAddress) {
                LOG.warn("Rejected non-loopback callback from ${exchange.remoteAddress}")
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
                return
            }

            val params = OAuthUrlCodec.parseQuery(exchange.requestURI.rawQuery)
            val error = params["error"]
            when {
                error != null -> {
                    callbackFuture.complete(OAuthCallbackResult(error = "OAuth error: $error"))
                    buildHtmlResponse("Authentication Failed", "Authentication failed: $error", false)
                }

                params["code"].isNullOrBlank() || params["state"].isNullOrBlank() -> {
                    LOG.warn("Callback missing code/state")
                    callbackFuture.complete(OAuthCallbackResult(error = "Missing code or state"))
                    buildHtmlResponse("Authentication Failed", "Missing code/state parameters.", false)
                }

                params["state"] != state -> {
                    LOG.warn("Callback state mismatch")
                    callbackFuture.complete(OAuthCallbackResult(error = "State mismatch"))
                    buildHtmlResponse("Authentication Failed", "State mismatch.", false)
                }

                else -> {
                    val code = params["code"]!!
                    LOG.info("Callback completed with authorization code")
                    callbackFuture.complete(OAuthCallbackResult(code = code))
                    buildHtmlResponse(
                        "Authentication Successful",
                        "You can close this window and return to the IDE.",
                        true,
                    )
                }
            }
        } catch (exception: Exception) {
            LOG.warn("Callback handling failed", exception)
            callbackFuture.complete(OAuthCallbackResult(error = exception.message))
            buildHtmlResponse("Authentication Failed", "Authentication failed.", false)
        }

        val bytes = responseText.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { os -> os.write(bytes) }
    }

    private fun stopServer() {
        server?.stop(0)
        if (server != null) {
            LOG.info("OAuth callback server stopped")
        }
        server = null

        serverExecutor?.shutdownNow()
        serverExecutor = null
    }

    private fun scheduleStopServer() {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                Thread.sleep(CALLBACK_SHUTDOWN_DELAY.inWholeMilliseconds)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            stopServer()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(OAuthLoginFlow::class.java)
        private val CALLBACK_TIMEOUT: Duration = 10.minutes
        private val CALLBACK_SHUTDOWN_DELAY: Duration = 3.seconds

        @JvmStatic
        fun start(config: OAuthClientConfig): OAuthLoginFlow {
            val verifier = generateCodeVerifier()
            val challenge = generateCodeChallenge(verifier)
            val state = generateState()
            val authorizationUrl = buildAuthorizationUrl(config, challenge, state)
            return OAuthLoginFlow(config, verifier, state, authorizationUrl).also { it.startServer() }
        }

        @JvmStatic
        fun parseQuery(query: String?): Map<String, String> = OAuthUrlCodec.parseQuery(query)

        @JvmStatic
        fun parseUri(value: String, redirectUri: String): URI = OAuthUrlCodec.parseCallbackUri(value, redirectUri)

        private fun buildAuthorizationUrl(config: OAuthClientConfig, challenge: String, state: String): String {
            val params = linkedMapOf(
                "client_id" to config.clientId,
                "redirect_uri" to config.redirectUri,
                "scope" to config.scopes,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "response_type" to "code",
                "state" to state,
                "codex_cli_simplified_flow" to "true",
                "originator" to config.originator,
            )
            return "${config.authorizationEndpoint}?${OAuthUrlCodec.formEncode(params)}"
        }

        private fun generateCodeVerifier(): String {
            val random = ByteArray(32)
            SecureRandom().nextBytes(random)
            return base64Url(random)
        }

        private fun generateCodeChallenge(verifier: String): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                base64Url(digest.digest(verifier.toByteArray(Charsets.UTF_8)))
            } catch (exception: Exception) {
                throw IllegalStateException("Unable to create code challenge", exception)
            }
        }

        private fun generateState(): String {
            val random = ByteArray(16)
            SecureRandom().nextBytes(random)
            return base64Url(random)
        }

        @OptIn(ExperimentalEncodingApi::class)
        private fun base64Url(value: ByteArray): String {
            return Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .encode(value)
        }

        private fun buildHtmlResponse(title: String, message: String, success: Boolean): String {
            val background = if (success) "#0d8f6f" else "#b3282d"
            @Language("HTML")
            val response = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>%s</title>
                <style>
                  body {
                    font-family: Arial,serif;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    height: 100vh;
                    margin: 0;
                    background: %s;
                    color: white;
                  }
                  .container {
                    text-align: center;
                    padding: 2rem;
                  }
                  h1 { font-size: 1.6rem; margin-bottom: 0.5rem; }
                  p { opacity: 0.9; }
                </style>
                </head>
                <body>
                <div class="container">
                  <h1>%s</h1>
                  <p>%s</p>
                </div>
                </body>
                </html>
            """
            return response.trimIndent().format(title, background, title, message)
        }
    }
}
