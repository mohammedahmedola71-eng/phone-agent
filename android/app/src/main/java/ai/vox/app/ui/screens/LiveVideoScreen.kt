package ai.vox.app.ui.screens

import ai.vox.app.core.model.Capability
import ai.vox.app.live.LiveStatus
import ai.vox.app.ui.components.CapabilityActions
import ai.vox.app.ui.components.ChatList
import ai.vox.app.ui.components.GhostButton
import ai.vox.app.ui.components.GlowButton
import ai.vox.app.ui.components.LocalContainer
import ai.vox.app.ui.components.MessageField
import ai.vox.app.ui.components.MicButton
import ai.vox.app.ui.components.Panel
import ai.vox.app.ui.components.ScreenScaffold
import ai.vox.app.ui.components.StatusPill
import ai.vox.app.ui.theme.VoxColors
import ai.vox.app.voice.ListenState
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

@Composable
fun LiveVideoScreen(actions: CapabilityActions, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val session by c.session.state.collectAsState()
    val status by c.live.status.collectAsState()
    val turns by c.liveThread.turns.collectAsState()
    val busy by c.liveThread.busy.collectAsState()
    val settings by c.settings.collectAsState()
    val listen by c.chat.listenState.collectAsState()
    var useCamera by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val previewView = remember { PreviewView(context) }
    val running = Capability.LIVE_VIDEO in session.enabled
    val voiceOn = Capability.VOICE in session.enabled
    val spokenQuestions = voiceOn && settings.speakLiveObservations

    // The camera must never keep running once this screen is gone.
    DisposableEffect(Unit) { onDispose { if (c.session.isEnabled(Capability.CAMERA)) c.disable(Capability.CAMERA) } }

    fun startScreen() {
        if (Capability.SCREEN !in session.enabled) { onMessage("Allow screen awareness first, then start live analysis."); actions.toggle(Capability.SCREEN); return }
        c.startLive(Capability.SCREEN) { r -> r.onFailure { onMessage(it.message ?: "Couldn't start live analysis.") } }
    }

    fun startCamera() = actions.requestCameraPermission {
        scope.launch {
            if (!c.camera.bind(owner, previewView)) { onMessage("Couldn't open the camera."); return@launch }
            c.enable(Capability.CAMERA) { r ->
                r.onFailure { c.camera.unbind(); onMessage(it.message ?: "Couldn't start the camera.") }
                r.onSuccess { c.startLive(Capability.CAMERA) { l -> l.onFailure { e -> onMessage(e.message ?: "Couldn't start live analysis.") } } }
            }
        }
    }

    ScreenScaffold("Live Video", onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).imePadding().navigationBarsPadding()) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Source", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    listOf("Screen" to false, "Camera" to true).forEach { (label, cam) ->
                        val sel = useCamera == cam
                        Text(
                            label, style = MaterialTheme.typography.labelSmall, color = if (sel) VoxColors.Cyan else VoxColors.Muted,
                            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50))
                                .border(1.dp, if (sel) VoxColors.Cyan else VoxColors.Outline, RoundedCornerShape(50))
                                .clickable(enabled = !running) { useCamera = cam }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(statusText(status), statusColor(status))
                    Text("  " + (if (useCamera) "Camera" else "Screen") + " source", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
                }
                Text(
                    if (useCamera) "Camera runs only while this screen is open and the session is active. Leaving the app stops it."
                    else "Samples your screen every ${settings.liveIntervalSec}s and only sends frames that changed. Protected/DRM video shows black on Android - Vox will tell you rather than guess.",
                    style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted, modifier = Modifier.padding(top = 8.dp),
                )
                (status as? LiveStatus.Blocked)?.let { Text(it.reason, color = VoxColors.Warn, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                (status as? LiveStatus.LimitReached)?.let { Text(it.reason, color = VoxColors.Warn, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(12.dp))
                if (!running) {
                    GlowButton(if (useCamera) "Start camera analysis" else "Start live analysis", { if (useCamera) startCamera() else startScreen() }, Modifier.fillMaxWidth(), enabled = session.active)
                    if (!session.active) Text("Activate Vox on the home screen first.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        GhostButton(if (status is LiveStatus.Paused) "Resume" else "Pause", { c.live.togglePause() }, Modifier.weight(1f))
                        GhostButton("Analyze now", { c.live.analyzeNow() }, Modifier.weight(1f))
                        GhostButton("Stop", { c.stopLive(); if (useCamera) c.disable(Capability.CAMERA) }, Modifier.weight(1f), danger = true)
                    }
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Speak observations aloud", style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted, modifier = Modifier.weight(1f))
                    Switch(checked = settings.speakLiveObservations, onCheckedChange = { v -> c.updateSettings { it.copy(speakLiveObservations = v) } })
                }
                if (settings.speakLiveObservations && !voiceOn) Text("Turn on Voice on the home screen to hear them.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
            }

            if (useCamera) {
                Spacer(Modifier.height(10.dp))
                AndroidView({ previewView }, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)))
            }

            Spacer(Modifier.height(10.dp))
            if (turns.none { !it.auto }) {
                Text(if (running) "Vox will describe what it sees here, and speak it if voice is on." else "Observations will appear here.", color = VoxColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            } else {
                ChatList(turns, Modifier.weight(1f), busy = busy)
            }
            MessageField(
                value = text, onValueChange = { text = it },
                onSend = { c.live.askAboutCurrent(text, spokenQuestions); text = "" },
                enabled = running, placeholder = "Ask about what Vox is seeing…",
                modifier = Modifier.padding(vertical = 8.dp),
                trailing = { MicButton(listening = listen is ListenState.Listening, enabled = running && voiceOn, onClick = { if (listen is ListenState.Listening) c.chat.stopListening() else c.speechIn.start { heard -> c.live.askAboutCurrent(heard, spokenQuestions) } }) },
            )
        }
    }
}

private fun statusText(s: LiveStatus) = when (s) {
    LiveStatus.Stopped -> "STOPPED"
    is LiveStatus.Watching -> "WATCHING · ${s.framesSent} sent"
    LiveStatus.Analyzing -> "ANALYZING"
    LiveStatus.Paused -> "PAUSED"
    is LiveStatus.Blocked -> "BLOCKED"
    is LiveStatus.LimitReached -> "LIMIT REACHED"
}

private fun statusColor(s: LiveStatus) = when (s) {
    LiveStatus.Stopped -> VoxColors.Muted
    is LiveStatus.Watching -> VoxColors.Ok
    LiveStatus.Analyzing -> VoxColors.Cyan
    LiveStatus.Paused -> VoxColors.Warn
    is LiveStatus.Blocked, is LiveStatus.LimitReached -> VoxColors.Warn
}
