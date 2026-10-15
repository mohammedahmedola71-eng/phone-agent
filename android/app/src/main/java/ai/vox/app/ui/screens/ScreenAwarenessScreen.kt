package ai.vox.app.ui.screens

import ai.vox.app.core.model.Capability
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
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun ScreenAwarenessScreen(actions: CapabilityActions, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val c = LocalContainer.current
    val session by c.session.state.collectAsState()
    val turns by c.screen.assistant.turns.collectAsState()
    val busy by c.screen.assistant.busy.collectAsState()
    val frame by c.screen.currentFrame.collectAsState()
    val capturing by c.screen.capturing.collectAsState()
    val countdown by c.screen.countdown.collectAsState()
    val pendingLook by c.capture.pendingLook.collectAsState()
    val listen by c.chat.listenState.collectAsState()
    var text by remember { mutableStateOf("") }
    val on = Capability.SCREEN in session.enabled
    val voiceOn = Capability.VOICE in session.enabled

    LaunchedEffect(Unit) { c.screen.notices.collect { onMessage(it) } }
    // Arrived via the notification's "Look at screen" button: ask about that capture straight away.
    LaunchedEffect(pendingLook != null, on) { if (pendingLook != null && on) c.screen.askAboutScreen("Describe what's on my screen and what I might want to do next.") }

    val thumb = remember(frame) { frame?.let { f -> runCatching { Base64.decode(f.jpegBase64, Base64.NO_WRAP).let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap() }.getOrNull() } }

    ScreenScaffold("Screen Awareness", onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).imePadding().navigationBarsPadding()) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (on) "Screen awareness is ON" else "Screen awareness is OFF", style = MaterialTheme.typography.titleMedium, color = if (on) VoxColors.Cyan else VoxColors.Text)
                        Text(
                            if (on) "Vox captures your screen only when you ask. A notification stays visible while this is on."
                            else "Needs Android's screen-capture permission each session. Vox won't see anything until you allow it.",
                            style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    if (on) StatusPill("ACTIVE", VoxColors.Ok)
                }
                Spacer(Modifier.height(12.dp))
                if (on) GhostButton("Turn off screen awareness", { actions.toggle(Capability.SCREEN) }, Modifier.fillMaxWidth(), danger = true)
                else GlowButton("Allow screen awareness", { actions.toggle(Capability.SCREEN) }, Modifier.fillMaxWidth(), enabled = session.active)
                if (!session.active) Text("Activate Vox on the home screen first.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted, modifier = Modifier.padding(top = 6.dp))
            }

            if (on) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Describe" to "Describe what's on my screen.",
                        "Read text" to "Extract all the readable text on my screen, in order.",
                        "Summarize" to "Summarize what's visible on my screen.",
                        "Explain" to "Explain what I'm looking at and what I can do here.",
                    ).forEach { (label, prompt) ->
                        Text(
                            label, style = MaterialTheme.typography.labelSmall, color = VoxColors.Cyan,
                            modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, VoxColors.Outline, RoundedCornerShape(50)).clickable(enabled = !busy && !capturing) { c.screen.askAboutScreen(prompt) }.padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                    Text(
                        "Look in 5s", style = MaterialTheme.typography.labelSmall, color = VoxColors.Blue,
                        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, VoxColors.Blue, RoundedCornerShape(50)).clickable(enabled = !busy && !capturing) { c.screen.askAboutScreen("Describe what's on my screen.", delaySec = 5) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
                Text(
                    "These capture whatever is on screen right now - inside Vox that's Vox itself. To look at another app: tap \"Look in 5s\" and switch to it, or switch to it and tap \"Look at screen\" in Vox's notification.",
                    style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted, modifier = Modifier.padding(top = 6.dp),
                )
                if (countdown > 0) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Capturing in $countdown…", color = VoxColors.Blue, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        GhostButton("Cancel", { c.screen.cancelCountdown() })
                    }
                }
                thumb?.let {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(it, "Captured screen", Modifier.size(width = 64.dp, height = 112.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, VoxColors.Outline, RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        Text("  This is the screen Vox is answering about. Follow-up questions use it without capturing again.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            if (turns.isEmpty()) {
                Text(if (on) "Ask a question about your screen below, or use a shortcut above." else "", color = VoxColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            } else {
                ChatList(turns, Modifier.weight(1f), busy = busy || capturing)
            }
            val failure = (listen as? ListenState.Failed)?.message
            if (failure != null) Text(failure, color = VoxColors.Warn, style = MaterialTheme.typography.bodyMedium)

            MessageField(
                value = text, onValueChange = { text = it },
                onSend = { c.screen.followUp(text); text = "" },
                enabled = on, placeholder = if (frame == null) "Ask about your screen…" else "Ask a follow-up…",
                modifier = Modifier.padding(vertical = 8.dp),
                trailing = { MicButton(listening = listen is ListenState.Listening, enabled = on && voiceOn, onClick = { if (listen is ListenState.Listening) c.chat.stopListening() else c.screen.listen(followUp = frame != null) }) },
            )
        }
    }
}
