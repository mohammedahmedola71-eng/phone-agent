package ai.vox.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Speech in (SpeechRecognizer) and speech out (TextToSpeech). Requires RECORD_AUDIO granted. */
class VoiceIO(context: Context) {
    private val app = context.applicationContext
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(app)
    private var tts: TextToSpeech? = TextToSpeech(app) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }

    fun listen(onResult: (String) -> Unit, onError: (Int) -> Unit = {}) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onResult)
            }
            override fun onError(error: Int) = onError(error)
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        })
    }

    fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vox") }

    fun shutdown() { recognizer.destroy(); tts?.shutdown(); tts = null }
}
