package ai.vox.app.ui.screens

import ai.vox.app.core.model.Capability
import ai.vox.app.core.model.OrbState
import ai.vox.app.core.model.Role
import ai.vox.app.ui.components.CapabilityActions
import ai.vox.app.ui.components.GhostButton
import ai.vox.app.ui.components.GlowButton
import ai.vox.app.ui.components.LocalContainer
import ai.vox.app.ui.components.MessageField
import ai.vox.app.ui.components.MicButton
import ai.vox.app.ui.components.Panel
import ai.vox.app.ui.components.VoxOrb
import ai.vox.app.ui.components.icon
import ai.vox.app.ui.components.markdownToAnnotated
import ai.vox.app.ui.theme.VoxColors
import ai.vox.app.voice.ListenState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(actions: CapabilityActions, onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val session by c.session.state.collectAsState()
    val status by c.status.collectAsState()
    val listen by c.chat.listenState.collectAsState()
    val level by c.chat.micLevel.collectAsState()
    val turns by c.chat.chat.turns.collectAsState()
    var text by remember { mutableStateOf("") }
    val active = session.active

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header: logo + shortcuts
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(VoxColors.Cyan))
            Spacer(Modifier.width(10.dp))
            Text("VOX", style = MaterialTheme.typography.headlineMedium, color = VoxColors.Text)
            Text(" AI", style = MaterialTheme.typography.headlineMedium, color = VoxColors.Cyan)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onNavigate("chat") }) { Icon(Icons.AutoMirrored.Filled.Chat, "Conversation", tint = VoxColors.Muted) }
            IconButton(onClick = { onNavigate("settings") }) { Icon(Icons.Filled.Settings, "Settings", tint = VoxColors.Muted) }
        }

        Spacer(Modifier.height(8.dp))
        Box(Modifier.clickable(enabled = active) { c.chat.interrupt() }, contentAlignment = Alignment.Center) {
            VoxOrb(status.orb, micLevel = level)
        }
        Text(status.label.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (active) VoxColors.Cyan else VoxColors.Muted, textAlign = TextAlign.Center)
        val partial = (listen as? ListenState.Listening)?.partial.orEmpty()
        val failure = (listen as? ListenState.Failed)?.message
        Text(
            when { partial.isNotEmpty() -> "“$partial”"; failure != null -> failure; !active -> "Vox only listens, looks or reads after you activate it."; else -> "Ask me anything, or turn on what I can use below." },
            style = MaterialTheme.typography.bodyMedium, color = if (failure != null) VoxColors.Warn else VoxColors.Muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp).height(42.dp),
        )

        if (!active) {
            GlowButton("ACTIVATE VOX", actions.activate, Modifier.fillMaxWidth())
        } else {
            // What Vox may use right now. Each is opt-in and can be switched off individually.
            Text("ALLOW VOX TO USE", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Capability.VOICE to "Voice", Capability.SCREEN to "Screen", Capability.NOTIFICATIONS to "Notifs").forEach { (cap, label) ->
                    val on = cap in session.enabled
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) VoxColors.Cyan.copy(alpha = 0.15f) else VoxColors.Surface)
                            .border(1.dp, if (on) VoxColors.Cyan else VoxColors.Outline, RoundedCornerShape(14.dp))
                            .clickable { actions.toggle(cap) }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(cap.icon(), null, tint = if (on) VoxColors.Cyan else VoxColors.Muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (on) "$label ON" else label, style = MaterialTheme.typography.labelSmall, color = if (on) VoxColors.Cyan else VoxColors.Muted)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        // Latest answer preview
        turns.lastOrNull { it.role == Role.ASSISTANT }?.let { last ->
            if (active) Panel(Modifier.clickable { onNavigate("chat") }) {
                Text(markdownToAnnotated(last.display), style = MaterialTheme.typography.bodyMedium, color = if (last.isError) VoxColors.Danger else VoxColors.Text, maxLines = 5)
            }
            Spacer(Modifier.height(12.dp))
        }

        MessageField(
            value = text, onValueChange = { text = it },
            onSend = { c.chat.send(text); text = "" },
            enabled = active, placeholder = if (active) "Type to Vox…" else "Activate Vox to chat",
            trailing = { MicButton(listening = listen is ListenState.Listening, enabled = active && Capability.VOICE in session.enabled, onClick = { if (listen is ListenState.Listening) c.chat.stopListening() else c.chat.listen() }) },
        )
        if (active && Capability.VOICE !in session.enabled) {
            Text("Turn on Voice to talk to Vox.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(20.dp))
        Text("QUICK ACTIONS", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("Screen Awareness", Icons.AutoMirrored.Filled.ScreenShare, Modifier.weight(1f)) { onNavigate("screen") }
            QuickAction("Live Video", Icons.Filled.Visibility, Modifier.weight(1f)) { onNavigate("live") }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("Analyze Video", Icons.Filled.VideoLibrary, Modifier.weight(1f)) { onNavigate("video") }
            QuickAction("Notifications", Icons.Filled.Notifications, Modifier.weight(1f)) { onNavigate("notifications") }
        }

        if (active) {
            Spacer(Modifier.height(24.dp))
            GhostButton("END SESSION", { c.endSessionAsync() }, Modifier.fillMaxWidth(), danger = true)
        }
        Spacer(Modifier.height(28.dp))
    }

    if (actions.showConsent) ConsentDialog(onAccept = actions.consentAccepted, onDismiss = actions.consentDismissed)
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(VoxColors.Surface).border(1.dp, VoxColors.Outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Icon(icon, null, tint = VoxColors.Cyan)
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Shown once, before the first activation: plain-language disclosure of what leaves the device. */
@Composable
fun ConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoxColors.SurfaceHigh,
        title = { Text("Before Vox starts") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Vox is off until you tap ACTIVATE VOX, and it stops completely when you tap END SESSION.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "To answer you, Vox sends what you ask - your typed or spoken words, and any screen capture, camera frame, video frame or notification text you choose to share - " +
                        "over HTTPS to your own Vox gateway, which passes it to the external AI service you configured (for example Anthropic or OpenAI) to generate a reply.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("The gateway is built not to store this content, but the AI provider's own data policy applies to what it receives. Don't share screens or notifications you wouldn't want processed there.", style = MaterialTheme.typography.bodyMedium)
                Text("Microphone, screen, camera and notification access are each requested separately, only when you switch them on, and Android shows its own indicators while they run.", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Agree & activate", color = VoxColors.Cyan) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", color = VoxColors.Muted) } },
    )
}
