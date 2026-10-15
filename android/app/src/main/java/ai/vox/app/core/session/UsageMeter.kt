package ai.vox.app.core.session

enum class UsageKind { SCREEN_ANALYSIS, LIVE_FRAME, VIDEO_FRAME, REQUEST }

/** Client-side per-session budget so a forgotten live session can't run up cost. The gateway enforces its own limits too. */
class UsageMeter(private val limits: () -> Map<UsageKind, Int>) {
    private val counts = HashMap<UsageKind, Int>()

    @Synchronized fun reset() = counts.clear()

    @Synchronized fun used(kind: UsageKind) = counts[kind] ?: 0

    fun limit(kind: UsageKind) = limits()[kind] ?: Int.MAX_VALUE

    /** Counts [n] units if they fit under the limit. Returns false (and counts nothing) otherwise. */
    @Synchronized fun tryConsume(kind: UsageKind, n: Int = 1): Boolean {
        val used = counts[kind] ?: 0
        if (used + n > limit(kind)) return false
        counts[kind] = used + n
        return true
    }
}
