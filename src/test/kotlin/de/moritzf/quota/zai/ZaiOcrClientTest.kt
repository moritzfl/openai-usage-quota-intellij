package de.moritzf.quota.zai

import java.nio.file.Files
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
    }
}
