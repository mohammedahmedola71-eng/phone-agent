package ai.vox.app

import ai.vox.app.capture.CameraFrameSource
import ai.vox.app.capture.CaptureHub
import ai.vox.app.capture.FrameSource
import ai.vox.app.capture.ScreenFrameSource
import ai.vox.app.controllers.ChatController
import ai.vox.app.controllers.ScreenController
import ai.vox.app.core.assistant.Assistant
import ai.vox.app.core.model.Capability
import ai.vox.app.core.model.OrbState
import ai.vox.app.core.model.OrbStatus
import ai.vox.app.core.model.Settings
import ai.vox.app.core.net.AiProvider
import ai.vox.app.core.net.GatewayAiProvider
import ai.vox.app.core.net.GatewayConfig
import ai.vox.app.core.session.SessionManager
import ai.vox.app.core.session.UsageKind
import ai.vox.app.core.session.UsageMeter
import ai.vox.app.data.FileConversationStore
import ai.vox.app.data.SecretStore
import ai.vox.app.data.SettingsRepository
import ai.vox.app.live.LiveStatus
import ai.vox.app.live.LiveVideoEngine
import ai.vox.app.notifications.NotificationReplier
import ai.vox.app.notifications.NotificationStore
import ai.vox.app.session.CameraCapability
import ai.vox.app.session.LiveCapability
import ai.vox.app.session.NotificationsCapability
import ai.vox.app.session.ScreenCapability
import ai.vox.app.session.VoiceCapability
import ai.vox.app.video.SharedVideoInbox
import ai.vox.app.video.VideoLabEngine
import ai.vox.app.voice.AndroidSpeechInput
import ai.vox.app.voice.AndroidSpeechOutput
import ai.vox.app.voice.ListenState
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Hand-rolled dependency container: one place that builds every component, easy to read and to fake in tests. */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepo = SettingsRepository(app, appScope)
    val settings: StateFlow<Settings> get() = settingsRepo.settings
    val secrets = SecretStore(app)
    val conversations = FileConversationStore(app)

    /** The only AI dependency. Change providers on the gateway, or swap this implementation. */
    val provider: AiProvider = GatewayAiProvider(config = { GatewayConfig(settings.value.gatewayUrl, secrets.getToken()) })

    val usage = UsageMeter {
        val s = settings.value
        mapOf(UsageKind.SCREEN_ANALYSIS to s.maxScreenAnalysesPerSession, UsageKind.LIVE_FRAME to s.maxLiveFramesPerSession, UsageKind.VIDEO_FRAME to 200)
    }

    val capture = CaptureHub()
    val camera = CameraFrameSource(app)
    val speechIn = AndroidSpeechInput(app)
    val speechOut = AndroidSpeechOutput(app) { settings.value }
    val notifications = NotificationStore(app)
    val replier = NotificationReplier(app)
    val sharedVideos = SharedVideoInbox()
    val videoEngine = VideoLabEngine(app)

    private fun assistant(name: String, maxImages: Int = 2, persist: Boolean = false) = Assistant(
        name = name, provider = provider, scope = appScope, length = { settings.value.responseLength },
        persist = { persist && settings.value.saveConversations }, store = if (persist) conversations else null, maxImagesInContext = maxImages,
    )

    private val chatAssistant = assistant("chat", persist = true)
    private val screenAssistant = assistant("screen")
    private val liveAssistant = assistant("live")
    val videoAssistant = assistant("video", maxImages = 32)

    lateinit var session: SessionManager
        private set

    val live = LiveVideoEngine(appScope, liveAssistant, speechOut, usage, { settings.value }, { session.isEnabled(Capability.VOICE) })
    val videoBusy = MutableStateFlow(false)

    init {
        session = SessionManager(
            controllers = listOf(
                VoiceCapability(app, speechIn, speechOut),
                ScreenCapability(app, capture),
                CameraCapability(app, camera),
                LiveCapability(live),
                NotificationsCapability(notifications),
            ),
            onUsageReset = { usage.reset() },
        )
    }

    val chat = ChatController(appScope, session, chatAssistant, speechIn, speechOut, capture, usage) { settings.value }
    val screen = ScreenController(appScope, session, screenAssistant, capture, usage, speechIn, speechOut) { settings.value }
    val liveThread: Assistant get() = liveAssistant

    /** Which capability feeds the running live analysis, so it stops when that source goes away. */
    private var liveSource: Capability? = null

    /** The orb state and the label that goes with it. */
    data class Status(val orb: OrbState, val label: String)

    val status: StateFlow<Status> = combine(
        session.state,
        speechIn.state,
        speechOut.speaking,
        chatAssistant.busy,
        combine(screenAssistant.busy, live.status, videoBusy, videoAssistant.busy, screen.capturing) { sb, ls, vb, vab, cap -> Triple(sb || cap || ls is LiveStatus.Analyzing, vb || vab, ls) },
    ) { s, listen, speaking, thinking, an ->
        val analyzing = an.first || an.second
        val orb = OrbStatus.resolve(s.active, listen is ListenState.Listening, speaking, thinking, analyzing)
        val what = when { an.second -> "video"; an.third is LiveStatus.Analyzing -> "live view"; else -> "screen" }
        Status(orb, OrbStatus.label(orb, what))
    }.stateIn(appScope, SharingStarted.Eagerly, Status(OrbState.INACTIVE, OrbStatus.label(OrbState.INACTIVE)))

    init {
        capture.onStoppedBySystem = { appScope.launch { session.disable(Capability.SCREEN) } }
        chatAssistant.restore()

        // Live analysis must not outlive the source that feeds it.
        appScope.launch {
            session.state.collectLatest { st ->
                val src = liveSource
                if (live.isRunning() && src != null && src !in st.enabled) {
                    live.stop()
                    session.disable(Capability.LIVE_VIDEO)
                }
            }
        }
        // Optional auto-end so a forgotten session doesn't stay open.
        appScope.launch {
            session.state.map { it.active }.distinctUntilChanged().collectLatest { active ->
                if (active) {
                    val minutes = settings.value.autoEndMinutes
                    if (minutes > 0) { delay(minutes * 60_000L); endSessionAsync() }
                }
            }
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) { appScope.launch { settingsRepo.update(transform) } }

    fun activate() { appScope.launch { session.activate() } }

    fun enable(cap: Capability, onResult: (Result<Unit>) -> Unit = {}) {
        appScope.launch { onResult(session.enable(cap)) }
    }

    fun disable(cap: Capability) { appScope.launch { session.disable(cap) } }

    /** Starts live analysis on the screen or camera. The source capability must already be enabled. */
    fun startLive(source: Capability, onResult: (Result<Unit>) -> Unit = {}) {
        appScope.launch {
            val frames: FrameSource = if (source == Capability.CAMERA) camera else ScreenFrameSource(capture)
            if (!session.isEnabled(source)) { onResult(Result.failure(IllegalStateException("Turn on ${source.label.lowercase()} first."))); return@launch }
            val r = session.enable(Capability.LIVE_VIDEO)
            if (r.isSuccess) { liveSource = source; live.start(frames) }
            onResult(r)
        }
    }

    fun stopLive() { appScope.launch { session.disable(Capability.LIVE_VIDEO) } }

    fun endSessionAsync() { appScope.launch { endSession() } }

    /** Stops every capture/listening/processing path and discards in-memory session data. */
    suspend fun endSession() {
        speechOut.stop(); speechIn.stop()
        live.stop()
        session.end() // flips indicators to "inactive" first, then stops every capability
        screen.reset()
        liveAssistant.clear()
        videoAssistant.clear()
        videoBusy.value = false
        sharedVideos.clear()
        if (!settings.value.saveConversations) chatAssistant.clear()
        liveSource = null
    }

    /** Called when the app leaves the foreground: the mic and camera never run while Vox isn't on screen. */
    fun onAppBackgrounded() {
        speechIn.stop()
        speechOut.stop()
        if (session.isEnabled(Capability.CAMERA)) disable(Capability.CAMERA)
    }

    /** Settings > Delete data. */
    fun deleteConversations() {
        chatAssistant.clear(); screen.reset(); liveAssistant.clear(); videoAssistant.clear()
        conversations.deleteAll()
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    suspend fun deleteEverything() {
        endSession()
        deleteConversations()
        secrets.clear()
        settingsRepo.resetAll()
    }
}
