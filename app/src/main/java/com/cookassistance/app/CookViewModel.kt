package com.cookassistance.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Content(val recipe: Recipe) : DetailUiState
    data object Error : DetailUiState
}

class CookViewModel(private val recipes: RecipeRepository) : ViewModel() {
    private val _catalog = MutableStateFlow<CatalogUiState>(CatalogUiState.Loading)
    val catalog: StateFlow<CatalogUiState> = _catalog.asStateFlow()

    private val _detail = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    private var catalogJob: Job? = null
    private var detailJob: Job? = null
    private var selectedId: String? = null

    init { loadCatalog() }

    fun loadCatalog() {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            _catalog.value = CatalogUiState.Loading
            _catalog.value = try {
                val list = recipes.list()
                if (list.isEmpty()) CatalogUiState.Empty else CatalogUiState.Content(list)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CatalogUiState.Error
            }
        }
    }

    fun select(id: String) {
        if (selectedId == id && _detail.value is DetailUiState.Content) return
        selectedId = id
        detailJob?.cancel()
        _detail.value = DetailUiState.Loading
        detailJob = viewModelScope.launch {
            try {
                val recipe = recipes.get(id)
                if (selectedId == id) _detail.value = DetailUiState.Content(recipe)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (selectedId == id) _detail.value = DetailUiState.Error
            }
        }
    }
}
