package com.cookassistance.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CookTheme { CookApp() } }
    }
}

@Composable private fun catalogViewModel(): CatalogViewModel {
    val app = LocalContext.current.applicationContext as CookApplication
    return viewModel(factory = viewModelFactory { initializer { CatalogViewModel(app.recipes) } })
}

@Composable fun CookApp(vm: CatalogViewModel = catalogViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stack = rememberNavBackStack(Catalog)
    val navigation = remember(stack) { CookNavigation(stack) }
    val revision = navigation.revision
    fun back(expected: NavKey) { navigation.back(expected, revision) }
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        NavDisplay(
            backStack = stack,
            onBack = { back(stack.last()) },
            entryProvider = entryProvider {
                entry<Catalog> { CatalogScreen(state, vm::load, { navigation.open(Catalog, revision, Detail(it.id)) }) }
                entry<Detail> { key -> DetailScreen(key.id) { back(key) } }
            },
        )
    }
}
