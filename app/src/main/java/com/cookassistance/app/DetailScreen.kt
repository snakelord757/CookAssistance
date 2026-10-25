package com.cookassistance.app

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
import androidx.compose.ui.unit.dp

@Composable
fun DetailScreen(id: String, back: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).testTag("recipe_detail")) {
        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.recipe_id, id), style = MaterialTheme.typography.titleLarge)
    }
}
