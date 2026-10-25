package com.cookassistance.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable data object Catalog : NavKey
@Serializable data class Detail(val id: String) : NavKey
@Serializable data class Cooking(val id: String) : NavKey
@Serializable data object Feedback : NavKey
@Serializable data object Favorites : NavKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CookTheme { CookApp() } }
    }
}

@Composable private fun cookViewModel(): CookViewModel {
    val app = LocalContext.current.applicationContext as CookApplication
    return viewModel(factory = viewModelFactory { initializer { CookViewModel(app.recipes, app.assistant) } })
}

@Composable private fun feedbackViewModel(): FeedbackViewModel {
    val app = LocalContext.current.applicationContext as CookApplication
    return viewModel(factory = viewModelFactory { initializer { FeedbackViewModel(app.feedback) } })
}

@Composable fun CookApp(vm: CookViewModel = cookViewModel(), fbVm: FeedbackViewModel = feedbackViewModel()) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val cooking by vm.cooking.collectAsStateWithLifecycle()
    val timer by vm.timer.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val saveError by vm.saveError.collectAsStateWithLifecycle()
    val feedback by fbVm.state.collectAsStateWithLifecycle()
    val stack = rememberNavBackStack(Catalog)
    val navigation = remember(stack) { CookNavigation(stack) }
    val revision = navigation.revision
    fun back(expected: NavKey) { navigation.back(expected, revision) }
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        NavDisplay(
            backStack = stack,
            onBack = { back(stack.last()) },
            entryProvider = entryProvider {
                entry<Catalog> {
                    CatalogScreen(
                        catalog,
                        vm::loadCatalog,
                        { navigation.open(Catalog, revision, Detail(it.id)) },
                        { navigation.open(Catalog, revision, Feedback) },
                        { navigation.open(Catalog, revision, Favorites) },
                    )
                }
                entry<Detail> { key ->
                    LaunchedEffect(key.id) { vm.select(key.id) }
                    DetailScreen(
                        detail, favorites, saving, saveError,
                        { vm.select(key.id) },
                        { back(key) },
                        { navigation.open(key, revision, Cooking(key.id)) },
                        vm::toggle,
                    )
                }
                entry<Cooking> { key ->
                    LaunchedEffect(key.id) { vm.startCooking(key.id) }
                    CookingScreen(
                        detail, cooking, timer,
                        vm::question, vm::ask,
                        vm::startTimer, vm::pauseTimer, vm::resumeTimer, vm::resetTimer,
                        { back(key) },
                    )
                }
                entry<Feedback> {
                    FeedbackScreen(feedback, fbVm::message, fbVm::contact, fbVm::submit, { back(Feedback) })
                }
                entry<Favorites> {
                    FavoritesScreen(favorites, { navigation.open(Favorites, revision, Detail(it.id)) }, { back(Favorites) })
                }
            },
        )
    }
}
