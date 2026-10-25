package com.cookassistance.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CookingScreen(state: DetailUiState, back: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).testTag("cooking_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.cooking_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        (state as? DetailUiState.Content)?.let {
            Text(it.recipe.title, style = MaterialTheme.typography.titleLarge)
        }
    }
}
