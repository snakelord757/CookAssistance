package com.cookassistance.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
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
fun CollectionsScreen(collections: List<CollectionInfo>, open: (CollectionInfo) -> Unit, back: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().testTag("collections_list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TextButton(onClick = back) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.collections), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        }
        if (collections.isEmpty()) {
            item { Text(stringResource(R.string.empty)) }
        } else {
            items(collections, key = { it.id }) { collection ->
                ElevatedCard(
                    onClick = { open(collection) },
                    modifier = Modifier.fillMaxWidth().testTag("collection_${collection.id}"),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(collection.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(collection.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun CollectionDetailScreen(title: String, recipes: List<Recipe>, open: (Recipe) -> Unit, back: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().testTag("collection_detail"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TextButton(onClick = back) { Text(stringResource(R.string.back)) }
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        }
        if (recipes.isEmpty()) {
            item { Text(stringResource(R.string.empty)) }
        } else {
            items(recipes, key = { it.id }) { recipe -> RecipeCard(recipe) { open(recipe) } }
        }
    }
}
