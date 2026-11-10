package com.cookassistance.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipeRouteTest {
    private val store = RecipeStore(recipes, collections, dishOfTheDayId)
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun listReturns10to15RecipesWithAllMandatoryFields() = testApplication {
        application { recipeModule(store) }
        val response = client.get("/recipes")
        assertEquals(HttpStatusCode.OK, response.status)
        val list = json.decodeFromString<List<Recipe>>(response.bodyAsText())
        assertTrue(list.size in 10..15, "expected 10..15 recipes, got ${list.size}")
        for (recipe in list) {
            assertTrue(recipe.id.isNotBlank(), "id blank in $recipe")
            assertTrue(recipe.title.isNotBlank(), "title blank in ${recipe.id}")
            assertTrue(recipe.description.isNotBlank(), "description blank in ${recipe.id}")
            assertTrue(recipe.type.isNotBlank(), "type blank in ${recipe.id}")
            assertTrue(recipe.ingredients.isNotEmpty(), "ingredients empty in ${recipe.id}")
            assertTrue(recipe.steps.isNotEmpty(), "steps empty in ${recipe.id}")
            assertTrue(recipe.cookingTimeMin > 0, "time invalid in ${recipe.id}")
            assertTrue(recipe.servings > 0, "servings invalid in ${recipe.id}")
            assertTrue(recipe.imageUrl.isNotBlank(), "imageUrl blank in ${recipe.id}")
            for (ingredient in recipe.ingredients) {
                assertTrue(ingredient.name.isNotBlank(), "ingredient name blank in ${recipe.id}")
                assertTrue(ingredient.unit.isNotBlank(), "ingredient unit blank in ${recipe.id}")
            }
        }
    }

    @Test
    fun typeIsPopulatedAndCoversAtLeastFour() = testApplication {
        application { recipeModule(store) }
        val list = json.decodeFromString<List<Recipe>>(client.get("/recipes").bodyAsText())
        assertTrue(list.isNotEmpty())
        val types = list.map { it.type }.distinct()
        assertTrue(types.size >= 4, "expected >=4 distinct types, got $types")
    }

    @Test
    fun detailReturnsFullRecipeAndUnknownIs404() = testApplication {
        application { recipeModule(store) }
        val ok = client.get("/recipes/r-borsch")
        assertEquals(HttpStatusCode.OK, ok.status)
        val recipe = json.decodeFromString<Recipe>(ok.bodyAsText())
        assertEquals("r-borsch", recipe.id)
        assertEquals("soup", recipe.type)
        assertEquals("Борщ", recipe.title)

        val missing = client.get("/recipes/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun dishOfTheDayReturnsExactlyOneRecipe() = testApplication {
        application { recipeModule(store) }
        val response = client.get("/recipes/dish-of-the-day")
        assertEquals(HttpStatusCode.OK, response.status)
        val recipe = json.decodeFromString<Recipe>(response.bodyAsText())
        assertEquals(dishOfTheDayId, recipe.id)
    }

    @Test
    fun collectionsHaveAtLeastTwoWithAtLeastTwoRecipesEach() = testApplication {
        application { recipeModule(store) }
        val response = client.get("/collections")
        assertEquals(HttpStatusCode.OK, response.status)
        val collections = json.decodeFromString<List<CollectionInfo>>(response.bodyAsText())
        assertTrue(collections.size >= 2, "expected >=2 collections, got ${collections.size}")
        for (collection in collections) {
            assertTrue(collection.id.isNotBlank() && collection.title.isNotBlank())
            val recipesResponse = client.get("/collections/${collection.id}/recipes")
            assertEquals(HttpStatusCode.OK, recipesResponse.status)
            val collectionRecipes = json.decodeFromString<List<Recipe>>(recipesResponse.bodyAsText())
            assertTrue(collectionRecipes.size >= 2, "collection ${collection.id} has ${collectionRecipes.size} recipes")
            assertTrue(collectionRecipes.all { collection.id in it.collections })
        }
    }

    @Test
    fun typeFilterReturnsOnlyRequestedType() = testApplication {
        application { recipeModule(store) }
        val response = client.get("/recipes?type=soup")
        assertEquals(HttpStatusCode.OK, response.status)
        val list = json.decodeFromString<List<Recipe>>(response.bodyAsText())
        assertTrue(list.isNotEmpty(), "expected soup recipes")
        assertTrue(list.all { it.type == "soup" })
    }

    @Test
    fun feedbackAcceptsValidAndRejectsBlank() = testApplication {
        application { recipeModule(store) }
        val ok = client.post("/feedback") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"Отличное приложение!","contact":"user@example.com"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)

        val blank = client.post("/feedback") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)

        val invalid = client.post("/feedback") {
            contentType(ContentType.Application.Json)
            setBody("not-json")
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
    }
}
