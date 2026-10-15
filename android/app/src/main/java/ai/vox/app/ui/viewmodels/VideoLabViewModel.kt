package ai.vox.app.ui.viewmodels

import ai.vox.app.AppContainer
import ai.vox.app.core.model.AiTask
import ai.vox.app.core.model.ContentPart
import ai.vox.app.core.session.UsageKind
import ai.vox.app.core.video.VideoCheck
import ai.vox.app.core.video.VideoLimits
import ai.vox.app.core.video.VideoPlan
import ai.vox.app.core.video.VideoPrompt
import ai.vox.app.core.video.VideoTask
import ai.vox.app.video.SampledFrame
import ai.vox.app.video.VideoMeta
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

sealed interface VideoPhase {
    data object Empty : VideoPhase
    data object Loading : VideoPhase
    data class Ready(val rejection: String?) : VideoPhase
    data class Extracting(val done: Int, val total: Int) : VideoPhase
    data object Analyzing : VideoPhase
    data object Done : VideoPhase
    data class Failed(val message: String) : VideoPhase
}

/** A request waiting for the user's explicit OK before any frame leaves the device. */
data class PendingUpload(val task: VideoTask, val question: String, val frameCount: Int, val everySec: Int)

class VideoLabViewModel(private val c: AppContainer) : ViewModel() {
    private val limits get() = VideoLimits(maxFrames = c.settings.value.maxVideoFrames.coerceIn(4, 32))

    private val _meta = MutableStateFlow<VideoMeta?>(null)
    val meta: StateFlow<VideoMeta?> = _meta.asStateFlow()
    private val _phase = MutableStateFlow<VideoPhase>(VideoPhase.Empty)
    val phase: StateFlow<VideoPhase> = _phase.asStateFlow()
    private val _pending = MutableStateFlow<PendingUpload?>(null)
    val pending: StateFlow<PendingUpload?> = _pending.asStateFlow()

    val turns = c.videoAssistant.turns
    val busy = c.videoAssistant.busy

    /** Frames stay in memory only, and only while this video is open in an active session. */
    private var frames: List<SampledFrame> = emptyList()
    private var job: Job? = null

    init {
        // Ending the session discards the video, its frames and the results.
        viewModelScope.launch {
            c.session.state.map { it.active }.distinctUntilChanged().drop(1).collect { active -> if (!active) discard() }
        }
        // A video shared from another app is loaded here but nothing is sent until the user confirms.
        viewModelScope.launch {
            c.sharedVideos.pending.collect { uri -> if (uri != null) { c.sharedVideos.take(); pick(uri) } }
        }
    }

    fun pick(uri: Uri) {
        discard()
        _phase.value = VideoPhase.Loading
        job = viewModelScope.launch {
            val m = c.videoEngine.readMeta(uri)
            _meta.value = m
            _phase.value = when (val v = VideoPlan.validate(m.durationMs, m.sizeBytes, m.mimeType, limits)) {
                VideoCheck.Ok -> VideoPhase.Ready(null)
                is VideoCheck.Rejected -> VideoPhase.Ready(v.reason)
            }
        }
    }

    /** Step 1: user chose a task. If frames must be uploaded, ask for confirmation first. */
    fun requestAnalysis(task: VideoTask, question: String = "") {
        val m = _meta.value ?: return
        if (!task.supported) return
        if (task == VideoTask.ASK && question.isBlank()) return
        if (!c.session.state.value.active) { _phase.value = VideoPhase.Failed("Activate Vox first - it only processes things during a session."); return }
        val times = VideoPlan.sampleTimes(m.durationMs ?: return, limits.maxFrames)
        if (frames.isNotEmpty()) { run(task, question, upload = false); return } // frames already in context, nothing new leaves the device
        val every = ((m.durationMs ?: 0) / 1000 / times.size.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        _pending.value = PendingUpload(task, question, times.size, every)
    }

    fun cancelPending() { _pending.value = null }

    /** Step 2: user confirmed that the sampled frames may be sent to the external AI service. */
    fun confirmUpload() {
        val p = _pending.value ?: return
        _pending.value = null
        run(p.task, p.question, upload = true)
    }

    private fun run(task: VideoTask, question: String, upload: Boolean) {
        val m = _meta.value ?: return
        job?.cancel()
        job = viewModelScope.launch {
            c.videoBusy.value = true
            try {
                if (upload) {
                    val times = VideoPlan.sampleTimes(m.durationMs ?: return@launch, limits.maxFrames)
                    if (!c.usage.tryConsume(UsageKind.VIDEO_FRAME, times.size)) {
                        _phase.value = VideoPhase.Failed("Video frame limit for this session reached. End the session and start a new one to analyze more.")
                        return@launch
                    }
                    _phase.value = VideoPhase.Extracting(0, times.size)
                    frames = c.videoEngine.sampleFrames(m.uri, times) { d, t -> _phase.value = VideoPhase.Extracting(d, t) }
                    if (frames.isEmpty()) {
                        _phase.value = VideoPhase.Failed("Vox couldn't decode any frames. This video format may not be supported on this device (try an MP4).")
                        return@launch
                    }
                }
                _phase.value = VideoPhase.Analyzing
                val prompt = if (task == VideoTask.ASK) question.trim() else task.prompt
                val display = if (task == VideoTask.ASK) question.trim() else task.label
                val text = if (upload) VideoPrompt.intro(m.name, m.durationMs ?: 0, frames.size) + "\n\n" + prompt else prompt
                val parts = buildList<ContentPart> {
                    add(ContentPart.Text(text))
                    if (upload) frames.forEach { f -> add(ContentPart.Text(VideoPrompt.label(f.timeMs))); add(ContentPart.Image(f.jpegBase64)) }
                }
                c.videoAssistant.ask(AiTask.VIDEO, display, parts, spoken = false).join()
                val last = c.videoAssistant.turns.value.lastOrNull()
                _phase.value = if (last?.isError == true) VideoPhase.Failed(last.display) else VideoPhase.Done
            } catch (e: CancellationException) {
                _phase.value = VideoPhase.Ready(null)
                throw e
            } finally {
                c.videoBusy.value = false
            }
        }
    }

    fun followUp(question: String) {
        val q = question.trim()
        if (q.isEmpty() || frames.isEmpty()) return
        run(VideoTask.ASK, q, upload = false)
    }

    /** Cancels extraction or the AI request. */
    fun cancel() {
        job?.cancel()
        c.videoAssistant.cancel()
        c.videoBusy.value = false
        _phase.value = if (_meta.value != null) VideoPhase.Ready(null) else VideoPhase.Empty
    }

    fun readAloud(text: String) { if (c.session.isEnabled(ai.vox.app.core.model.Capability.VOICE)) c.speechOut.speak(text) }
    fun stopSpeaking() = c.speechOut.stop()

    /** Forget the video, its frames and the analysis. */
    fun discard() {
        job?.cancel()
        c.videoAssistant.clear()
        c.videoBusy.value = false
        frames = emptyList()
        _pending.value = null
        _meta.value = null
        _phase.value = VideoPhase.Empty
    }

    override fun onCleared() {
        // Leaving Video Lab never leaves frames behind.
        job?.cancel()
        frames = emptyList()
    }
}
