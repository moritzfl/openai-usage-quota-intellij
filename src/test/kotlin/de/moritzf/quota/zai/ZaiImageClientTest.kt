package de.moritzf.quota.zai

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZaiImageClientTest {
    @Test
    fun firstImageUrlReadsDataArray() {
        assertEquals(
            "https://cdn.example.com/a.png",
            ZaiImageClient.firstImageUrl("""{"data":[{"url":"https://cdn.example.com/a.png"}]}"""),
        )
        assertNull(ZaiImageClient.firstImageUrl("""{"data":[]}"""))
    }

    @Test
    fun resolveOutputUsesProjectDirectory() {
        val dir = Files.createTempDirectory("zai-img")
        assertEquals(dir.resolve("out/hi.png"), ZaiImageClient.resolveOutput("out/hi.png", dir))
        assertNull(ZaiImageClient.resolveOutput(null, dir))
    }
}
