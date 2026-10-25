package com.cookassistance.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CatalogUiState {
    data object Loading : CatalogUiState
    data class Content(val recipes: List<Recipe>) : CatalogUiState
    data object Empty : CatalogUiState
    data object Error : CatalogUiState
}

class CatalogViewModel(private val recipes: RecipeRepository) : ViewModel() {
    private val _state = MutableStateFlow<CatalogUiState>(CatalogUiState.Loading)
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = CatalogUiState.Loading
            _state.value = try {
                val list = recipes.list()
                if (list.isEmpty()) CatalogUiState.Empty else CatalogUiState.Content(list)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CatalogUiState.Error
            }
        }
    }
}
