package ai.vox.app.ui.screens

import ai.vox.app.BuildConfig
import ai.vox.app.core.model.ResponseLength
import ai.vox.app.core.net.AiException
import ai.vox.app.core.net.GatewayStatus
import ai.vox.app.core.session.UsageKind
import ai.vox.app.ui.components.GhostButton
import ai.vox.app.ui.components.GlowButton
import ai.vox.app.ui.components.LocalContainer
import ai.vox.app.ui.components.Panel
import ai.vox.app.ui.components.ScreenScaffold
import ai.vox.app.ui.theme.VoxColors
import ai.vox.app.voice.VoiceOption
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit, onMessage: (String) -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.collectAsState()
    val session by c.session.state.collectAsState()
    var url by remember(s.gatewayUrl) { mutableStateOf(s.gatewayUrl) }
    var token by remember { mutableStateOf("") }
    var tokenSaved by remember { mutableStateOf(c.secrets.getToken().isNotBlank()) }
    var voices by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
    var voiceMenu by remember { mutableStateOf(false) }
    var gateway by remember { mutableStateOf<Result<GatewayStatus>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { c.speechOut.listVoices { voices = it } }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun openAppSettings() = context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    ScreenScaffold("Settings", onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Section("AI GATEWAY") {
                Text("Vox never holds AI provider keys. It talks to your own gateway over HTTPS; the gateway holds the provider key and decides which model to use.", style = MaterialTheme.typography.bodyMedium, color = VoxColors.Muted)
                Field("Gateway address", url, { url = it }, placeholder = "https://vox.example.com", keyboard = KeyboardType.Uri)
                Field("App token", token, { token = it }, placeholder = if (tokenSaved) "Saved securely - enter a new one to replace" else "Paste your app token", password = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlowButton("Save", {
                        c.updateSettings { it.copy(gatewayUrl = url.trim()) }
                        if (token.isNotBlank()) { c.secrets.setToken(token); token = ""; tokenSaved = true }
                        onMessage("Gateway settings saved.")
                    })
                    GhostButton(if (testing) "Testing…" else "Test connection", {
                        testing = true; gateway = null
                        scope.launch { gateway = runCatching { c.provider.status() }; testing = false }
                    }, enabled = !testing)
                }
                gateway?.let { r ->
                    r.onSuccess { g ->
                        Text("Connected · ${g.provider} / ${g.model}", color = VoxColors.Ok, style = MaterialTheme.typography.bodyMedium)
                        Text("Today: ${g.requestsToday}/${g.requestsPerDay} requests · ${g.imagesToday}/${g.imagesPerDay} images · ${g.requestsPerMinute}/min · max ${g.maxImagesPerRequest} images per request", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
                    }
                    r.onFailure { Text((it as? AiException)?.userMessage ?: "Connection failed.", color = VoxColors.Danger, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            Section("VOICE") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Voice", Modifier.weight(1f))
                    Column {
                        GhostButton(voices.firstOrNull { it.id == s.voiceName }?.label ?: "Automatic (best available)", { voiceMenu = true })
                        DropdownMenu(voiceMenu, { voiceMenu = false }) {
                            DropdownMenuItem(text = { Text("Automatic (best available)") }, onClick = { c.updateSettings { it.copy(voiceName = null) }; voiceMenu = false })
                            voices.forEach { v -> DropdownMenuItem(text = { Text(v.label) }, onClick = { c.updateSettings { it.copy(voiceName = v.id) }; voiceMenu = false }) }
                        }
                    }
                }
                SliderRow("Speaking speed", s.speechRate, 0.5f..2f, "%.1fx") { v -> c.updateSettings { it.copy(speechRate = v) } }
                SliderRow("Pitch", s.pitch, 0.7f..1.4f, "%.1f") { v -> c.updateSettings { it.copy(pitch = v) } }
                GhostButton("Preview voice", { c.speechOut.speak("Hello. I'm Vox, your personal assistant. This is how I sound.") })
                Text("Response length", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResponseLength.entries.forEach { l ->
                        val sel = s.responseLength == l
                        Text(l.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = if (sel) VoxColors.Cyan else VoxColors.Muted,
                            modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (sel) VoxColors.Cyan else VoxColors.Outline, RoundedCornerShape(50)).clickable { c.updateSettings { it.copy(responseLength = l) } }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                SwitchRow("Speak replies aloud when Voice is on", s.speakReplies) { v -> c.updateSettings { it.copy(speakReplies = v) } }
                SwitchRow("Keep listening after Vox replies", s.continuousConversation) { v -> c.updateSettings { it.copy(continuousConversation = v) } }
                Text("Uses your phone's speech engine. For more natural voices install a high-quality voice in Android Settings > System > Languages > Text-to-speech.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
            }

            Section("SESSION") {
                SliderRow("Auto-end session after", s.autoEndMinutes.toFloat(), 0f..120f, "%.0f min (0 = never)", steps = 7) { v -> c.updateSettings { it.copy(autoEndMinutes = v.toInt()) } }
                SliderRow("Live analysis interval", s.liveIntervalSec.toFloat(), 2f..30f, "%.0f s") { v -> c.updateSettings { it.copy(liveIntervalSec = v.toInt()) } }
                SliderRow("Live change sensitivity", 0.12f - s.liveChangeThreshold, 0f..0.11f, "%.2f") { v -> c.updateSettings { it.copy(liveChangeThreshold = (0.12f - v).coerceIn(0.01f, 0.12f)) } }
                Text("Higher sensitivity sends more frames when the picture changes a little.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
                SwitchRow("Speak live observations", s.speakLiveObservations) { v -> c.updateSettings { it.copy(speakLiveObservations = v) } }
            }

            Section("USAGE LIMITS (PER SESSION)") {
                SliderRow("Screen analyses", s.maxScreenAnalysesPerSession.toFloat(), 5f..200f, "%.0f") { v -> c.updateSettings { it.copy(maxScreenAnalysesPerSession = v.toInt()) } }
                SliderRow("Live frames", s.maxLiveFramesPerSession.toFloat(), 10f..500f, "%.0f") { v -> c.updateSettings { it.copy(maxLiveFramesPerSession = v.toInt()) } }
                SliderRow("Frames per video analysis", s.maxVideoFrames.toFloat(), 4f..32f, "%.0f", steps = 6) { v -> c.updateSettings { it.copy(maxVideoFrames = v.toInt()) } }
                Text("Used this session: ${c.usage.used(UsageKind.SCREEN_ANALYSIS)} screen analyses · ${c.usage.used(UsageKind.LIVE_FRAME)} live frames · ${c.usage.used(UsageKind.VIDEO_FRAME)} video frames. Your gateway enforces its own daily limits too.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
            }

            Section("PRIVACY & PERMISSIONS") {
                PermRow("Microphone", granted(Manifest.permission.RECORD_AUDIO))
                PermRow("Camera", granted(Manifest.permission.CAMERA))
                if (Build.VERSION.SDK_INT >= 33) PermRow("Post notifications (capture indicator)", granted(Manifest.permission.POST_NOTIFICATIONS))
                PermRow("Notification access (system)", c.notifications.isAccessGranted())
                PermRow("Screen capture", session.enabled.contains(ai.vox.app.core.model.Capability.SCREEN), onText = "On now", offText = "Asked every session")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("App permissions", { openAppSettings() })
                    GhostButton("Notification access", { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                }
                SwitchRow("Save conversation text on this device", s.saveConversations) { v ->
                    c.updateSettings { it.copy(saveConversations = v) }
                    if (!v) c.conversations.deleteAll()
                }
                Text("Off by default. Even when on, only chat text is saved - never screenshots, frames, videos or notification contents. Vox does not use Accessibility Service.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
                Text("When you use Vox, the content you share is sent over HTTPS to your gateway and on to the AI provider it's configured with. Revoke any permission at any time in Android settings; ending the session stops everything.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
            }

            Section("DATA") {
                GhostButton("Delete saved conversations & cached files", { c.deleteConversations(); onMessage("Conversations and cached files deleted.") }, Modifier.fillMaxWidth())
                GhostButton("Delete everything (also resets settings & token)", { confirmDelete = true }, Modifier.fillMaxWidth(), danger = true)
                Text("Uploaded videos are never stored by Vox or the gateway: only sampled frames are sent, kept in memory, and dropped when you remove the video or end the session.", style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted)
            }

            Section("DIAGNOSTICS") {
                val d = listOf(
                    "Vox" to "${BuildConfig.VERSION_NAME} (${if (BuildConfig.DEBUG) "debug" else "release"})",
                    "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
                    "Speech recognition" to if (c.speechIn.isAvailable()) "available" else "not available",
                    "Voices found" to voices.size.toString(),
                    "Session" to if (session.active) "active · ${session.enabled.joinToString { it.label }.ifEmpty { "no access granted" }}" else "inactive",
                    "Gateway" to s.gatewayUrl.ifBlank { "not set" },
                )
                d.forEach { (k, v) -> Row { Text(k, Modifier.weight(0.4f), style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted); Text(v, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium) } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = VoxColors.SurfaceHigh,
        title = { Text("Delete everything?") },
        text = { Text("This ends any session, deletes saved conversations and cached files, removes your gateway token and resets all settings.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { c.deleteEverything(); tokenSaved = false; onMessage("Everything deleted.") } }) { Text("Delete", color = VoxColors.Danger) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = VoxColors.Muted) } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall, color = VoxColors.Muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Panel { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(placeholder, color = VoxColors.Muted) }, singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, steps: Int = 0, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column {
        Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(String.format(format, local), style = MaterialTheme.typography.labelSmall, color = VoxColors.Cyan) }
        Slider(local, { local = it }, valueRange = range, steps = steps, onValueChangeFinished = { onChange(local) }, colors = SliderDefaults.colors(thumbColor = VoxColors.Cyan, activeTrackColor = VoxColors.Cyan, inactiveTrackColor = VoxColors.Outline))
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, onText: String = "Granted", offText: String = "Not granted") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(if (ok) onText else offText, style = MaterialTheme.typography.labelSmall, color = if (ok) VoxColors.Ok else VoxColors.Muted)
    }
}
