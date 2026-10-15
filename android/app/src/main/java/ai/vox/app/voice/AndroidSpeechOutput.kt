package ai.vox.app.voice

import ai.vox.app.core.model.Settings
import ai.vox.app.core.voice.SpeechTextCleaner
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

data class VoiceOption(val id: String, val label: String, val network: Boolean)

/**
 * Text-to-speech through the device's engine. Replies are cleaned of Markdown and spoken sentence by sentence
 * so prosody sounds natural and "stop" takes effect within a phrase. Main-thread use.
 */
class AndroidSpeechOutput(private val context: Context, private val settings: () -> Settings) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var batch = ""
    private var remaining = 0
    private var onDone: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private fun ensure(then: () -> Unit) {
        if (ready) { then(); return }
        pending = then
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(listener)
                pending?.invoke()
            } else {
                _speaking.value = false
                onDone?.invoke()
            }
            pending = null
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { _speaking.value = true }
        override fun onDone(utteranceId: String?) = chunkFinished(utteranceId)
        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = chunkFinished(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = chunkFinished(utteranceId)
    }

    private fun chunkFinished(id: String?) {
        // Utterance callbacks arrive on a binder thread. Only act on the current batch.
        if (id == null || !id.startsWith(batch)) return
        synchronized(this) {
            remaining -= 1
            if (remaining > 0) return
        }
        _speaking.value = false
        // Utterance callbacks come from a binder thread; the follow-up (e.g. start listening) must run on main.
        main.post {
            val cb = onDone
            onDone = null
            cb?.invoke()
        }
    }

    /** Speaks [markdown], replacing anything currently being said. [done] runs when finished (not when interrupted). */
    fun speak(markdown: String, done: (() -> Unit)? = null) {
        val chunks = SpeechTextCleaner.chunks(markdown)
        if (chunks.isEmpty()) { done?.invoke(); return }
        ensure {
            val engine = tts ?: return@ensure
            stopInternal(engine)
            applySettings(engine)
            val id = UUID.randomUUID().toString()
            synchronized(this) { batch = id; remaining = chunks.size }
            onDone = done
            _speaking.value = true
            chunks.forEachIndexed { i, c ->
                engine.speak(c, TextToSpeech.QUEUE_ADD, Bundle(), "$id-$i")
            }
        }
    }

    /** Interrupts speech immediately. Does not call the `done` callback. */
    fun stop() {
        tts?.let { stopInternal(it) }
        _speaking.value = false
    }

    private fun stopInternal(engine: TextToSpeech) {
        onDone = null
        synchronized(this) { batch = "stopped"; remaining = 0 }
        engine.stop()
    }

    /** Releases the TTS engine. It is recreated on next use. */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
    }

    private fun applySettings(engine: TextToSpeech) {
        val s = settings()
        engine.setSpeechRate(s.speechRate)
        engine.setPitch(s.pitch)
        val chosen = s.voiceName?.let { name -> engine.voices?.firstOrNull { it.name == name } }
        engine.voice = chosen ?: bestDefaultVoice(engine) ?: engine.voice
        if (engine.voice == null) engine.language = Locale.getDefault()
    }

    private fun bestDefaultVoice(engine: TextToSpeech): Voice? {
        val lang = Locale.getDefault().language
        return engine.voices?.filter { it.locale.language == lang && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            ?.maxByOrNull { it.quality * 10 + if (it.locale == Locale.getDefault()) 5 else 0 }
    }

    /** Voices for the current language, best quality first. Calls [onResult] once the engine is ready. */
    fun listVoices(onResult: (List<VoiceOption>) -> Unit) = ensure {
        val lang = Locale.getDefault().language
        val voices = tts?.voices.orEmpty()
            .filter { it.locale.language == lang && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedByDescending { it.quality }
            .mapIndexed { i, v -> VoiceOption(v.name, "Voice ${i + 1} - ${v.locale.displayCountry.ifBlank { v.locale.displayLanguage }}${if (v.isNetworkConnectionRequired) " (online)" else ""}", v.isNetworkConnectionRequired) }
        onResult(voices)
    }
}
