package de.moritzf.quota.mistral

import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MistralToolClientTest {
    @Test
    fun conversationRequestUsesWebSearchTool() {
        val json = MistralWebSearchClient.conversationRequestJson("hello", "mistral-small-latest", premium = false)
        assertTrue(json.contains("\"web_search\""))
        assertTrue(json.contains("hello"))
    }

    @Test
    fun conversationRequestCanUsePremiumSearch() {
        val json = MistralWebSearchClient.conversationRequestJson("hello", "mistral-small-latest", premium = true)
        assertTrue(json.contains("\"web_search_premium\""))
    }

    @Test
    fun firstToolFileIdReadsNestedConversationOutput() {
        val body = """
            {
              "outputs": [
                {"type": "tool.execution", "name": "image_generation"},
                {
                  "type": "message.output",
                  "content": [
                    {"type": "text", "text": "here"},
                    {"type": "tool_file", "file_id": "file-123", "file_type": "png"}
                  ]
                }
              ]
            }
        """.trimIndent()
        assertEquals("file-123", MistralImageClient.firstToolFileId(body))
    }

    @Test
    fun writeMarkdownPersistsApiPlaceholdersAsSiblingImages() {
        val dir = Files.createTempDirectory("mistral-ocr")
        val markdownFile = dir.resolve("doc.md")
        val png = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val body = """
            {
              "pages": [
                {
                  "markdown": "Hello ![img-0.jpeg](img-0.jpeg)",
                  "images": [{"id": "img-0.jpeg", "image_base64": "$png"}]
                }
              ]
            }
        """.trimIndent()

        val result = MistralOcrClient.writeMarkdown(body, markdownFile, includeImages = true)

        assertEquals(markdownFile.toString(), result.outputFile)
        assertEquals(1, result.pages)
        assertEquals(listOf(dir.resolve("img-0.jpeg").toString()), result.imageFiles)
        assertEquals("Hello ![img-0.jpeg](img-0.jpeg)", Files.readString(markdownFile))
        assertTrue(Files.size(dir.resolve("img-0.jpeg")) > 0)
    }
}
