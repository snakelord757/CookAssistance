package com.cookassistance.app

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
class CatalogViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeRepo(var items: List<Recipe>, var fail: Boolean = false) : RecipeRepository {
        override suspend fun list(): List<Recipe> {
            if (fail) error("offline")
            return items
        }
        override suspend fun get(id: String): Recipe = items.first { it.id == id }
    }

    private fun recipe(id: String) = Recipe(
        id = id, title = "Борщ", description = "Суп", type = "soup",
        ingredients = listOf(Ingredient("Свекла", 2.0, "шт")),
        steps = listOf("Варите"), cookingTimeMin = 90, servings = 6, imageUrl = "https://x",
    )

    @Test fun successShowsRecipes() = runTest(dispatcher) {
        val vm = CatalogViewModel(FakeRepo(listOf(recipe("r1"), recipe("r2"))))
        runCurrent()
        val content = vm.state.value as CatalogUiState.Content
        assertEquals(listOf("r1", "r2"), content.recipes.map { it.id })
    }

    @Test fun emptyListShowsEmpty() = runTest(dispatcher) {
        val vm = CatalogViewModel(FakeRepo(emptyList()))
        runCurrent()
        assertEquals(CatalogUiState.Empty, vm.state.value)
    }

    @Test fun failureShowsErrorAndRetryRecovers() = runTest(dispatcher) {
        val repo = FakeRepo(listOf(recipe("r1")), fail = true)
        val vm = CatalogViewModel(repo)
        runCurrent()
        assertEquals(CatalogUiState.Error, vm.state.value)
        repo.fail = false
        vm.load(); runCurrent()
        assertTrue(vm.state.value is CatalogUiState.Content)
    }
}
