package com.cookassistance.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test

class NavigationRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val recipe = Recipe(2, "Navigation soup", listOf("Water"), listOf("Boil"))
    private val recipes = object : Recipes {
        override val favorites = MutableStateFlow<List<Recipe>>(emptyList())
        override suspend fun list() = listOf(recipe)
        override suspend fun get(id: Int) = recipe
        override suspend fun save(recipe: Recipe) { favorites.value = listOf(recipe) }
        override suspend fun remove(id: Int) { favorites.value = emptyList() }
    }
    private val vm = CookViewModel(recipes, object : Assistant {
        override suspend fun ask(id: Int, question: String) = AssistReply(ignored = true)
    }, SavedStateHandle())

    @After fun cleanUp() { vm.viewModelScope.cancel() }

    @Test fun retainedDetailBackDuringTabResetKeepsRootUsable() {
        compose.setContent { MaterialTheme { CookApp(vm) } }
        compose.waitUntil { compose.onAllNodesWithTag("recipe_2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recipe_2").performClick()
        val oldBack = compose.onNodeWithText("Назад").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val tab = compose.onNodeWithText("Рецепты").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // Dispatch before the next frame; invoking a detached clickable wrapper is not valid input.
        compose.runOnIdle { tab(); oldBack() }
        compose.onNodeWithTag("recipe_list").assertIsDisplayed()
        compose.onNodeWithTag("recipe_2").performClick()
        compose.onNodeWithTag("recipe_detail").assertIsDisplayed()
        compose.onNodeWithText("Назад").performClick()
        compose.onNodeWithTag("recipe_list").assertIsDisplayed()
    }

    @Test fun restoredDetailRetainsRecipeAndCanReturnToRoot() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { CookApp(vm) } }
        compose.waitUntil { compose.onAllNodesWithTag("recipe_2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recipe_2").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("recipe_detail").assertIsDisplayed()
        compose.onNode(hasText("Navigation soup") and hasAnyAncestor(hasTestTag("recipe_detail"))).assertExists()
        compose.onNodeWithText("Назад").performClick()
        compose.onNodeWithTag("recipe_list").assertIsDisplayed()
    }

    @Test fun repeatedOutgoingBackCannotRemoveRootFromRealHost() {
        compose.setContent { MaterialTheme { CookApp(vm) } }
        compose.waitUntil { compose.onAllNodesWithTag("recipe_2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recipe_2").performClick()
        compose.onNodeWithTag("recipe_detail").assertExists()
        val back = compose.onNodeWithText("Назад").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // Hardware/key events may dispatch a retained outgoing entry action again before a frame.
        compose.runOnIdle { back(); back() }
        compose.waitForIdle()
        compose.onNodeWithTag("recipe_list").assertIsDisplayed()
        compose.onNodeWithTag("recipe_2").performClick()
        compose.onNodeWithTag("recipe_detail").assertExists()
    }
}
