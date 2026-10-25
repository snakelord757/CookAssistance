package com.cookassistance.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CookViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeRepo(var items: List<Recipe>, var fail: Boolean = false) : RecipeRepository {
        var getFail = false
        override suspend fun list(): List<Recipe> {
            if (fail) error("offline")
            return items
        }
        override suspend fun get(id: String): Recipe {
            if (getFail) error("offline")
            return items.first { it.id == id }
        }
    }

    private fun recipe(id: String) = Recipe(
        id = id, title = "Борщ", description = "Суп", type = "soup",
        ingredients = listOf(Ingredient("Свекла", 2.0, "шт")),
        steps = listOf("Варите"), cookingTimeMin = 90, servings = 6, imageUrl = "https://x",
    )

    // catalog

    @Test fun catalogSuccessShowsRecipes() = runTest(dispatcher) {
        val vm = CookViewModel(FakeRepo(listOf(recipe("r1"), recipe("r2"))))
        runCurrent()
        val content = vm.catalog.value as CatalogUiState.Content
        assertEquals(listOf("r1", "r2"), content.recipes.map { it.id })
    }

    @Test fun catalogEmptyListShowsEmpty() = runTest(dispatcher) {
        val vm = CookViewModel(FakeRepo(emptyList()))
        runCurrent()
        assertEquals(CatalogUiState.Empty, vm.catalog.value)
    }

    @Test fun catalogFailureShowsErrorAndRetryRecovers() = runTest(dispatcher) {
        val repo = FakeRepo(listOf(recipe("r1")), fail = true)
        val vm = CookViewModel(repo)
        runCurrent()
        assertEquals(CatalogUiState.Error, vm.catalog.value)
        repo.fail = false
        vm.loadCatalog(); runCurrent()
        assertTrue(vm.catalog.value is CatalogUiState.Content)
    }

    // detail

    @Test fun selectLoadsDetail() = runTest(dispatcher) {
        val vm = CookViewModel(FakeRepo(listOf(recipe("r1"), recipe("r2"))))
        runCurrent()
        vm.select("r1"); runCurrent()
        assertEquals("r1", (vm.detail.value as DetailUiState.Content).recipe.id)
    }

    @Test fun selectFailureShowsErrorAndRetryRecovers() = runTest(dispatcher) {
        val repo = FakeRepo(listOf(recipe("r1"))).apply { getFail = true }
        val vm = CookViewModel(repo)
        runCurrent()
        vm.select("r1"); runCurrent()
        assertEquals(DetailUiState.Error, vm.detail.value)
        repo.getFail = false
        vm.select("r1"); runCurrent()
        assertTrue(vm.detail.value is DetailUiState.Content)
    }

    @Test fun switchingRecipeDiscardsStaleDetail() = runTest(dispatcher) {
        val pending = CompletableDeferred<Recipe>()
        val repo = object : RecipeRepository {
            override suspend fun list(): List<Recipe> = listOf(recipe("r1"), recipe("r2"))
            override suspend fun get(id: String): Recipe = if (id == "r1") pending.await() else recipe("r2")
        }
        val vm = CookViewModel(repo)
        runCurrent()
        vm.select("r1"); runCurrent()
        assertTrue(vm.detail.value is DetailUiState.Loading)
        vm.select("r2"); runCurrent()
        assertEquals("r2", (vm.detail.value as DetailUiState.Content).recipe.id)
        pending.complete(recipe("r1")); runCurrent()
        assertEquals("r2", (vm.detail.value as DetailUiState.Content).recipe.id)
    }

    // cooking / recognition

    @Test fun recognizedTextIsStoredAsStringAndBounded() = runTest(dispatcher) {
        val vm = CookViewModel(FakeRepo(listOf(recipe("r1"))))
        runCurrent()
        vm.startCooking()
        vm.question("Сколько варить свеклу?")
        assertEquals("Сколько варить свеклу?", vm.cooking.value.question)
        vm.question("a".repeat(700))
        assertEquals(500, vm.cooking.value.question.length)
    }

    @Test fun startAndStopCookingResetQuestion() = runTest(dispatcher) {
        val vm = CookViewModel(FakeRepo(listOf(recipe("r1"))))
        runCurrent()
        vm.startCooking()
        vm.question("Вопрос")
        assertEquals("Вопрос", vm.cooking.value.question)
        vm.stopCooking()
        assertEquals("", vm.cooking.value.question)
        vm.startCooking()
        assertEquals("", vm.cooking.value.question)
    }
}
