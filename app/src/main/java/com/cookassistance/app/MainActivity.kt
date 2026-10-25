package com.cookassistance.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable data object Home : NavKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CookTheme { CookApp() } }
    }
}

@Composable private fun homeViewModel(): HomeViewModel {
    val app = LocalContext.current.applicationContext as CookApplication
    return viewModel(factory = viewModelFactory { initializer { HomeViewModel(app.recipes) } })
}

@Composable fun CookApp(vm: HomeViewModel = homeViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(Home)
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryProvider = entryProvider {
            entry<Home> { HomeScreen(state, vm::load) }
        },
    )
}

@Composable fun HomeScreen(state: HomeUiState, retry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(stringResource(R.string.tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (state) {
            HomeUiState.Loading -> CircularProgressIndicator()
            is HomeUiState.Content -> Text(stringResource(R.string.recipes_available, state.recipeCount))
            HomeUiState.Error -> {
                Text(stringResource(R.string.server_unavailable), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = retry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
