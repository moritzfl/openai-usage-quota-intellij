package de.moritzf.quota.shared

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DefaultOutputFilesTest {
    @Test
    fun speechFileNamesAreUnique() {
        val first = DefaultOutputFiles.speech("mp3")
        val second = DefaultOutputFiles.speech("mp3")
        assertTrue(first.matches(Regex("speech-[0-9a-f-]{36}\\.mp3")))
        assertNotEquals(first, second)
    }

    @Test
    fun imageFileNamesAreUnique() {
        val first = DefaultOutputFiles.image()
        val second = DefaultOutputFiles.image()
        assertTrue(first.matches(Regex("image-[0-9a-f-]{36}\\.png")))
        assertNotEquals(first, second)
    }
}
