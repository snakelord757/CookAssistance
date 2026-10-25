package com.cookassistance.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import coil3.compose.AsyncImage
import kotlinx.serialization.Serializable

@Serializable data object Browse : NavKey
@Serializable data class Detail(val id: Int) : NavKey
@Serializable data class Cooking(val id: Int) : NavKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFFA9D8B6)) else lightColorScheme(
                primary = Color(0xFF286344), secondary = Color(0xFF82572D), background = Color(0xFFFAF9F5), surface = Color(0xFFFAF9F5))
            MaterialTheme(colorScheme = scheme) { CookApp() }
        }
    }
}

@Composable private fun cookViewModel(): CookViewModel {
    val app = LocalContext.current.applicationContext as CookApplication
    return viewModel(factory = viewModelFactory { initializer { CookViewModel(app.recipes, app.assistant, createSavedStateHandle()) } })
}

@Composable fun CookApp(vm: CookViewModel = cookViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stack = rememberNavBackStack(Browse)
    val navigation = remember(stack) { CookNavigation(stack) }
    val revision = navigation.revision
    var favorites by rememberSaveable { mutableStateOf(false) }
    fun tab(value: Boolean) { vm.stopCooking(); favorites = value; navigation.root() }
    fun back(expected: NavKey) { if (navigation.back(expected, revision)) vm.stopCooking() }
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        NavigationSuiteScaffold(navigationSuiteItems = {
            item(selected = !favorites, onClick = { tab(false) }, icon = { Text("◉") }, label = { Text(stringResource(R.string.catalog)) })
            item(selected = favorites, onClick = { tab(true) }, icon = { Text("♡") }, label = { Text(stringResource(R.string.favorites)) })
        }) {
            NavDisplay(backStack = stack, onBack = { back(stack.last()) },
                entryProvider = entryProvider {
                    entry<Browse> { Catalog(state, favorites, vm::reload, { navigation.open(Browse, revision, Detail(it.id)) }) }
                    entry<Detail> { key ->
                        LaunchedEffect(key.id) { vm.select(key.id) }
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            if (maxWidth >= 840.dp) Row {
                                Box(Modifier.weight(0.42f)) { Catalog(state, favorites, vm::reload, { navigation.open(key, revision, Detail(it.id), replace = true) }) }
                                VerticalDivider()
                                Box(Modifier.weight(0.58f)) { RecipeDetail(state, { back(key) }, { vm.select(key.id) }, vm::toggle, { navigation.open(key, revision, Cooking(key.id)) }) }
                            } else RecipeDetail(state, { back(key) }, { vm.select(key.id) }, vm::toggle, { navigation.open(key, revision, Cooking(key.id)) })
                        }
                    }
                    entry<Cooking> { key -> CookingScreen(key.id, state, vm, { back(key) }) }
                })
        }
    }
}

@Composable fun Catalog(state: CookState, favorites: Boolean, retry: () -> Unit, open: (Recipe) -> Unit) {
    val recipes = if (favorites) state.favorites else state.catalog
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().testTag("recipe_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(stringResource(if (favorites) R.string.favorites else R.string.tagline), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(if (favorites) R.string.favorites_empty else R.string.catalog_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!favorites && state.loading) item { CircularProgressIndicator(); Text(stringResource(R.string.loading)) }
        if (!favorites && state.catalogError) item { ErrorMessage(R.string.load_error, retry) }
        if (recipes.isEmpty() && (favorites || (!state.loading && !state.catalogError))) item { Text(stringResource(R.string.empty)) }
        items(recipes, key = { it.id }) { recipe ->
            ElevatedCard(onClick = { open(recipe) }, modifier = Modifier.fillMaxWidth().testTag("recipe_${recipe.id}"), shape = RoundedCornerShape(24.dp)) {
                AsyncImage(recipe.image, contentDescription = null, modifier = Modifier.fillMaxWidth().height(170.dp), contentScale = ContentScale.Crop)
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(recipe.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.minutes, recipe.prepTimeMinutes + recipe.cookTimeMinutes))
                        if (state.favorites.any { it.id == recipe.id }) Text("♥", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item { Text(stringResource(R.string.source), style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable fun ErrorMessage(message: Int, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(message), color = MaterialTheme.colorScheme.error); OutlinedButton(retry) { Text(stringResource(R.string.retry)) } }
}

@Composable fun RecipeDetail(state: CookState, back: () -> Unit, retry: () -> Unit, toggle: (Recipe) -> Unit, cook: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().testTag("recipe_detail"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { TextButton(back) { Text(stringResource(R.string.back)) } }
        if (state.detailLoading) item { CircularProgressIndicator() }
        if (state.detailError) item { ErrorMessage(R.string.detail_error, retry) }
        state.selected?.let { recipe ->
            item {
                AsyncImage(recipe.image, null, Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(16.dp))
                Text(recipe.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.source), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.minutes, recipe.prepTimeMinutes + recipe.cookTimeMinutes))
                Text(stringResource(R.string.servings, recipe.servings))
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(cook, Modifier.fillMaxWidth().testTag("cook_button")) { Text(stringResource(R.string.cook)) }
                    OutlinedButton({ toggle(recipe) }, Modifier.fillMaxWidth().testTag("favorite_button"), enabled = !state.saving) {
                        Text(stringResource(if (state.favorites.any { it.id == recipe.id }) R.string.saved else R.string.save))
                    }
                    if (state.saveError) Text(stringResource(R.string.save_error), color = MaterialTheme.colorScheme.error)
                }
            }
            item { Text(stringResource(R.string.ingredients), style = MaterialTheme.typography.titleLarge); Text(stringResource(R.string.amount_unknown), style = MaterialTheme.typography.bodySmall) }
            items(recipe.ingredients) { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
            item { Text(stringResource(R.string.steps), style = MaterialTheme.typography.titleLarge) }
            items(recipe.instructions.size) { index -> Text("${index + 1}. ${recipe.instructions[index]}", style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
