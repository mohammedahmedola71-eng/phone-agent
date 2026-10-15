package ai.vox.app.ui.screens

import ai.vox.app.core.model.Capability
import ai.vox.app.core.model.Role
import ai.vox.app.ui.components.CapabilityActions
import ai.vox.app.ui.components.ChatList
import ai.vox.app.ui.components.LocalContainer
import ai.vox.app.ui.components.MessageField
import ai.vox.app.ui.components.MicButton
import ai.vox.app.ui.components.ScreenScaffold
import ai.vox.app.ui.theme.VoxColors
import ai.vox.app.voice.ListenState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun ConversationScreen(actions: CapabilityActions, onBack: () -> Unit) {
    val c = LocalContainer.current
    val session by c.session.state.collectAsState()
    val turns by c.chat.chat.turns.collectAsState()
    val busy by c.chat.chat.busy.collectAsState()
    val speaking by c.chat.speaking.collectAsState()
    val listen by c.chat.listenState.collectAsState()
    val attach by c.chat.attachScreen.collectAsState()
    val settings by c.settings.collectAsState()
    var text by remember { mutableStateOf("") }
    val active = session.active
    val voiceOn = Capability.VOICE in session.enabled

    ScreenScaffold("Conversation", onBack, actions = {
        if (speaking || busy) IconButton(onClick = { c.chat.interrupt() }) { Icon(Icons.Filled.StopCircle, "Stop", tint = VoxColors.Danger) }
        IconButton(onClick = { c.chat.regenerate() }, enabled = active && !busy && turns.any { it.role == Role.USER }) { Icon(Icons.Filled.Autorenew, "Regenerate", tint = VoxColors.Muted) }
        IconButton(onClick = { c.chat.chat.clear() }) { Icon(Icons.Filled.DeleteSweep, "Clear conversation", tint = VoxColors.Muted) }
    }) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Keep listening after replies", style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted, modifier = Modifier.weight(1f))
            Switch(
                checked = settings.continuousConversation, enabled = voiceOn,
                onCheckedChange = { v -> c.updateSettings { it.copy(continuousConversation = v) } },
            )
        }
        if (Capability.SCREEN in session.enabled) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Include what's on screen with each message", style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted, modifier = Modifier.weight(1f))
                Switch(checked = attach, onCheckedChange = { c.chat.setAttachScreen(it) })
            }
        }

        if (turns.isEmpty()) {
            Text(
                if (active) "Say or type something. Follow-up questions work - Vox remembers this conversation until the session ends${if (settings.saveConversations) " (and saves it on this device)" else ""}."
                else "Activate Vox from the home screen to start a conversation.",
                color = VoxColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(8.dp),
            )
        } else {
            ChatList(turns, Modifier.weight(1f), busy = busy)
        }

        val last = turns.lastOrNull()
        if (active && last != null && last.role == Role.ASSISTANT && !last.isError && !busy) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Tell me more", "Shorter", "Give an example").forEach { s ->
                    Text(
                        s, style = MaterialTheme.typography.labelSmall, color = VoxColors.Cyan,
                        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, VoxColors.Outline, RoundedCornerShape(50)).clickable { c.chat.send(s) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        val partial = (listen as? ListenState.Listening)?.partial
        if (partial != null) Text(if (partial.isEmpty()) "Listening…" else "“$partial”", color = VoxColors.Cyan, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        (listen as? ListenState.Failed)?.let { Text(it.message, color = VoxColors.Warn, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp)) }

        MessageField(
            value = text, onValueChange = { text = it }, onSend = { c.chat.send(text); text = "" },
            enabled = active, placeholder = if (active) "Message Vox…" else "Activate Vox first",
            modifier = Modifier.padding(bottom = 8.dp),
            trailing = { MicButton(listening = listen is ListenState.Listening, enabled = active && voiceOn, onClick = { if (listen is ListenState.Listening) c.chat.stopListening() else c.chat.listen() }) },
        )
        if (active && !voiceOn) Text("Voice is off - text chat works. Turn on Voice on the home screen to talk.", color = VoxColors.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 8.dp))
    }
    }
}
