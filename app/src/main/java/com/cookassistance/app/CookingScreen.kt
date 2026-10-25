package com.cookassistance.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onQuestion: (String) -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var listening by remember { mutableStateOf(false) }
    var recognizing by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<Int?>(null) }
    val voice = remember {
        VoiceController(context, { l, r, e -> listening = l; recognizing = r; voiceError = e }, onQuestion)
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
            voiceError != null -> {
                Text(stringResource(voiceError!!), color = MaterialTheme.colorScheme.error)
                if (voiceError == R.string.mic_denied) {
                    OutlinedButton(onClick = { context.openAppSettings() }, modifier = Modifier.testTag("open_settings")) {
                        Text(stringResource(R.string.open_settings))
                    }
                }
            }
        }

        Button(onClick = { listen() }, enabled = !listening && !recognizing, modifier = Modifier.fillMaxWidth().testTag("microphone_button")) {
            Text(stringResource(R.string.mic))
        }
        if (listening || recognizing) {
            OutlinedButton(onClick = { voice.stop() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stop)) }
        }

        Text(stringResource(R.string.question_label), style = MaterialTheme.typography.titleMedium)
        if (cooking.question.isBlank()) {
            Text(stringResource(R.string.question_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(cooking.question, style = MaterialTheme.typography.bodyLarge)
        }
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
