package com.cookassistance.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceController(
    private val context: Context,
    private val status: (listening: Boolean, recognizing: Boolean, error: Int?) -> Unit,
    private val recognized: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var disposed = false
    private var generation = 0

    fun listen() {
        stop()
        val session = ++generation
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status(false, false, R.string.speech_unavailable)
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { if (!disposed && session == generation) status(true, false, null) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { if (!disposed && session == generation) status(false, true, null) }
                    override fun onError(error: Int) { if (!disposed && session == generation) status(false, false, speechErrorRes(error)) }
                    override fun onResults(results: Bundle?) {
                        if (disposed || session != generation) return
                        generation++
                        status(false, false, null)
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
            status(true, false, null)
        } catch (_: Exception) {
            status(false, false, R.string.speech_unavailable)
        }
    }

    fun stop() {
        generation++
        recognizer?.cancel()
        status(false, false, null)
    }

    fun close() {
        disposed = true
        recognizer?.cancel()
        recognizer?.destroy()
    }

    private fun speechErrorRes(error: Int): Int = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.speech_no_match
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.speech_network
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.speech_language
        else -> R.string.speech_error
    }
}
