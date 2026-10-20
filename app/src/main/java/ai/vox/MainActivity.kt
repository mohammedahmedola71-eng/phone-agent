package ai.vox

import ai.vox.agent.AgentLoop
import ai.vox.agent.ClaudeClient
import ai.vox.session.SessionState
import ai.vox.voice.VoiceIO
import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var voice: VoiceIO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = VoiceIO(this)
        setContent { MaterialTheme { VoxScreen() } }
    }

    override fun onDestroy() { SessionState.stop(); voice.shutdown(); super.onDestroy() }

    @Composable
    private fun VoxScreen() {
        val prefs = remember { getSharedPreferences("vox", Context.MODE_PRIVATE) }
        var apiKey by remember { mutableStateOf(prefs.getString("api_key", "").orEmpty()) }
        val active by SessionState.active.collectAsState()
        val log by SessionState.log.collectAsState()
        var pendingReply by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
        val scope = rememberCoroutineScope()
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("VOX AI", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; prefs.edit().putString("api_key", it).apply() },
                label = { Text("Anthropic API key") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Enable screen access") }
                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Enable notifications") }
            }
            Button(
                onClick = {
                    if (active) SessionState.stop() else { micPermission.launch(Manifest.permission.RECORD_AUDIO); SessionState.start() }
                },
                enabled = apiKey.isNotBlank(),
            ) { Text(if (active) "Stop session" else "Start session") }

            Button(
                enabled = active,
                onClick = {
                    val agent = AgentLoop(ClaudeClient(apiKey), approve = { text ->
                        val d = CompletableDeferred<Boolean>()
                        pendingReply = text to d
                        d.await()
                    })
                    voice.listen(onResult = { heard ->
                        SessionState.append("you: $heard")
                        scope.launch {
                            val answer = runCatching { agent.handle(heard) }.getOrElse { "Error: ${it.message}" }
                            SessionState.append("vox: $answer")
                            voice.speak(answer)
                        }
                    })
                },
            ) { Text("Talk to VOX") }

            LazyColumn(Modifier.weight(1f)) { items(log) { Text(it, style = MaterialTheme.typography.bodySmall) } }
        }

        pendingReply?.let { (text, d) ->
            AlertDialog(
                onDismissRequest = { d.complete(false); pendingReply = null },
                title = { Text("Send this reply?") },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = { d.complete(true); pendingReply = null }) { Text("Send") } },
                dismissButton = { TextButton(onClick = { d.complete(false); pendingReply = null }) { Text("Cancel") } },
            )
        }
    }
}
