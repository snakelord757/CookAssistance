package com.cookassistance.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun DetailScreen(
    state: DetailUiState,
    favorites: List<Recipe>,
    saving: Boolean,
    saveError: Boolean,
    retry: () -> Unit,
    back: () -> Unit,
    cook: () -> Unit,
    toggle: (Recipe) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().testTag("recipe_detail"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { TextButton(onClick = back) { Text(stringResource(R.string.back)) } }
        when (state) {
            DetailUiState.Loading -> item { CircularProgressIndicator() }
            DetailUiState.Error -> item { ErrorMessage(R.string.detail_error, retry) }
            is DetailUiState.Content -> {
                val recipe = state.recipe
                item {
                    AsyncImage(recipe.imageUrl, null, Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Crop)
                    Spacer(Modifier.height(16.dp))
                    Text(recipe.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(recipe.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(typeLabel(recipe.type), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.minutes, recipe.cookingTimeMin))
                        Text(stringResource(R.string.servings, recipe.servings))
                    }
                }
                item {
                    Button(onClick = cook, modifier = Modifier.fillMaxWidth().testTag("cook_button")) {
                        Text(stringResource(R.string.cook))
                    }
                    OutlinedButton(
                        onClick = { toggle(recipe) },
                        modifier = Modifier.fillMaxWidth().testTag("favorite_button"),
                        enabled = !saving,
                    ) {
                        Text(stringResource(if (favorites.any { it.id == recipe.id }) R.string.saved else R.string.save))
                    }
                    if (saveError) Text(stringResource(R.string.save_error), color = MaterialTheme.colorScheme.error)
                }
                item {
                    Text(stringResource(R.string.ingredients), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                items(recipe.ingredients) { ingredient ->
                    Text(
                        "• ${ingredient.name} — ${formatAmount(ingredient.amount)} ${ingredient.unit}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                item {
                    Text(stringResource(R.string.steps), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                items(recipe.steps.size) { index ->
                    Text("${index + 1}. ${recipe.steps[index]}", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun formatAmount(amount: Double): String =
    if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()
