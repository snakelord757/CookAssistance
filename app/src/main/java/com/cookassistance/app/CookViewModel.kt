package com.cookassistance.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class CookState(
    val catalog: List<Recipe> = emptyList(), val favorites: List<Recipe> = emptyList(),
    val loading: Boolean = false, val catalogError: Boolean = false,
    val selected: Recipe? = null, val detailLoading: Boolean = false, val detailError: Boolean = false,
    val saveError: Boolean = false, val saving: Boolean = false,
    val question: String = "", val answer: String = "", val ignored: Boolean = false,
    val thinking: Boolean = false, val assistError: Boolean = false, val answerSequence: Int = 0
)
class CookViewModel(private val recipes: Recipes, private val assistant: Assistant, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(CookState(question = saved["question"] ?: ""))
    val state = mutable.asStateFlow()
    private var detailJob: Job? = null
    private var assistJob: Job? = null
    private var catalogJob: Job? = null
    private var selectedId: Int? = null
    private var cookingId: Int? = null
    private var cookingSession = 0L
    init {
        viewModelScope.launch { recipes.favorites.collect { favorites -> mutable.update { it.copy(favorites = favorites) } } }
        reload()
    }
    fun reload() {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, catalogError = false) }
            try { val values = recipes.list(); mutable.update { it.copy(catalog = values, loading = false) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(loading = false, catalogError = true) } }
        }
    }
    fun select(id: Int) {
        if (selectedId == id && state.value.selected?.id == id) return
        selectedId = id
        detailJob?.cancel()
        stopCooking()
        mutable.update { it.copy(selected = null, detailLoading = true, detailError = false, saveError = false) }
        detailJob = viewModelScope.launch {
            try {
                val value = recipes.get(id)
                if (selectedId == id) mutable.update { it.copy(selected = value, detailLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (selectedId == id) mutable.update { it.copy(detailLoading = false, detailError = true) } }
        }
    }
    fun toggle(recipe: Recipe) {
        if (state.value.saving) return
        val remove = state.value.favorites.any { it.id == recipe.id }
        mutable.update { it.copy(saving = true, saveError = false) }
        viewModelScope.launch {
            try { if (remove) recipes.remove(recipe.id) else recipes.save(recipe) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(saveError = true) } }
            finally { mutable.update { it.copy(saving = false) } }
        }
    }
    fun startCooking(id: Int): Long {
        if (cookingId == id) return ++cookingSession
        select(id)
        cookingId = id
        cookingSession++
        mutable.update { it.copy(answer = "", assistError = false, ignored = false) }
        return cookingSession
    }
    fun question(value: String) {
        val bounded = value.take(500)
        saved["question"] = bounded
        mutable.update { it.copy(question = bounded) }
    }
    fun ask() {
        val id = cookingId ?: return
        val question = state.value.question.trim()
        if (question.isEmpty() || state.value.thinking) return
        assistJob?.cancel()
        mutable.update { it.copy(thinking = true, answer = "", ignored = false, assistError = false) }
        assistJob = viewModelScope.launch {
            try {
                val reply = assistant.ask(id, question)
                if (cookingId == id) mutable.update { it.copy(thinking = false, answer = if (reply.ignored) "" else reply.answer,
                    ignored = reply.ignored, answerSequence = it.answerSequence + 1) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (cookingId == id) mutable.update { it.copy(thinking = false, assistError = true) } }
        }
    }
    fun stopCooking() {
        cookingId = null; assistJob?.cancel()
        mutable.update { it.copy(thinking = false, answer = "", ignored = false, assistError = false) }
    }
    fun stopCooking(session: Long) {
        if (cookingId != null && cookingSession == session) stopCooking()
    }
    fun cancelRequest() { assistJob?.cancel(); mutable.update { it.copy(thinking = false) } }
}
