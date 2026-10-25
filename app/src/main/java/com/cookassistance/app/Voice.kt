package com.cookassistance.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

class VoiceController(private val context: Context, private val status: (Boolean, Boolean, Int?) -> Unit, private val recognized: (String) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var disposed = false
    private var generation = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    init {
        tts = TextToSpeech(context) { result ->
            if (!disposed && result == TextToSpeech.SUCCESS) {
                val language = tts?.setLanguage(Locale.forLanguageTag("ru-RU")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsReady = language >= TextToSpeech.LANG_AVAILABLE
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { if (!disposed) status(false, true, null) } }
            override fun onDone(id: String?) { main.post { if (!disposed) status(false, false, null) } }
            @Deprecated("Platform callback") override fun onError(id: String?) { main.post { if (!disposed) status(false, false, R.string.tts_unavailable) } }
        })
    }
    fun listen() {
        stop()
        val session = ++generation
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { status(false, false, R.string.speech_unavailable); return }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { if (!disposed && session == generation) status(true, false, null) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { if (!disposed && session == generation) status(false, false, null) }
                    override fun onError(error: Int) { if (!disposed && session == generation) status(false, false, R.string.speech_error) }
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
        } catch (_: Exception) { status(false, false, R.string.speech_unavailable) }
    }
    fun speak(text: String) {
        recognizer?.cancel()
        if (!ttsReady) { status(false, false, R.string.tts_unavailable); return }
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer") == TextToSpeech.ERROR) status(false, false, R.string.tts_unavailable)
    }
    fun stop() { generation++; recognizer?.cancel(); tts?.stop(); if (!disposed) status(false, false, null) }
    fun close() { disposed = true; recognizer?.cancel(); recognizer?.destroy(); tts?.stop(); tts?.shutdown(); main.removeCallbacksAndMessages(null) }
}

@Composable fun CookingScreen(id: Int, state: CookState, vm: CookViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var listening by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<Int?>(null) }
    var autoRequested by rememberSaveable { mutableStateOf(false) }
    val voice = remember(id) { VoiceController(context, { a, b, e -> listening = a; speaking = b; voiceError = e }, { vm.question(it); vm.ask() }) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) voice.listen() else voiceError = R.string.mic_denied
    }
    fun listen() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.listen()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(id) { vm.startCooking(id); if (!autoRequested) { autoRequested = true; listen() } }
    LaunchedEffect(state.answerSequence) {
        if (state.answer.isNotBlank() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) voice.speak(state.answer)
    }
    DisposableEffect(voice, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { voice.stop(); vm.cancelRequest() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice.close(); vm.stopCooking() }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("cooking_screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(back) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.assistant), style = MaterialTheme.typography.headlineLarge)
        state.selected?.let { Text(it.name, style = MaterialTheme.typography.titleLarge) }
        Text(stringResource(R.string.assistant_hint))
        Text(stringResource(R.string.voice_privacy), style = MaterialTheme.typography.bodySmall)
        when { listening -> Text(stringResource(R.string.listening)); speaking -> Text(stringResource(R.string.speaking)); state.thinking -> Text(stringResource(R.string.thinking)) }
        voiceError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        Button({ listen() }, enabled = !state.thinking && !listening, modifier = Modifier.fillMaxWidth().testTag("microphone_button")) { Text(stringResource(R.string.mic)) }
        if (listening || speaking || state.thinking) OutlinedButton({ voice.stop(); vm.cancelRequest() }) { Text(stringResource(R.string.stop)) }
        OutlinedTextField(state.question, vm::question, label = { Text(stringResource(R.string.question)) }, modifier = Modifier.fillMaxWidth().testTag("question_input"), minLines = 2, enabled = !state.thinking)
        Button({ voice.stop(); vm.ask() }, enabled = state.question.isNotBlank() && !state.thinking, modifier = Modifier.fillMaxWidth().testTag("ask_button")) { Text(stringResource(R.string.send)) }
        if (state.assistError) Text(stringResource(R.string.assist_error), color = MaterialTheme.colorScheme.error)
        if (state.ignored) Text(stringResource(R.string.ignored))
        if (state.answer.isNotBlank()) Card(Modifier.fillMaxWidth()) { Text(state.answer, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge) }
    }
}
