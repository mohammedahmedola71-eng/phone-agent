package ai.vox.app.core

import ai.vox.app.core.voice.SpeechTextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextCleanerTest {
    @Test fun `strips markdown so it is not read aloud`() {
        val out = SpeechTextCleaner.clean("## Title\n- **Bold** point with `code`\n- See [docs](https://x.y/z) now 🎉")
        assertFalse(out.contains("#")); assertFalse(out.contains("*")); assertFalse(out.contains("`"))
        assertFalse(out.contains("https")); assertFalse(out.contains("🎉"))
        assertTrue(out.contains("Bold point with code")); assertTrue(out.contains("docs"))
    }

    @Test fun `code blocks and bare urls are replaced`() {
        val out = SpeechTextCleaner.clean("Run this:\n```\nrm -rf /\n```\nthen visit https://example.com/a?b=c")
        assertFalse(out.contains("rm -rf")); assertTrue(out.contains("a link"))
    }

    @Test fun `chunks respect sentence boundaries and max length`() {
        val text = "First sentence here. Second one follows! Is a third? " + "word ".repeat(120).trim() + "."
        val chunks = SpeechTextCleaner.chunks(text, maxChars = 80)
        assertTrue(chunks.size > 2)
        assertTrue(chunks.all { it.length <= 80 })
        assertEquals("First sentence here. Second one follows! Is a third?", chunks.first())
    }

    @Test fun `empty input gives no chunks`() {
        assertTrue(SpeechTextCleaner.chunks("   \n ").isEmpty())
    }
}
