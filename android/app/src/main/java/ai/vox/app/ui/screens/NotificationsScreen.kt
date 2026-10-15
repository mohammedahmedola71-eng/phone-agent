package ai.vox.app.ui.screens

import ai.vox.app.core.model.Capability
import ai.vox.app.core.notifications.ReplyState
import ai.vox.app.notifications.NotificationItem
import ai.vox.app.ui.components.CapabilityActions
import ai.vox.app.ui.components.GhostButton
import ai.vox.app.ui.components.GlowButton
import ai.vox.app.ui.components.LocalContainer
import ai.vox.app.ui.components.Panel
import ai.vox.app.ui.components.ScreenScaffold
import ai.vox.app.ui.components.StatusPill
import ai.vox.app.ui.components.markdownToAnnotated
import ai.vox.app.ui.theme.VoxColors
import ai.vox.app.ui.viewmodels.NotificationsViewModel
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
fun NotificationsScreen(actions: CapabilityActions, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val c = LocalContainer.current
    val vm: NotificationsViewModel = viewModel(factory = viewModelFactory { initializer { NotificationsViewModel(c) } })
    val session by c.session.state.collectAsState()
    val items by c.notifications.items.collectAsState()
    val selectedKey by vm.selectedKey.collectAsState()
    val machine by vm.machine.collectAsState()
    val summary by vm.summary.collectAsState()
    val summarizing by vm.summarizing.collectAsState()
    val notice by vm.notice.collectAsState()
    val on = Capability.NOTIFICATIONS in session.enabled
    val selected = items.firstOrNull { it.key == selectedKey }

    LaunchedEffect(notice) { notice?.let { onMessage(it); vm.clearNotice() } }

    ScreenScaffold("Notifications", onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding().navigationBarsPadding()) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (on) "Notification assistance is ON" else "Notification assistance is OFF", style = MaterialTheme.typography.titleMedium, color = if (on) VoxColors.Cyan else VoxColors.Text)
                        Text(
                            if (on) "Vox can read notifications that apps share with it until you turn this off or end the session. It never replies on its own."
                            else "Needs Android's notification-access permission. Vox ignores all notifications until you turn this on inside a session.",
                            style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted,
                        )
                        Text(
                            "System access: " + if (c.notifications.isAccessGranted()) "granted" else "not granted yet",
                            style = MaterialTheme.typography.labelSmall, color = if (c.notifications.isAccessGranted()) VoxColors.Ok else VoxColors.Warn, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (on) StatusPill("ACTIVE", VoxColors.Ok)
                }
                Spacer(Modifier.height(12.dp))
                if (on) GhostButton("Turn off notification assistance", { actions.toggle(Capability.NOTIFICATIONS) }, Modifier.fillMaxWidth(), danger = true)
                else GlowButton(if (c.notifications.isAccessGranted()) "Turn on notification assistance" else "Grant notification access", { actions.toggle(Capability.NOTIFICATIONS) }, Modifier.fillMaxWidth(), enabled = session.active)
                if (!session.active) Text("Activate Vox on the home screen first.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted, modifier = Modifier.padding(top = 6.dp))
            }

            if (on) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlowButton(if (summarizing) "Summarizing…" else "Summarize & prioritize", { vm.summarize(items) }, enabled = items.isNotEmpty() && !summarizing)
                    if (summary != null && Capability.VOICE in session.enabled) GhostButton("Read aloud", { vm.readAloud(summary.orEmpty()) })
                }
                summary?.let {
                    Spacer(Modifier.height(10.dp))
                    Panel { Text(markdownToAnnotated(it), style = MaterialTheme.typography.bodyMedium) }
                }

                Spacer(Modifier.height(14.dp))
                Text("${items.size} NOTIFICATION${if (items.size == 1) "" else "S"}", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
                Spacer(Modifier.height(8.dp))
                if (items.isEmpty()) Text("No readable notifications right now. Private or ongoing ones (calls, media, hidden-content) are skipped.", color = VoxColors.Muted, style = MaterialTheme.typography.bodyMedium)
                items.forEach { item -> NotificationRow(item, selected = item.key == selectedKey, onClick = { vm.select(if (item.key == selectedKey) null else item) }); Spacer(Modifier.height(8.dp)) }

                if (selected != null && machine != null) {
                    Spacer(Modifier.height(8.dp))
                    ReplyPanel(vm, selected)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NotificationRow(item: NotificationItem, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, if (selected) VoxColors.Cyan else VoxColors.Outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.appLabel.uppercase(), style = MaterialTheme.typography.labelSmall, color = VoxColors.Cyan, modifier = Modifier.weight(1f), maxLines = 1)
            if (item.canReply) StatusPill("CAN REPLY", VoxColors.Ok) else StatusPill("NO REPLY", VoxColors.Muted)
        }
        if (item.title.isNotBlank()) Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        if (item.text.isNotBlank()) Text(item.text, style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted, maxLines = 3)
    }
}

