package com.cookassistance.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecipeDetailUiTest {
    @get:Rule val compose = createComposeRule()
    private val recipe = Recipe(7, "Vegetable soup with a long descriptive name", listOf("Water", "Carrot"), listOf("Wash and chop the carrot.", "Boil until tender."))

    @Test fun detailActionsUseSelectedRecipeAndSavedState() {
        var toggled: Recipe? = null
        var cookCount = 0
        compose.setContent {
            MaterialTheme {
                RecipeDetail(CookState(selected = recipe, favorites = listOf(recipe)), {}, {}, { toggled = it }, { cookCount++ })
            }
        }
        compose.onNodeWithTag("favorite_button").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Убрать из избранного").assertExists()
        assertEquals(recipe, toggled)
        compose.onNodeWithTag("cook_button").performScrollTo().performClick()
        assertEquals(1, cookCount)
    }

    @Test fun narrowLargeFontDetailKeepsIngredientsAndLastStepReachable() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        RecipeDetail(CookState(selected = recipe), {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("cook_button").performScrollTo().assertIsDisplayed()
        // Lazy prefetch may expose an unplaced semantics node; scroll through the list.
        compose.onNodeWithTag("recipe_detail").performScrollToNode(hasText("В источнике количество ингредиентов не указано."))
        compose.onNodeWithText("В источнике количество ингредиентов не указано.").assertIsDisplayed()
        compose.onNodeWithTag("recipe_detail").performScrollToNode(hasText("2. Boil until tender."))
        compose.onNodeWithText("2. Boil until tender.").assertIsDisplayed()
    }
}
