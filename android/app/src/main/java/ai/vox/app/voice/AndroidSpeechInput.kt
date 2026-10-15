package ai.vox.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface ListenState {
    data object Idle : ListenState
    data class Listening(val partial: String = "") : ListenState
    data class Failed(val message: String) : ListenState
}

/**
 * Speech-to-text via the device's SpeechRecognizer. Must be used from the main thread.
 * The recognizer (and with it the microphone) is created for one utterance and destroyed right after.
 * Swap this class for a streaming/realtime voice backend later without touching the rest of the app.
 */
class AndroidSpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private val _state = MutableStateFlow<ListenState>(ListenState.Idle)
    val state: StateFlow<ListenState> = _state.asStateFlow()
    private val _level = MutableStateFlow(0f)
    /** Normalized 0..1 microphone level for the orb. */
    val level: StateFlow<Float> = _level.asStateFlow()

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(onFinal: (String) -> Unit) {
        stop()
        if (!isAvailable()) { _state.value = ListenState.Failed("Speech recognition isn't available on this device. Use the keyboard instead."); return }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = ListenState.Listening() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _level.value = 0f }
            override fun onError(error: Int) {
                release()
                _state.value = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenState.Failed("I didn't catch that.")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenState.Failed("Microphone permission is needed.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> ListenState.Failed("Speech recognition needs a network connection right now.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ListenState.Failed("The speech service is busy. Try again.")
                    SpeechRecognizer.ERROR_CLIENT -> ListenState.Idle // we cancelled it ourselves
                    else -> ListenState.Failed("Speech recognition failed (code $error).")
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                release()
                _state.value = ListenState.Idle
                if (text.isNotEmpty()) onFinal(text) else _state.value = ListenState.Failed("I didn't catch that.")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotEmpty()) _state.value = ListenState.Listening(t)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        _state.value = ListenState.Listening()
        r.startListening(intent)
    }

    /** Stops listening immediately and releases the microphone. */
    fun stop() {
        release()
        if (_state.value is ListenState.Listening) _state.value = ListenState.Idle
    }

    fun clearError() { if (_state.value is ListenState.Failed) _state.value = ListenState.Idle }

    private fun release() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        _level.value = 0f
    }
}
