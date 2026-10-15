package ai.vox.app.core.model

/** Combines the independent activity flags into the single state the orb and status label show. */
object OrbStatus {
    fun resolve(
        sessionActive: Boolean,
        listening: Boolean,
        speaking: Boolean,
        thinking: Boolean,
        analyzing: Boolean,
    ): OrbState = when {
        !sessionActive -> OrbState.INACTIVE
        speaking -> OrbState.SPEAKING
        listening -> OrbState.LISTENING
        analyzing -> OrbState.ANALYZING
        thinking -> OrbState.THINKING
        else -> OrbState.IDLE
    }

    fun label(state: OrbState, analyzingWhat: String = "screen"): String = when (state) {
        OrbState.INACTIVE -> "Vox is ready"
        OrbState.IDLE -> "Session active"
        OrbState.LISTENING -> "Listening"
        OrbState.THINKING -> "Thinking"
        OrbState.SPEAKING -> "Speaking"
        OrbState.ANALYZING -> "Analyzing $analyzingWhat"
    }
}
