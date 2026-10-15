package ai.vox.app.data

import ai.vox.app.core.model.ResponseLength
import ai.vox.app.core.model.Settings
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore by preferencesDataStore("vox_settings")

/** Non-secret preferences. The gateway app token lives in [SecretStore], not here. */
class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val gatewayUrl = stringPreferencesKey("gateway_url")
        val voice = stringPreferencesKey("voice_name")
        val rate = floatPreferencesKey("speech_rate")
        val pitch = floatPreferencesKey("pitch")
        val length = stringPreferencesKey("response_length")
        val speak = booleanPreferencesKey("speak_replies")
        val continuous = booleanPreferencesKey("continuous")
        val save = booleanPreferencesKey("save_conversations")
        val liveInterval = intPreferencesKey("live_interval")
        val liveThreshold = floatPreferencesKey("live_threshold")
        val speakLive = booleanPreferencesKey("speak_live")
        val maxScreen = intPreferencesKey("max_screen")
        val maxLive = intPreferencesKey("max_live")
        val maxVideoFrames = intPreferencesKey("max_video_frames")
        val autoEnd = intPreferencesKey("auto_end_min")
        val consent = booleanPreferencesKey("consent")
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            gatewayUrl = this[K.gatewayUrl] ?: d.gatewayUrl,
            voiceName = this[K.voice],
            speechRate = this[K.rate] ?: d.speechRate,
            pitch = this[K.pitch] ?: d.pitch,
            responseLength = runCatching { ResponseLength.valueOf(this[K.length] ?: "") }.getOrDefault(d.responseLength),
            speakReplies = this[K.speak] ?: d.speakReplies,
            continuousConversation = this[K.continuous] ?: d.continuousConversation,
            saveConversations = this[K.save] ?: d.saveConversations,
            liveIntervalSec = this[K.liveInterval] ?: d.liveIntervalSec,
            liveChangeThreshold = this[K.liveThreshold] ?: d.liveChangeThreshold,
            speakLiveObservations = this[K.speakLive] ?: d.speakLiveObservations,
            maxScreenAnalysesPerSession = this[K.maxScreen] ?: d.maxScreenAnalysesPerSession,
            maxLiveFramesPerSession = this[K.maxLive] ?: d.maxLiveFramesPerSession,
            maxVideoFrames = this[K.maxVideoFrames] ?: d.maxVideoFrames,
            autoEndMinutes = this[K.autoEnd] ?: d.autoEndMinutes,
            consentAccepted = this[K.consent] ?: d.consentAccepted,
        )
    }

    /** Always has a value (defaults until DataStore loads) so non-suspending code can read `.value`. */
    val settings: StateFlow<Settings> = context.dataStore.data.map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, Settings())

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[K.gatewayUrl] = s.gatewayUrl
            if (s.voiceName != null) prefs[K.voice] = s.voiceName else prefs.remove(K.voice)
            prefs[K.rate] = s.speechRate
            prefs[K.pitch] = s.pitch
            prefs[K.length] = s.responseLength.name
            prefs[K.speak] = s.speakReplies
            prefs[K.continuous] = s.continuousConversation
            prefs[K.save] = s.saveConversations
            prefs[K.liveInterval] = s.liveIntervalSec
            prefs[K.liveThreshold] = s.liveChangeThreshold
            prefs[K.speakLive] = s.speakLiveObservations
            prefs[K.maxScreen] = s.maxScreenAnalysesPerSession
            prefs[K.maxLive] = s.maxLiveFramesPerSession
            prefs[K.maxVideoFrames] = s.maxVideoFrames
            prefs[K.autoEnd] = s.autoEndMinutes
            prefs[K.consent] = s.consentAccepted
        }
    }

    suspend fun resetAll() = context.dataStore.edit { it.clear() }
}
