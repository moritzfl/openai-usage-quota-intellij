package de.moritzf.quota.minimax

import kotlin.test.Test
import kotlin.test.assertEquals

class MiniMaxAudioClientTest {
    @Test
    fun hexToBytesDecodesAudioPayload() {
        assertEquals(listOf(0x0A, 0xFF), MiniMaxAudioClient.hexToBytes("0aff").map { it.toInt() and 0xFF })
        assertEquals(
            "abcd",
            MiniMaxAudioClient.audioHex("""{"data":{"audio":"abcd","status":2}}"""),
        )
    }
}
