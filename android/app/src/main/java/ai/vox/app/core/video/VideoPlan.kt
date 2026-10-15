package ai.vox.app.core.video

import java.util.Locale

data class VideoLimits(
    val maxDurationMs: Long = 60 * 60 * 1000L, // 1 hour
    val maxBytes: Long = 4L * 1024 * 1024 * 1024, // 4 GB: the file itself is never uploaded, only sampled frames
    val maxFrames: Int = 24,
)

sealed interface VideoCheck {
    data object Ok : VideoCheck
    data class Rejected(val reason: String) : VideoCheck
}

object VideoPlan {
    fun validate(durationMs: Long?, sizeBytes: Long?, mimeType: String?, limits: VideoLimits): VideoCheck {
        if (mimeType != null && !mimeType.startsWith("video/")) return VideoCheck.Rejected("This isn't a video file ($mimeType).")
        if (durationMs == null || durationMs <= 0) return VideoCheck.Rejected("Vox can't read this video's length. The format may be unsupported or the file damaged.")
        if (durationMs > limits.maxDurationMs) return VideoCheck.Rejected("This video is ${format(durationMs)} long. The limit is ${format(limits.maxDurationMs)}.")
        if (sizeBytes != null && sizeBytes > limits.maxBytes) return VideoCheck.Rejected("This file is larger than ${limits.maxBytes / (1024 * 1024 * 1024)} GB.")
        return VideoCheck.Ok
    }

    /**
     * Evenly spaced sample times (ms), centered in each slice so the first and last frames aren't black fades.
     * Never more than [maxFrames] and never closer than [minGapMs].
     */
    fun sampleTimes(durationMs: Long, maxFrames: Int, minGapMs: Long = 1_000L): List<Long> {
        if (durationMs <= 0 || maxFrames <= 0) return emptyList()
        val byGap = (durationMs / minGapMs).toInt().coerceAtLeast(1)
        val n = minOf(maxFrames, byGap)
        return (0 until n).map { i -> ((i + 0.5) * durationMs / n).toLong().coerceIn(0, durationMs - 1) }
    }

    /** mm:ss, or h:mm:ss for videos over an hour. */
    fun format(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/** What the user can ask Vox to do with a video. */
enum class VideoTask(val label: String, val supported: Boolean = true, val note: String? = null, val prompt: String) {
    SUMMARY("Summary", prompt = "Summarize the whole video: what it is about, who or what appears, and the main takeaways. Start with a one-sentence overview."),
    SCENES("Scene by scene", prompt = "Explain what happens in each scene, in order. Start each scene with its approximate time range like [00:10-00:35] and a short title."),
    KEY_MOMENTS("Key moments", prompt = "List the most important moments with approximate timestamps in [mm:ss] format, each with one sentence on why it matters."),
    DETAILS("Objects & actions", prompt = "Describe the notable objects, people, actions, on-screen text and other visual details, with approximate timestamps in [mm:ss] format."),
    OUTLINE("Notes & outline", prompt = "Turn this video into structured notes: a title, a short summary, then a hierarchical outline with timestamps in [mm:ss] format."),
    TRANSCRIBE(
        "Transcribe speech",
        supported = false,
        note = "Not available: Vox analyzes sampled picture frames only and doesn't extract the audio track. Use a speech-to-text service on the video's audio, then paste the transcript into Vox.",
        prompt = "",
    ),
    ASK("Ask a question", prompt = ""),
}

/** Builds the interleaved text/image prompt: one timestamp label before every frame. */
object VideoPrompt {
    fun intro(fileName: String?, durationMs: Long, frameCount: Int): String =
        "These are $frameCount frames sampled evenly from a video${fileName?.let { " named \"$it\"" } ?: ""} that is ${VideoPlan.format(durationMs)} long. " +
            "Each frame is preceded by its timestamp. No audio is available."

    fun label(timeMs: Long) = "[${VideoPlan.format(timeMs)}]"
}
