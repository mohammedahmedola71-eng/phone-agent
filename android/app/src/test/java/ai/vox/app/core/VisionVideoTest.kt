package ai.vox.app.core

import ai.vox.app.core.vision.FrameDiff
import ai.vox.app.core.vision.FrameSamplerPolicy
import ai.vox.app.core.vision.FrameSamplerPolicy.Decision
import ai.vox.app.core.video.VideoCheck
import ai.vox.app.core.video.VideoLimits
import ai.vox.app.core.video.VideoPlan
import ai.vox.app.core.video.VideoTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionVideoTest {
    private fun grid(v: Int) = IntArray(256) { v }

    @Test fun `identical frames are skipped but changes are sent`() {
        val p = FrameSamplerPolicy(changeThreshold = 0.05, minIntervalMs = 1000)
        assertEquals(Decision.Send, p.decide(grid(100), 0)); p.markSent(grid(100), 0)
        assertEquals(Decision.SkipTooSoon, p.decide(grid(200), 500))
        assertEquals(Decision.SkipUnchanged, p.decide(grid(102), 2000))
        assertEquals(Decision.Send, p.decide(grid(200), 3000))
    }

    @Test fun `black frames are reported as protected content, never sent`() {
        val p = FrameSamplerPolicy(0.05, 0)
        assertEquals(Decision.Blank, p.decide(grid(0), 0))
        assertTrue(FrameDiff.looksBlank(grid(1)))
        assertTrue(!FrameDiff.looksBlank(grid(40)))
    }

    @Test fun `stale refresh sends an unchanged frame eventually`() {
        val p = FrameSamplerPolicy(0.05, 100, refreshAfterMs = 10_000)
        p.markSent(grid(100), 0)
        assertEquals(Decision.SkipUnchanged, p.decide(grid(100), 5_000))
        assertEquals(Decision.Send, p.decide(grid(100), 10_000))
    }

    @Test fun `sample times are capped, ordered and inside the video`() {
        val t = VideoPlan.sampleTimes(600_000, 24)
        assertEquals(24, t.size)
        assertTrue(t == t.sorted()); assertTrue(t.all { it in 0 until 600_000 })
        assertEquals(3, VideoPlan.sampleTimes(3_500, 24).size)  // short clip: at most 1 frame per second
        assertEquals(1, VideoPlan.sampleTimes(400, 24).size)
        assertTrue(VideoPlan.sampleTimes(0, 24).isEmpty())
    }

    @Test fun `validation rejects unreadable, too long and non-video files`() {
        val l = VideoLimits(maxDurationMs = 60_000)
        assertEquals(VideoCheck.Ok, VideoPlan.validate(30_000, 1000, "video/mp4", l))
        assertTrue(VideoPlan.validate(null, 1000, "video/mp4", l) is VideoCheck.Rejected)
        assertTrue(VideoPlan.validate(120_000, 1000, "video/mp4", l) is VideoCheck.Rejected)
        assertTrue(VideoPlan.validate(1000, 1000, "image/png", l) is VideoCheck.Rejected)
    }

    @Test fun `timestamps format and transcription is honestly unsupported`() {
        assertEquals("01:05", VideoPlan.format(65_000)); assertEquals("1:01:05", VideoPlan.format(3_665_000))
        assertTrue(!VideoTask.TRANSCRIBE.supported && VideoTask.TRANSCRIBE.note != null)
        assertTrue(VideoTask.entries.filter { it.supported && it != VideoTask.ASK }.all { it.prompt.isNotBlank() })
    }
}
