package com.cookassistance.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CookViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<CookViewModel>()
    private val first = Recipe(1, "Soup", listOf("Water"), listOf("Boil"))
    private val second = Recipe(2, "Salad", listOf("Lettuce"), listOf("Chop"))

    @Before fun mainDispatcher() { Dispatchers.setMain(dispatcher) }
    @After fun cleanUp() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private class FakeRecipes(var values: List<Recipe>) : Recipes {
        override val favorites = MutableStateFlow<List<Recipe>>(emptyList())
        var listFailure = false
        var saveFailure = false
        var load: suspend (Int) -> Recipe = { id -> values.first { it.id == id } }
        override suspend fun list(): List<Recipe> {
            if (listFailure) error("offline")
            return values
        }
        override suspend fun get(id: Int) = load(id)
        override suspend fun save(recipe: Recipe) {
            if (saveFailure) error("disk full")
            favorites.value = favorites.value.filterNot { it.id == recipe.id } + recipe
        }
        override suspend fun remove(id: Int) { favorites.value = favorites.value.filterNot { it.id == id } }
    }
    private class FakeAssistant : Assistant {
        val pending = CompletableDeferred<AssistReply>()
        val calls = mutableListOf<Pair<Int, String>>()
        override suspend fun ask(id: Int, question: String): AssistReply {
            calls += id to question
            return pending.await()
        }
    }
    private fun model(recipes: FakeRecipes, assistant: FakeAssistant = FakeAssistant(), saved: SavedStateHandle = SavedStateHandle()) =
        CookViewModel(recipes, assistant, saved).also(models::add)

    @Test fun catalogFailureCanBeRetriedWithoutStuckSpinner() = runTest(dispatcher) {
        val recipes = FakeRecipes(listOf(first)).apply { listFailure = true }
        val vm = model(recipes)
        runCurrent()
        assertTrue(vm.state.value.catalogError)
        assertFalse(vm.state.value.loading)
        recipes.listFailure = false
        vm.reload(); runCurrent()
        assertEquals(listOf(first), vm.state.value.catalog)
        assertFalse(vm.state.value.catalogError)
    }

    @Test fun changingRecipeCancelsPendingDetailAndClearsOldCard() = runTest(dispatcher) {
        val pending = CompletableDeferred<Recipe>()
        val recipes = FakeRecipes(listOf(first, second)).apply { load = { id -> if (id == 1) pending.await() else second } }
        val vm = model(recipes)
        vm.select(1); runCurrent()
        assertTrue(vm.state.value.detailLoading)
        vm.select(2); runCurrent()
        pending.complete(first); runCurrent()
        assertEquals(second, vm.state.value.selected)
        assertFalse(vm.state.value.detailLoading)
    }

    @Test fun failingNewDetailNeverDisplaysPreviousRecipe() = runTest(dispatcher) {
        val recipes = FakeRecipes(listOf(first))
        val vm = model(recipes)
        vm.select(1); runCurrent()
        assertEquals(first, vm.state.value.selected)
        vm.select(99); runCurrent()
        assertNull(vm.state.value.selected)
        assertTrue(vm.state.value.detailError)
    }

    @Test fun favoriteFailureIsVisibleAndNextSaveCanSucceed() = runTest(dispatcher) {
        val recipes = FakeRecipes(listOf(first)).apply { saveFailure = true }
        val vm = model(recipes)
        vm.toggle(first); runCurrent()
        assertTrue(vm.state.value.saveError)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.favorites.isEmpty())
        recipes.saveFailure = false
        vm.toggle(first); runCurrent()
        assertEquals(listOf(first), vm.state.value.favorites)
        assertFalse(vm.state.value.saveError)
        vm.toggle(first); runCurrent()
        assertTrue(vm.state.value.favorites.isEmpty())
    }

    @Test fun stoppingSessionDiscardsPendingAnswerAndDoesNotShowError() = runTest(dispatcher) {
        val assistant = FakeAssistant()
        val vm = model(FakeRecipes(listOf(first)), assistant)
        vm.startCooking(1); vm.question("First step?"); vm.ask(); runCurrent()
        assertTrue(vm.state.value.thinking)
        vm.stopCooking()
        assistant.pending.complete(AssistReply("Old answer")); runCurrent()
        assertEquals("", vm.state.value.answer)
        assertFalse(vm.state.value.thinking)
        assertFalse(vm.state.value.assistError)
    }

    @Test fun changingCookingRecipeDiscardsPreviousAnswer() = runTest(dispatcher) {
        val assistant = FakeAssistant()
        val vm = model(FakeRecipes(listOf(first, second)), assistant)
        vm.startCooking(1); vm.question("First step?"); vm.ask(); runCurrent()
        vm.startCooking(2); runCurrent()
        assistant.pending.complete(AssistReply("Boil")); runCurrent()
        assertEquals(second, vm.state.value.selected)
        assertEquals("", vm.state.value.answer)
        assertEquals(listOf(1 to "First step?"), assistant.calls)
    }

    @Test fun duplicateSubmitUsesOneRequestAndIgnoredTextIsNeverDisplayed() = runTest(dispatcher) {
        val assistant = FakeAssistant()
        val vm = model(FakeRecipes(listOf(first)), assistant)
        vm.startCooking(1); vm.question("  Weather?  "); vm.ask(); vm.ask(); runCurrent()
        assertEquals(listOf(1 to "Weather?"), assistant.calls)
        assistant.pending.complete(AssistReply("Off-topic content", ignored = true)); runCurrent()
        assertTrue(vm.state.value.ignored)
        assertEquals("", vm.state.value.answer)
        assertFalse(vm.state.value.thinking)
    }

    @Test fun questionIsBoundedAndRestoredWithoutListeningOrSubmitting() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val recipes = FakeRecipes(listOf(first))
        val vm = model(recipes, saved = saved)
        vm.question("a".repeat(700))
        assertEquals(500, vm.state.value.question.length)
        val assistant = FakeAssistant()
        val restored = model(recipes, assistant, saved); runCurrent()
        assertEquals("a".repeat(500), restored.state.value.question)
        assertFalse(restored.state.value.thinking)
        assertTrue(assistant.calls.isEmpty())
    }
}
