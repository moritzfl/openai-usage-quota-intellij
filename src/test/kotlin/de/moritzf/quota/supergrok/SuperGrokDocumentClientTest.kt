package de.moritzf.quota.supergrok

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.DocumentLimits
import de.moritzf.quota.shared.JsonSupport
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SuperGrokDocumentClientTest {
    @Test
    fun documentInputUsesFileUrlAndFileId() {
        val url = SuperGrokDocumentClient.documentInput("https://example.com/a.pdf", null, null)
        assertEquals("input_file", url["type"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/a.pdf", url["file_url"]!!.jsonPrimitive.content)

        val uploaded = SuperGrokDocumentClient.documentInput(null, null, "file-abc")
        assertEquals("file-abc", uploaded["file_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun documentInputRejectsOversizedLocalFileWithoutFileId() {
        val dir = Files.createTempDirectory("grok-doc-big")
        val pdf = dir.resolve("big.pdf")
        RandomAccessFile(pdf.toFile(), "rw").use { it.setLength(DocumentLimits.MAX_INLINE_BYTES + 1) }
        val exception = assertFailsWith<SuperGrokQuotaException> {
            SuperGrokDocumentClient.documentInput(null, pdf, null)
        }
        assertTrue(exception.message!!.contains("too large"))
    }

    @Test
    fun parseMarkdownReadsOutputText() {
        assertEquals(
            "# Hello",
            SuperGrokDocumentClient.parseMarkdown(
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"# Hello"}]}]}""",
            ),
        )
    }

    @Test
    fun postsDocumentAndWritesMarkdown() {
        TestUpstream(
            uploadBody = """{"id":"file-1"}""",
            responseBody = """{"output":[{"content":[{"type":"output_text","text":"# Converted"}]}]}""",
        ).use { upstream ->
            val client = SuperGrokDocumentClient(baseUri = upstream.baseUri)
            val dir = Files.createTempDirectory("grok-doc")
            val pdf = dir.resolve("doc.pdf")
            Files.write(pdf, "%PDF-1.4".toByteArray())

            val result = client.convertDocument("grok-token", localFile = pdf)

            val upload = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/v1/files", upload.path)
            assertTrue(upload.body.contains("expires_after"))
            val convert = assertNotNull(upstream.requests.poll(2, TimeUnit.SECONDS))
            assertEquals("/v1/responses", convert.path)
            val body = JsonSupport.json.parseToJsonElement(convert.body).jsonObject
            val content = body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            assertEquals("input_file", content[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("file-1", content[0].jsonObject["file_id"]!!.jsonPrimitive.content)
            assertTrue(result.contains("output_file"))
            assertEquals("# Converted", Files.readString(dir.resolve("doc.md")))
        }
    }

    private class TestUpstream(
        private val uploadBody: String,
        private val responseBody: String,
    ) : AutoCloseable {
        val requests = LinkedBlockingQueue<CapturedRequest>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val baseUri: URI

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val path = exchange.requestURI.rawPath
                requests += CapturedRequest(exchange.requestMethod, path, body)
                val payload = when {
                    path.endsWith("/files") && exchange.requestMethod == "POST" -> uploadBody
                    path.contains("/files/") && exchange.requestMethod == "DELETE" -> """{"deleted":true}"""
                    else -> responseBody
                }.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            server.start()
            baseUri = URI.create("http://127.0.0.1:${server.address.port}/v1/")
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class CapturedRequest(val method: String, val path: String, val body: String)
}
