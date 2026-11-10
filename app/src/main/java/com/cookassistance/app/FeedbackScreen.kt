package com.cookassistance.app

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun FeedbackScreen(
    state: FeedbackState,
    onMessage: (String) -> Unit,
    onContact: (String) -> Unit,
    onSubmit: () -> Unit,
    back: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("feedback_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.feedback_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = state.message,
            onValueChange = onMessage,
            label = { Text(stringResource(R.string.feedback_message)) },
            modifier = Modifier.fillMaxWidth().testTag("feedback_message"),
            minLines = 3,
            isError = state.messageError,
        )
        if (state.messageError) {
            Text(stringResource(R.string.feedback_required), color = MaterialTheme.colorScheme.error)
        }

        OutlinedTextField(
            value = state.contact,
            onValueChange = onContact,
            label = { Text(stringResource(R.string.feedback_contact)) },
            modifier = Modifier.fillMaxWidth().testTag("feedback_contact"),
        )

        Button(onClick = onSubmit, enabled = !state.sending, modifier = Modifier.fillMaxWidth().testTag("feedback_submit")) {
            Text(stringResource(R.string.send))
        }
        when {
            state.sending -> Text(stringResource(R.string.sending))
            state.sent -> Text(stringResource(R.string.feedback_sent), color = MaterialTheme.colorScheme.primary)
            state.error -> Text(stringResource(R.string.feedback_error), color = MaterialTheme.colorScheme.error)
        }
    }
}
