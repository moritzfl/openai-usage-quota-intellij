package de.moritzf.quota.claude

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ClaudeDocumentClientTest {
    @Test
    fun documentContentUsesUrlAndLocalPdf() {
        val url = ClaudeDocumentClient.documentContent("https://example.com/a.pdf", null)
        assertEquals("document", url["type"]!!.jsonPrimitive.content)
        assertEquals("url", url["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val dir = Files.createTempDirectory("claude-doc")
        val pdf = dir.resolve("doc.pdf")
        Files.write(pdf, "%PDF-1.4".toByteArray())
        val local = ClaudeDocumentClient.documentContent(null, pdf)
        assertEquals("document", local["type"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", local["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun parseMarkdownReadsTextBlocks() {
        assertEquals(
            "# Hello",
            ClaudeDocumentClient.parseMarkdown("""{"content":[{"type":"text","text":"# Hello"}]}"""),
        )
    }

    @Test
    fun postsDocumentToMessagesAndWritesMarkdown() {
        TestUpstream(
            """{"content":[{"type":"text","text":"# Converted"}]}""",
        ).use { upstream ->
            val client = ClaudeDocumentClient(messagesUri = upstream.uri)
            val dir = Files.createTempDirectory("claude-doc-out")
            val pdf = dir.resolve("doc.pdf")
            Files.write(pdf, "%PDF-1.4".toByteArray())

            val result = client.convertDocument("claude-token", localFile = pdf)

            val request = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/v1/messages", request.path)
            assertEquals("Bearer claude-token", request.firstHeader("Authorization"))
            assertEquals("2023-06-01", request.firstHeader("anthropic-version"))
            assertEquals("oauth-2025-04-20", request.firstHeader("anthropic-beta"))
            val body = JsonSupport.json.parseToJsonElement(request.body).jsonObject
            assertEquals("claude-sonnet-4-6", body["model"]!!.jsonPrimitive.content)
            val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            assertEquals("document", content[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(result.contains("output_file"))
            assertEquals("# Converted", Files.readString(dir.resolve("doc.md")))
        }
    }

    private class TestUpstream(private val responseBody: String) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val uri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests += CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.rawPath,
                    headers = exchange.requestHeaders.mapValues { it.value.toList() },
                    body = body,
                )
                val response = responseBody.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            uri = URI.create("http://127.0.0.1:${server.address.port}/v1/messages")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun firstHeader(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        }
    }
}
