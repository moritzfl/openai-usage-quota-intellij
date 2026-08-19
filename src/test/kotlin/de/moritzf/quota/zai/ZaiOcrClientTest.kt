package de.moritzf.quota.zai

import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZaiOcrClientTest {
    @Test
    fun resolveFileKeepsPublicUrlAndWrapsLocalPdf() {
        assertEquals("https://example.com/a.pdf", ZaiOcrClient.resolveFile("https://example.com/a.pdf", null))
        val dir = Files.createTempDirectory("zai-ocr")
        val pdf = dir.resolve("doc.pdf")
        Files.write(pdf, "%PDF-1.4".toByteArray())
        val encoded = ZaiOcrClient.resolveFile(null, pdf)
        assertTrue(encoded.startsWith("data:application/pdf;base64,"))
    }

    @Test
    fun writeMarkdownUsesMdResults() {
        val dir = Files.createTempDirectory("zai-ocr-md")
        val out = dir.resolve("doc.md")
        val result = ZaiOcrClient.writeMarkdown(
            """{"md_results":"# Hello","data_info":{"num_pages":2}}""",
            out,
        )
        assertEquals(out.toString(), result.outputFile)
        assertEquals(2, result.pages)
        assertEquals("# Hello", Files.readString(out))
        assertEquals(emptyList(), result.imageFiles)
    }

    @Test
    fun writeMarkdownPersistsCropImagesAndRewritesMarkdown() {
        val dir = Files.createTempDirectory("zai-ocr-img")
        val out = dir.resolve("doc.md")
        val png = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val url = "https://cdn.example.com/crops/figure-1.png"
        val body = """
            {
              "md_results": "See ![]($url) and a data image",
              "layout_details": [
                [
                  {"index": 1, "label": "text", "content": "See"},
                  {"index": 2, "label": "image", "content": "$url"},
                  {"index": 3, "label": "image", "content": "data:image/png;base64,$png"}
                ]
              ],
              "data_info": {"num_pages": 1}
            }
        """.trimIndent()

        val result = ZaiOcrClient.writeMarkdown(body, out, includeImages = true) { requested ->
            assertEquals(url, requested)
            byteArrayOf(9, 8, 7)
        }

        assertEquals(listOf(dir.resolve("figure-1.png").toString(), dir.resolve("img-1.png").toString()), result.imageFiles)
        assertEquals(byteArrayOf(9, 8, 7).toList(), Files.readAllBytes(dir.resolve("figure-1.png")).toList())
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), Files.readAllBytes(dir.resolve("img-1.png")).toList())
        assertEquals("See ![](figure-1.png) and a data image", Files.readString(out))
    }
}
