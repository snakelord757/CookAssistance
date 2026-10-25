package com.cookassistance.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class VoiceController(
    private val context: Context,
    private val status: (listening: Boolean, recognizing: Boolean, speaking: Boolean, error: Int?) -> Unit,
    private val recognized: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var disposed = false
    private var generation = 0

    init {
        tts = TextToSpeech(context) { result ->
            if (!disposed && result == TextToSpeech.SUCCESS) {
                val language = tts?.setLanguage(Locale.forLanguageTag("ru-RU")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsReady = language >= TextToSpeech.LANG_AVAILABLE
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { if (!disposed) status(false, false, true, null) }
            override fun onDone(id: String?) { if (!disposed) status(false, false, false, null) }
            @Deprecated("Platform callback") override fun onError(id: String?) { if (!disposed) status(false, false, false, R.string.tts_unavailable) }
        })
    }

    fun listen() {
        stop()
        val session = ++generation
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status(false, false, false, R.string.speech_unavailable)
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { if (!disposed && session == generation) status(true, false, false, null) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { if (!disposed && session == generation) status(false, true, false, null) }
                    override fun onError(error: Int) { if (!disposed && session == generation) status(false, false, false, speechErrorRes(error)) }
                    override fun onResults(results: Bundle?) {
                        if (disposed || session != generation) return
                        generation++
                        status(false, false, false, null)
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(recognized)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            }
            status(true, false, false, null)
        } catch (_: Exception) {
            status(false, false, false, R.string.speech_unavailable)
        }
    }

    fun speak(text: String) {
        recognizer?.cancel()
        if (!ttsReady) {
            status(false, false, false, R.string.tts_unavailable)
            return
        }
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer") == TextToSpeech.ERROR) {
            status(false, false, false, R.string.tts_unavailable)
        }
    }

    fun stop() {
        generation++
        recognizer?.cancel()
        tts?.stop()
        status(false, false, false, null)
    }

    fun close() {
        disposed = true
        recognizer?.cancel()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    private fun speechErrorRes(error: Int): Int = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.speech_no_match
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.speech_network
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.speech_language
        else -> R.string.speech_error
    }
}
