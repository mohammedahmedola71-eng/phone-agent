package ai.vox.app.controllers

import ai.vox.app.capture.CaptureHub
import ai.vox.app.capture.Frame
import ai.vox.app.core.assistant.Assistant
import ai.vox.app.core.assistant.AssistantEvent
import ai.vox.app.core.model.AiTask
import ai.vox.app.core.model.Capability
import ai.vox.app.core.model.ContentPart
import ai.vox.app.core.model.Settings
import ai.vox.app.core.session.SessionManager
import ai.vox.app.core.session.UsageKind
import ai.vox.app.core.session.UsageMeter
import ai.vox.app.core.vision.FrameDiff
import ai.vox.app.voice.AndroidSpeechInput
import ai.vox.app.voice.AndroidSpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Screen awareness: captures a frame on demand (never continuously), asks the model about it, and lets the user ask
 * follow-ups about the same screen without capturing again.
 */
class ScreenController(
    private val scope: CoroutineScope,
    private val session: SessionManager,
    val assistant: Assistant,
    private val capture: CaptureHub,
    private val usage: UsageMeter,
    private val input: AndroidSpeechInput,
    private val output: AndroidSpeechOutput,
    private val settings: () -> Settings,
) {
    private val _currentFrame = MutableStateFlow<Frame?>(null)
    /** The screenshot the current Q&A is about (kept in memory only). */
    val currentFrame: StateFlow<Frame?> = _currentFrame.asStateFlow()
    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()
    private val _countdown = MutableStateFlow(0)
    /** Seconds left before a delayed capture fires (0 = none pending). */
    val countdown: StateFlow<Int> = _countdown.asStateFlow()
    private var pendingCapture: Job? = null

    init {
        scope.launch {
            assistant.events.collect { e ->
                if (e is AssistantEvent.Reply && e.spoken && session.isEnabled(Capability.VOICE)) output.speak(e.turn.display)
            }
        }
    }

    private fun wantsSpeech() = session.isEnabled(Capability.VOICE) && settings().speakReplies

    /** Looks at the screen now (or uses the frame captured from the notification button) and asks [question] about it. */
    fun askAboutScreen(question: String, delaySec: Int = 0) {
        if (!session.isEnabled(Capability.SCREEN)) { _notices.tryEmit("Turn on screen awareness first."); return }
        output.stop()
        pendingCapture?.cancel()
        pendingCapture = scope.launch {
            // A short countdown lets the user switch to another app before the capture happens.
            try {
                for (i in delaySec downTo 1) { _countdown.value = i; delay(1000) }
            } finally { _countdown.value = 0 }
            _capturing.value = true
            try {
                val frame = capture.takePendingLook() ?: run {
                    if (!usage.tryConsume(UsageKind.SCREEN_ANALYSIS)) {
                        _notices.tryEmit("Screen analysis limit for this session reached (${usage.limit(UsageKind.SCREEN_ANALYSIS)}).")
                        return@launch
                    }
                    capture.grabScreen()
                }
                if (frame == null) { _notices.tryEmit("Couldn't capture the screen."); return@launch }
                if (FrameDiff.looksBlank(frame.luma)) {
                    _notices.tryEmit("The screen came back black. Android blocks capture of protected content such as banking apps, DRM video and secure screens.")
                    return@launch
                }
                _currentFrame.value = frame
                assistant.ask(AiTask.SCREEN, question, listOf(ContentPart.Text(question), ContentPart.Image(frame.jpegBase64)), spoken = wantsSpeech())
            } finally {
                _capturing.value = false
            }
        }
    }

    /** Follow-up about the screenshot already in context - no new capture, no new image upload cost beyond history. */
    fun followUp(question: String) {
        if (_currentFrame.value == null) { askAboutScreen(question); return }
        output.stop()
        assistant.ask(AiTask.SCREEN, question, spoken = wantsSpeech())
    }

    fun listen(followUp: Boolean) {
        if (!session.isEnabled(Capability.VOICE)) { _notices.tryEmit("Turn on the microphone first."); return }
        output.stop()
        input.start { heard -> if (followUp) followUp(heard) else askAboutScreen(heard) }
    }

    fun cancelCountdown() { pendingCapture?.cancel(); _countdown.value = 0 }

    fun stopSpeaking() = output.stop()

    /** Forget the screenshot and the Q&A about it. */
    fun reset() {
        cancelCountdown()
        assistant.clear()
        _currentFrame.value = null
        capture.setPendingLook(null)
    }
}
