package ai.vox.app.ui.screens

import ai.vox.app.core.model.Capability
import ai.vox.app.core.model.Role
import ai.vox.app.core.video.VideoLimits
import ai.vox.app.core.video.VideoPlan
import ai.vox.app.core.video.VideoTask
import ai.vox.app.ui.components.ChatList
import ai.vox.app.ui.components.GhostButton
import ai.vox.app.ui.components.GlowButton
import ai.vox.app.ui.components.LocalContainer
import ai.vox.app.ui.components.MessageField
import ai.vox.app.ui.components.Panel
import ai.vox.app.ui.components.ScreenScaffold
import ai.vox.app.ui.components.StatusPill
import ai.vox.app.ui.theme.VoxColors
import ai.vox.app.ui.viewmodels.VideoLabViewModel
import ai.vox.app.ui.viewmodels.VideoPhase
import ai.vox.app.video.VideoLabEngine
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
fun VideoLabScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val vm: VideoLabViewModel = viewModel(factory = viewModelFactory { initializer { VideoLabViewModel(c) } })
    val meta by vm.meta.collectAsState()
    val phase by vm.phase.collectAsState()
    val pending by vm.pending.collectAsState()
    val turns by vm.turns.collectAsState()
    val busy by vm.busy.collectAsState()
    val session by c.session.state.collectAsState()
    val settings by c.settings.collectAsState()
    var task by remember { mutableStateOf(VideoTask.SUMMARY) }
    var question by remember { mutableStateOf("") }
    var followUp by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pick(uri) }
    val active = session.active
    val working = phase is VideoPhase.Extracting || phase is VideoPhase.Analyzing
    val limits = VideoLimits(maxFrames = settings.maxVideoFrames.coerceIn(4, 32))
    val hasResult = turns.any { it.role == Role.ASSISTANT && !it.isError }

    ScreenScaffold("Video Lab", onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding().navigationBarsPadding()) {
            Panel {
                Text("Pick a video, or share one to Vox from another app.", style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted)
                Text(
                    "Limits: up to ${VideoPlan.format(limits.maxDurationMs)} long · ${limits.maxFrames} frames per analysis. The video file stays on your phone; only still frames you confirm are sent to the AI service.",
                    style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(12.dp))
                GlowButton(if (meta == null) "Choose a video" else "Choose a different video", { picker.launch(arrayOf("video/*")) }, Modifier.fillMaxWidth(), enabled = !working)
            }

            meta?.let { m ->
                Spacer(Modifier.height(12.dp))
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bmp = m.thumbnail?.asImageBitmap()
                        if (bmp != null) Image(bmp, "Video thumbnail", Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        else Text("No preview", color = VoxColors.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.size(width = 120.dp, height = 68.dp))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            Text(VideoLabEngine.describe(m), style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted)
                            Spacer(Modifier.height(4.dp))
                            when (val p = phase) {
                                VideoPhase.Loading -> StatusPill("READING…", VoxColors.Cyan)
                                is VideoPhase.Ready -> if (p.rejection == null) StatusPill("READY", VoxColors.Ok) else StatusPill("CAN'T ANALYZE", VoxColors.Danger)
                                is VideoPhase.Extracting -> StatusPill("EXTRACTING ${p.done}/${p.total}", VoxColors.Cyan)
                                VideoPhase.Analyzing -> StatusPill("ANALYZING", VoxColors.Cyan)
                                VideoPhase.Done -> StatusPill("DONE", VoxColors.Ok)
                                is VideoPhase.Failed -> StatusPill("FAILED", VoxColors.Danger)
                                VideoPhase.Empty -> {}
                            }
                        }
                    }
                    (phase as? VideoPhase.Ready)?.rejection?.let { Text(it, color = VoxColors.Danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                    (phase as? VideoPhase.Failed)?.let { Text(it.message, color = VoxColors.Danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }

                    when (val p = phase) {
                        is VideoPhase.Extracting -> {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done / p.total.toFloat() }, Modifier.fillMaxWidth(), color = VoxColors.Cyan, trackColor = VoxColors.Outline)
                            Text("Reading frames on your phone…", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted, modifier = Modifier.padding(top = 4.dp))
                        }
                        VideoPhase.Analyzing -> {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = VoxColors.Cyan, trackColor = VoxColors.Outline)
                            Text("Waiting for the AI service. This can take a minute.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted, modifier = Modifier.padding(top = 4.dp))
                        }
                        else -> {}
                    }
                    if (working) { Spacer(Modifier.height(10.dp)); GhostButton("Cancel", vm::cancel, Modifier.fillMaxWidth(), danger = true) }
                }

                val canAnalyze = (phase as? VideoPhase.Ready)?.rejection == null && phase !is VideoPhase.Loading && phase !is VideoPhase.Empty && !working
                Spacer(Modifier.height(12.dp))
                Panel {
                    Text("WHAT SHOULD VOX DO?", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoTask.entries.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { t ->
                                    val sel = task == t
                                    Text(
                                        t.label, style = MaterialTheme.typography.labelSmall,
                                        color = when { !t.supported -> VoxColors.Muted.copy(alpha = 0.5f); sel -> VoxColors.Cyan; else -> VoxColors.Text },
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, if (sel) VoxColors.Cyan else VoxColors.Outline, RoundedCornerShape(12.dp))
                                            .clickable { task = t }.padding(12.dp),
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    task.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = VoxColors.Warn, modifier = Modifier.padding(top = 8.dp)) }
                    if (task == VideoTask.ASK) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), placeholder = { Text("What do you want to know about this video?") })
                    }
                    Spacer(Modifier.height(12.dp))
                    GlowButton("Analyze", { vm.requestAnalysis(task, question) }, Modifier.fillMaxWidth(), enabled = canAnalyze && active && task.supported && (task != VideoTask.ASK || question.isNotBlank()))
                    if (!active) Text("Activate Vox on the home screen to analyze - it only processes things during a session.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Warn, modifier = Modifier.padding(top = 6.dp))
                }

                if (turns.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("RESULTS", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().height(380.dp)) { ChatList(turns, Modifier.weight(1f), busy = busy) }
                    if (hasResult) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (Capability.VOICE in session.enabled) {
                                GhostButton("Read aloud", { turns.lastOrNull { it.role == Role.ASSISTANT && !it.isError }?.let { vm.readAloud(it.display) } })
                                GhostButton("Stop", vm::stopSpeaking)
                            }
                        }
                        MessageField(followUp, { followUp = it }, { vm.followUp(followUp); followUp = "" }, enabled = active && !working, placeholder = "Ask a follow-up about the video…", modifier = Modifier.padding(top = 10.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                GhostButton("Remove video & delete results", vm::discard, Modifier.fillMaxWidth(), enabled = !working)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = vm::cancelPending,
            containerColor = VoxColors.SurfaceHigh,
            title = { Text("Send frames to the AI service?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Vox will pull ${p.frameCount} still frames from this video (about one every ${p.everySec}s) on your phone and send them over HTTPS to your Vox gateway. The gateway forwards them to the external AI provider you configured to analyze them.")
                    Text("The video file itself is not uploaded, and audio is not analyzed. The frames are kept in memory only and discarded when you remove the video or end the session.")
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmUpload) { Text("Send frames", color = VoxColors.Cyan) } },
            dismissButton = { TextButton(onClick = vm::cancelPending) { Text("Cancel", color = VoxColors.Muted) } },
        )
    }
}
