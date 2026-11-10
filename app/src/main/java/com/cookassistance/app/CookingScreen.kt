package com.cookassistance.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun CookingScreen(
    state: DetailUiState,
    cooking: CookingState,
    timer: TimerState,
    onQuestion: (String) -> Unit,
    onAsk: () -> Unit,
    onStartTimer: (Int) -> Unit,
    onPauseTimer: () -> Unit,
    onResumeTimer: () -> Unit,
    onResetTimer: () -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var listening by remember { mutableStateOf(false) }
    var recognizing by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<Int?>(null) }
    val voice = remember {
        VoiceController(context, { l, r, s, e -> listening = l; recognizing = r; speaking = s; voiceError = e }, onQuestion)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.listen() else voiceError = R.string.mic_denied
    }
    fun listen() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.listen()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(cooking.answerSequence) {
        if (cooking.answer.isNotBlank()) voice.speak(cooking.answer)
    }
    LaunchedEffect(timer.done) {
        if (timer.done) playAlertSound(context)
    }
    DisposableEffect(voice, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice.close() }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("cooking_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.cooking_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        (state as? DetailUiState.Content)?.let { Text(it.recipe.title, style = MaterialTheme.typography.titleLarge) }

        when {
            listening -> Text(stringResource(R.string.listening))
            recognizing -> Text(stringResource(R.string.recognizing))
            speaking -> Text(stringResource(R.string.speaking))
            voiceError != null -> {
                Text(stringResource(voiceError!!), color = MaterialTheme.colorScheme.error)
                if (voiceError == R.string.mic_denied) {
                    OutlinedButton(onClick = { context.openAppSettings() }, modifier = Modifier.testTag("open_settings")) {
                        Text(stringResource(R.string.open_settings))
                    }
                }
            }
        }

        Button(onClick = { listen() }, enabled = !listening && !recognizing && !cooking.thinking, modifier = Modifier.fillMaxWidth().testTag("microphone_button")) {
            Text(stringResource(R.string.mic))
        }
        if (listening || recognizing) {
            OutlinedButton(onClick = { voice.stop() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stop)) }
        }

        OutlinedTextField(
            value = cooking.question,
            onValueChange = onQuestion,
            label = { Text(stringResource(R.string.question_label)) },
            modifier = Modifier.fillMaxWidth().testTag("question_input"),
            minLines = 2,
            enabled = !cooking.thinking,
        )

        Button(onClick = { voice.stop(); onAsk() }, enabled = cooking.question.isNotBlank() && !cooking.thinking, modifier = Modifier.fillMaxWidth().testTag("ask_button")) {
            Text(stringResource(R.string.ask))
        }
        when {
            cooking.thinking -> Text(stringResource(R.string.thinking))
            cooking.assistError -> Text(stringResource(R.string.assist_error), color = MaterialTheme.colorScheme.error)
            cooking.ignored -> Text(stringResource(R.string.ignored), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (cooking.answer.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) { Text(cooking.answer, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge) }
        }

        // timer
        var timerMinutes by remember { mutableIntStateOf(10) }
        Text(stringResource(R.string.timer), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (timer.running || timer.remainingSeconds > 0 || timer.done) {
            Text(formatTime(timer.remainingSeconds), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("timer_display"))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { timerMinutes = (timerMinutes - 1).coerceAtLeast(1) }, modifier = Modifier.testTag("timer_minus")) { Text("−") }
                Text(stringResource(R.string.timer_minutes, timerMinutes))
                OutlinedButton(onClick = { timerMinutes = (timerMinutes + 1).coerceAtMost(180) }, modifier = Modifier.testTag("timer_plus")) { Text("+") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!timer.running && !timer.done && timer.remainingSeconds == 0) {
                Button(onClick = { onStartTimer(timerMinutes) }, modifier = Modifier.testTag("timer_start")) { Text(stringResource(R.string.start)) }
            }
            if (timer.running) OutlinedButton(onClick = onPauseTimer, modifier = Modifier.testTag("timer_pause")) { Text(stringResource(R.string.pause)) }
            if (!timer.running && timer.remainingSeconds > 0) OutlinedButton(onClick = onResumeTimer, modifier = Modifier.testTag("timer_resume")) { Text(stringResource(R.string.resume)) }
            if (timer.remainingSeconds > 0 || timer.done) OutlinedButton(onClick = onResetTimer, modifier = Modifier.testTag("timer_reset")) { Text(stringResource(R.string.reset)) }
        }
        if (timer.done) {
            Text(stringResource(R.string.timer_done), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("timer_done"))
        }
    }
}

fun formatTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun playAlertSound(context: Context) {
    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        context.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
