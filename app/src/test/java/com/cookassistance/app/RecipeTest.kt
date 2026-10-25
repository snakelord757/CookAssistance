package com.cookassistance.app

import org.junit.Assert.*
import org.junit.Test

class RecipeTest {
    @Test fun snapshotRoundTripPreservesOfflineInstructions() {
        val recipe = Recipe(7, "Soup", listOf("Salt", "Water"), listOf("Boil", "Season"))
        assertEquals(recipe, recipeJson.decodeFromString<Recipe>(recipeJson.encodeToString(recipe)))
    }
    @Test fun sourceDoesNotInventQuantities() {
        val recipe = recipeJson.decodeFromString<Recipe>("""{"id":1,"name":"Soup","ingredients":["Salt"],"instructions":["Season"],"unrelated":true}""")
        assertEquals(listOf("Salt"), recipe.ingredients)
    }
}
