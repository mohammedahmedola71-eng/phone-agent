package ai.vox.app.controllers

import ai.vox.app.capture.CaptureHub
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
import ai.vox.app.voice.ListenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Voice + text conversation. Spoken replies and listening only happen while the VOICE capability is on. */
class ChatController(
    private val scope: CoroutineScope,
    private val session: SessionManager,
    val chat: Assistant,
    private val input: AndroidSpeechInput,
    private val output: AndroidSpeechOutput,
    private val capture: CaptureHub,
    private val usage: UsageMeter,
    private val settings: () -> Settings,
) {
    private val _attachScreen = MutableStateFlow(false)
    /** When on (and screen awareness is active), each message includes a fresh look at the screen. */
    val attachScreen: StateFlow<Boolean> = _attachScreen.asStateFlow()
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    init {
        scope.launch {
            chat.events.collect { e ->
                if (e is AssistantEvent.Reply && e.spoken && session.isEnabled(Capability.VOICE)) {
                    output.speak(e.turn.display) { if (settings().continuousConversation && session.isEnabled(Capability.VOICE)) listen() }
                }
            }
        }
    }

    fun setAttachScreen(on: Boolean) { _attachScreen.value = on && session.isEnabled(Capability.SCREEN) }

    private fun wantsSpeech() = session.isEnabled(Capability.VOICE) && settings().speakReplies

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        if (!session.state.value.active) { _notices.tryEmit("Activate Vox first."); return }
        output.stop()
        scope.launch {
            val parts = mutableListOf<ContentPart>(ContentPart.Text(q))
            var task = AiTask.CHAT
            if (_attachScreen.value && session.isEnabled(Capability.SCREEN)) {
                if (!usage.tryConsume(UsageKind.SCREEN_ANALYSIS)) {
                    _notices.tryEmit("Screen analysis limit for this session reached. Sending without the screen.")
                } else {
                    val frame = capture.grabScreen()
                    when {
                        frame == null -> _notices.tryEmit("Couldn't capture the screen. Sending without it.")
                        FrameDiff.looksBlank(frame.luma) -> _notices.tryEmit("The screen came back black - it may be protected content. Sending without it.")
                        else -> { parts += ContentPart.Image(frame.jpegBase64); task = AiTask.SCREEN }
                    }
                }
            }
            chat.ask(task, q, parts, spoken = wantsSpeech())
        }
    }

    /** Starts one round of listening; the result is sent as a message. */
    fun listen() {
        if (!session.isEnabled(Capability.VOICE)) { _notices.tryEmit("Turn on the microphone first."); return }
        output.stop() // interrupt Vox if it's talking
        input.start { heard -> send(heard) }
    }

    fun stopListening() = input.stop()

    /** Interrupts speech and, if the model is still working, cancels that request. */
    fun interrupt() {
        output.stop()
        input.stop()
        if (chat.busy.value) chat.cancel()
    }

    fun stopSpeaking() = output.stop()

    fun regenerate() { output.stop(); chat.regenerate() }

    val listenState: StateFlow<ListenState> get() = input.state
    val micLevel: StateFlow<Float> get() = input.level
    val speaking: StateFlow<Boolean> get() = output.speaking

    fun clearListenError() = input.clearError()
}
