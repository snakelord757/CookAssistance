package com.cookassistance.app

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun CatalogScreen(
    state: CatalogUiState,
    selectedType: String?,
    dishOfTheDay: Recipe?,
    onTypeFilter: (String?) -> Unit,
    retry: () -> Unit,
    open: (Recipe) -> Unit,
    openFeedback: () -> Unit,
    openFavorites: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().testTag("recipe_list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.catalog_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = openFavorites, modifier = Modifier.testTag("favorites_button")) { Text(stringResource(R.string.favorites)) }
                TextButton(onClick = openFeedback, modifier = Modifier.testTag("feedback_button")) { Text(stringResource(R.string.feedback)) }
            }
        }
        dishOfTheDay?.let { dish ->
            item {
                ElevatedCard(onClick = { open(dish) }, modifier = Modifier.fillMaxWidth().testTag("dish_of_the_day")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.dish_of_the_day), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(dish.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(typeLabel(dish.type), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state is CatalogUiState.Content && state.recipes.isNotEmpty()) {
            item {
                val types = state.recipes.map { it.type }.distinct()
                Row(Modifier.horizontalScroll(rememberScrollState()).testTag("type_filter"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedType == null,
                        onClick = { onTypeFilter(null) },
                        label = { Text(stringResource(R.string.all)) },
                    )
                    types.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { onTypeFilter(type) },
                            label = { Text(typeLabel(type)) },
                        )
                    }
                }
            }
        }
        when (state) {
            CatalogUiState.Loading -> item {
                CircularProgressIndicator()
                Text(stringResource(R.string.loading))
            }
            CatalogUiState.Error -> item { ErrorMessage(R.string.load_error, retry) }
            CatalogUiState.Empty -> item { Text(stringResource(R.string.empty)) }
            is CatalogUiState.Content -> {
                val filtered = if (selectedType == null) state.recipes else state.recipes.filter { it.type == selectedType }
                if (filtered.isEmpty()) {
                    item { Text(stringResource(R.string.empty)) }
                } else {
                    items(filtered, key = { it.id }) { recipe -> RecipeCard(recipe) { open(recipe) } }
                }
            }
        }
    }
}

@Composable
fun RecipeCard(recipe: Recipe, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("recipe_${recipe.id}"),
        shape = RoundedCornerShape(24.dp),
    ) {
        AsyncImage(
            model = recipe.imageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(170.dp),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(recipe.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(typeLabel(recipe.type), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.minutes, recipe.cookingTimeMin), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ErrorMessage(message: Int, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = retry) { Text(stringResource(R.string.retry)) }
    }
}

private val typeLabelRes = mapOf(
    "soup" to R.string.type_soup,
    "salad" to R.string.type_salad,
    "main" to R.string.type_main,
    "side" to R.string.type_side,
    "dessert" to R.string.type_dessert,
    "drink" to R.string.type_drink,
    "baking" to R.string.type_baking,
    "breakfast" to R.string.type_breakfast,
)

@Composable
fun typeLabel(type: String): String {
    val res = typeLabelRes[type] ?: return type
    return stringResource(res)
}
