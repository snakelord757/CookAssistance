package com.cookassistance.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

data class CookingState(
    val question: String = "",
    val answer: String = "",
    val thinking: Boolean = false,
    val assistError: Boolean = false,
    val ignored: Boolean = false,
    val answerSequence: Int = 0,
)

data class TimerState(
    val totalSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    val running: Boolean = false,
    val done: Boolean = false,
)

class CookViewModel(private val recipes: RecipeRepository, private val assistant: Assistant) : ViewModel() {
    private val _catalog = MutableStateFlow<CatalogUiState>(CatalogUiState.Loading)
    val catalog: StateFlow<CatalogUiState> = _catalog.asStateFlow()

    private val _detail = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    private val _cooking = MutableStateFlow(CookingState())
    val cooking: StateFlow<CookingState> = _cooking.asStateFlow()

    private val _favorites = MutableStateFlow<List<Recipe>>(emptyList())
    val favorites: StateFlow<List<Recipe>> = _favorites.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _saveError = MutableStateFlow(false)
    val saveError: StateFlow<Boolean> = _saveError.asStateFlow()

    private val _timer = MutableStateFlow(TimerState())
    val timer: StateFlow<TimerState> = _timer.asStateFlow()

    private val _selectedType = MutableStateFlow<String?>(null)
    val selectedType: StateFlow<String?> = _selectedType.asStateFlow()

    private var catalogJob: Job? = null
    private var timerJob: Job? = null
    private var detailJob: Job? = null
    private var assistJob: Job? = null
    private var selectedId: String? = null
    private var cookingId: String? = null

    init {
        loadCatalog()
        viewModelScope.launch { recipes.favorites.collect { _favorites.value = it } }
    }

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

    fun setTypeFilter(type: String?) {
        _selectedType.value = type
    }

    fun toggle(recipe: Recipe) {
        if (_saving.value) return
        val remove = _favorites.value.any { it.id == recipe.id }
        _saving.value = true
        _saveError.value = false
        viewModelScope.launch {
            try {
                if (remove) recipes.remove(recipe.id) else recipes.save(recipe)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _saveError.value = true
            } finally {
                _saving.value = false
            }
        }
    }

    fun startTimer(minutes: Int) {
        if (minutes <= 0 || minutes > 180) return
        timerJob?.cancel()
        _timer.value = TimerState(totalSeconds = minutes * 60, remainingSeconds = minutes * 60)
        runTimer()
    }

    fun pauseTimer() {
        timerJob?.cancel()
        _timer.update { it.copy(running = false) }
    }

    fun resumeTimer() {
        if (_timer.value.running || _timer.value.remainingSeconds <= 0) return
        runTimer()
    }

    fun resetTimer() {
        timerJob?.cancel()
        _timer.value = TimerState()
    }

    private fun runTimer() {
        timerJob = viewModelScope.launch {
            _timer.update { it.copy(running = true) }
            while (_timer.value.remainingSeconds > 0) {
                delay(1000)
                _timer.update { it.copy(remainingSeconds = it.remainingSeconds - 1) }
            }
            _timer.update { it.copy(running = false, done = true) }
        }
    }

    fun startCooking(id: String) {
        cookingId = id
        assistJob?.cancel()
        _cooking.value = CookingState()
    }

    fun question(text: String) {
        _cooking.update { it.copy(question = text.take(500)) }
    }

    fun ask() {
        val id = cookingId ?: return
        val question = _cooking.value.question.trim()
        if (question.isEmpty() || _cooking.value.thinking) return
        assistJob?.cancel()
        _cooking.update { it.copy(thinking = true, answer = "", ignored = false, assistError = false) }
        assistJob = viewModelScope.launch {
            try {
                val reply = assistant.ask(id, question)
                if (cookingId == id) {
                    _cooking.update {
                        it.copy(
                            thinking = false,
                            answer = if (reply.ignored) "" else reply.answer,
                            ignored = reply.ignored,
                            answerSequence = it.answerSequence + 1,
                        )
                    }
                    reply.timerMinutes?.let { startTimer(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (cookingId == id) _cooking.update { it.copy(thinking = false, assistError = true) }
            }
        }
    }

    fun stopCooking() {
        cookingId = null
        assistJob?.cancel()
        _cooking.value = CookingState()
    }
}
