package ai.vox.app.live

import ai.vox.app.capture.Frame
import ai.vox.app.capture.FrameSource
import ai.vox.app.core.assistant.Assistant
import ai.vox.app.core.model.AiTask
import ai.vox.app.core.model.ContentPart
import ai.vox.app.core.model.Settings
import ai.vox.app.core.session.UsageKind
import ai.vox.app.core.session.UsageMeter
import ai.vox.app.core.vision.FrameSamplerPolicy
import ai.vox.app.voice.AndroidSpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LiveStatus {
    data object Stopped : LiveStatus
    data class Watching(val framesSent: Int) : LiveStatus
    data object Analyzing : LiveStatus
    data object Paused : LiveStatus
    /** Capture is running but nothing usable arrived (e.g. protected content renders black). */
    data class Blocked(val reason: String) : LiveStatus
    data class LimitReached(val reason: String) : LiveStatus
}

/**
 * Periodically samples a [FrameSource], sends only frames that changed meaningfully, and narrates what's new.
 * Never sends every frame: interval, change threshold, and a per-session frame budget all apply.
 */
class LiveVideoEngine(
    private val scope: CoroutineScope,
    private val assistant: Assistant,
    private val speech: AndroidSpeechOutput,
    private val usage: UsageMeter,
    private val settings: () -> Settings,
    private val canSpeak: () -> Boolean,
) {
    private val _status = MutableStateFlow<LiveStatus>(LiveStatus.Stopped)
    val status: StateFlow<LiveStatus> = _status.asStateFlow()
    private var loop: Job? = null
    private var source: FrameSource? = null
    @Volatile private var paused = false
    @Volatile private var forceNext = false
    private var sent = 0

    fun isRunning() = loop?.isActive == true
    fun isPaused() = paused

    fun start(source: FrameSource) {
        stop()
        this.source = source
        paused = false
        sent = 0
        val s = settings()
        val policy = FrameSamplerPolicy(
            changeThreshold = s.liveChangeThreshold.toDouble(),
            minIntervalMs = s.liveIntervalSec * 1000L,
            refreshAfterMs = 60_000L,
        )
        _status.value = LiveStatus.Watching(0)
        loop = scope.launch {
            try {
                while (true) {
                    if (paused) { _status.value = LiveStatus.Paused; delay(500); continue }
                    val frame: Frame? = source.nextFrame()
                    if (frame == null) { delay(1000); continue }
                    val now = System.currentTimeMillis()
                    val send = forceNext || when (val d = policy.decide(frame.luma, now)) {
                        FrameSamplerPolicy.Decision.Send -> true
                        FrameSamplerPolicy.Decision.Blank -> {
                            _status.value = LiveStatus.Blocked("The picture is black. This can mean protected content (DRM video or a secure app), which Android doesn't let Vox capture.")
                            false
                        }
                        else -> false
                    }
                    if (send) {
                        forceNext = false
                        if (!usage.tryConsume(UsageKind.LIVE_FRAME)) {
                            _status.value = LiveStatus.LimitReached("Live frame limit for this session reached (${usage.limit(UsageKind.LIVE_FRAME)}). End and restart the session to continue.")
                            return@launch
                        }
                        policy.markSent(frame.luma, now)
                        analyze(frame)
                    }
                    delay((settings().liveIntervalSec * 1000L).coerceAtLeast(1000L))
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private suspend fun analyze(frame: Frame) {
        _status.value = LiveStatus.Analyzing
        val first = sent == 0
        sent++
        val prompt = if (first) "Describe what is happening in this live view." else "Here is the next sampled frame. Report only what is new or important compared with earlier frames. If nothing meaningful changed, say so in a few words."
        val spoken = settings().speakLiveObservations && canSpeak()
        assistant.ask(AiTask.LIVE, "Sampled frame #$sent", listOf(ContentPart.Text(prompt), ContentPart.Image(frame.jpegBase64)), spoken = spoken, auto = true).join()
        val last = assistant.turns.value.lastOrNull()
        if (spoken && last != null && !last.isError && !speech.speaking.value) speech.speak(last.display)
        _status.value = if (paused) LiveStatus.Paused else LiveStatus.Watching(sent)
    }

    /** User asked a question about what's in view right now: send a fresh frame with it. */
    fun askAboutCurrent(question: String, spoken: Boolean) {
        scope.launch {
            val frame = source?.nextFrame()
            val parts = buildList {
                add(ContentPart.Text(question))
                if (frame != null && usage.tryConsume(UsageKind.LIVE_FRAME)) add(ContentPart.Image(frame.jpegBase64))
            }
            assistant.ask(AiTask.LIVE, question, parts, spoken = spoken).join()
            val last = assistant.turns.value.lastOrNull()
            if (spoken && last != null && !last.isError) speech.speak(last.display)
        }
    }

    /** Request an immediate analysis of the next frame, regardless of change detection. */
    fun analyzeNow() { forceNext = true }

    fun pause() { paused = true; _status.value = LiveStatus.Paused }
    fun resume() { paused = false; _status.value = LiveStatus.Watching(sent) }
    fun togglePause() { if (paused) resume() else pause() }

    fun stop() {
        loop?.cancel()
        loop = null
        source = null
        assistant.cancel()
        speech.stop()
        _status.value = LiveStatus.Stopped
    }
}
