package de.moritzf.quota.shared

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentMarkdownTest {
    @Test
    fun unwrapStripsMarkdownFences() {
        assertEquals("# Hello", DocumentMarkdown.unwrap("```markdown\n# Hello\n```"))
        assertEquals("# Hello", DocumentMarkdown.unwrap("# Hello"))
    }

    @Test
    fun resultJsonWritesBesideSource() {
        val dir = Files.createTempDirectory("doc-md")
        val source = dir.resolve("doc.pdf")
        Files.writeString(source, "%PDF-1.4")
        val output = DocumentMarkdown.defaultOutput(source)!!
        val json = DocumentMarkdown.resultJson("```md\n# Title\n```", output)
        assertEquals("# Title", Files.readString(output))
        assertTrue(json.contains("output_file"))
    }
}