@Composable
private fun ReplyPanel(vm: NotificationsViewModel, item: NotificationItem) {
    val c = LocalContainer.current
    val machine = vm.machine.collectAsState().value ?: return
    val state by machine.state.collectAsState()
    val sessionState by c.session.state.collectAsState()
    var instruction by remember(item.key) { mutableStateOf("") }
    var draft by remember(item.key) { mutableStateOf("") }
    LaunchedEffect(state) { (state as? ReplyState.Drafted)?.let { if (it.text != draft) draft = it.text } }

    Panel {
        Text("Reply to ${item.appLabel}", style = MaterialTheme.typography.titleMedium)
        if (item.conversation.isNotEmpty()) Text(item.conversation.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(10.dp))

        if (!item.canReply) {
            Text("${item.appLabel} doesn't offer a reply action on this notification, so Vox can't reply from here. It never bypasses an app's own controls.", style = MaterialTheme.typography.bodyMedium, color = VoxColors.Warn)
            if (item.canOpenApp) { Spacer(Modifier.height(10.dp)); GlowButton("Open ${item.appLabel}", { vm.openApp(item) }, Modifier.fillMaxWidth()) }
            return@Panel
        }

        OutlinedTextField(instruction, { instruction = it }, Modifier.fillMaxWidth(), placeholder = { Text("Optional: tell Vox what to say (e.g. “say I'll be 10 minutes late”)") }, maxLines = 3)
        Spacer(Modifier.height(10.dp))
        val busy = state is ReplyState.Drafting || state is ReplyState.Sending
        GlowButton(if (state is ReplyState.Drafting) "Drafting…" else "Draft Reply", { vm.draft(item, instruction) }, Modifier.fillMaxWidth(), enabled = !busy)

        when (val s = state) {
            is ReplyState.Failed -> {
                Text(s.message, color = VoxColors.Danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                if (s.text.isNotBlank()) GhostButton("Back to editing", { machine.backToEditing() }, Modifier.padding(top = 6.dp))
            }
            ReplyState.Sent -> Text("Reply handed to ${item.appLabel}. Check the app to confirm it went through.", color = VoxColors.Ok, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            else -> {}
        }

        val editable = state is ReplyState.Drafted || state is ReplyState.Confirming
        if (editable) {
            Spacer(Modifier.height(12.dp))
            Text("EDIT REPLY", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
            OutlinedTextField(draft, { draft = it; vm.edit(it) }, Modifier.fillMaxWidth().padding(top = 6.dp), maxLines = 8)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (Capability.VOICE in sessionState.enabled) GhostButton("Read Aloud", { vm.readAloud(draft) }, Modifier.weight(1f))
                GhostButton("Cancel", { vm.cancel() }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            GlowButton("Send Reply", { vm.requestSend() }, Modifier.fillMaxWidth(), enabled = draft.isNotBlank() && state is ReplyState.Drafted)
        }
    }

    // The explicit approval step: shows the exact text that will be sent.
    (state as? ReplyState.Confirming)?.let { s ->
        AlertDialog(
            onDismissRequest = { machine.backToEditing() },
            containerColor = VoxColors.SurfaceHigh,
            title = { Text("Send this reply to ${item.appLabel}?") },
            text = { Text("“${s.text}”") },
            confirmButton = { TextButton(onClick = { vm.confirmSend(item) }) { Text("Send", color = VoxColors.Cyan) } },
            dismissButton = { TextButton(onClick = { machine.backToEditing() }) { Text("Back", color = VoxColors.Muted) } },
        )
    }
}
