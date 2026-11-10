package com.cookassistance.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun FavoritesScreen(favorites: List<Recipe>, open: (Recipe) -> Unit, back: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().testTag("favorites_list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TextButton(onClick = back) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.favorites), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        }
        if (favorites.isEmpty()) {
            item { Text(stringResource(R.string.favorites_empty)) }
        } else {
            items(favorites, key = { it.id }) { recipe -> RecipeCard(recipe) { open(recipe) } }
        }
    }
}
