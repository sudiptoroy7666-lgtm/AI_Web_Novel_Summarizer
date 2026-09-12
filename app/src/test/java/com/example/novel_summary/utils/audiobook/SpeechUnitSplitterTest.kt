package com.example.novel_summary.utils.audiobook

import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechUnitSplitterTest {
    @Test
    fun `split produces balanced chunk sizes for long text`() {
        val content = buildString {
            repeat(3000) {
                append("word ")
            }
        }

        val chunks = SpeechUnitSplitter.split(content)

        assertTrue("Expected multiple chunks for long input", chunks.size > 1)
        assertTrue(
            "Chunks should stay within the allowed TTS size limit",
            chunks.all { it.length <= 800 }
        )
        assertTrue(
            "Chunks should be reasonably balanced in size",
            chunks.all { it.length >= 200 } || chunks.size <= 1
        )
        assertTrue(
            "Chunk spread should not be excessive",
            (chunks.maxOfOrNull { it.length } ?: 0) - (chunks.minOfOrNull { it.length } ?: 0) <= 450
        )
    }
}
