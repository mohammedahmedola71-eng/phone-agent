package ai.vox.app.core.vision

/**
 * Cheap change detection on tiny luma thumbnails (e.g. 16x16 = 256 values, 0..255) so unchanged
 * screens/scenes are never sent to the AI.
 */
object FrameDiff {
    /** Mean absolute difference between two same-sized luma grids, normalized to 0..1. Different sizes count as fully changed. */
    fun difference(a: IntArray?, b: IntArray): Double {
        if (a == null || a.size != b.size || b.isEmpty()) return 1.0
        var sum = 0L
        for (i in b.indices) sum += kotlin.math.abs(a[i] - b[i])
        return sum.toDouble() / (b.size * 255.0)
    }

    /**
     * True if the frame is (almost) uniformly black. Android renders FLAG_SECURE windows and DRM video as black,
     * so this is how we detect protected content instead of pretending we saw something.
     */
    fun looksBlank(luma: IntArray): Boolean {
        if (luma.isEmpty()) return true
        val mean = luma.average()
        val min = luma.min()
        val max = luma.max()
        return mean < 3.0 && (max - min) < 8
    }
}

/** Decides whether a newly captured frame is worth sending. */
class FrameSamplerPolicy(
    private val changeThreshold: Double,
    private val minIntervalMs: Long,
    /** Even if nothing changed, allow one refresh after this long (0 = never). */
    private val refreshAfterMs: Long = 0,
) {
    private var lastSent: IntArray? = null
    private var lastSentAt: Long = Long.MIN_VALUE

    sealed interface Decision {
        data object Send : Decision
        data object SkipUnchanged : Decision
        data object SkipTooSoon : Decision
        data object Blank : Decision
    }

    fun decide(luma: IntArray, nowMs: Long): Decision {
        if (FrameDiff.looksBlank(luma)) return Decision.Blank
        if (lastSentAt != Long.MIN_VALUE && nowMs - lastSentAt < minIntervalMs) return Decision.SkipTooSoon
        val changed = FrameDiff.difference(lastSent, luma) >= changeThreshold
        val stale = refreshAfterMs > 0 && lastSentAt != Long.MIN_VALUE && nowMs - lastSentAt >= refreshAfterMs
        return if (changed || stale) Decision.Send else Decision.SkipUnchanged
    }

    fun markSent(luma: IntArray, nowMs: Long) {
        lastSent = luma.copyOf()
        lastSentAt = nowMs
    }

    fun reset() {
        lastSent = null
        lastSentAt = Long.MIN_VALUE
    }
}
