package com.cookassistance.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeRepositoryTest {
    private val sample = """{"id":"r-borsch","title":"Борщ","description":"Классический борщ","type":"soup",
        "ingredients":[{"name":"Свекла","amount":2.0,"unit":"шт"}],"steps":["Варите"],
        "cookingTimeMin":90,"servings":6,"imageUrl":"https://x","collections":[]}""".replace(Regex("\\s+"), "")

    private val engine = MockEngine { request ->
        when (request.url.encodedPath) {
            "/recipes" -> respond("[$sample]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            "/recipes/r-borsch" -> respond(sample, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else -> respond("""{"error":"recipe_not_found"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }
    private val client = HttpClient(engine) { install(ContentNegotiation) { json(recipeJson) } }
    private val repo = NetworkRecipeRepository(client, "http://localhost:8080")

    @Test fun listParsesRecipesFromJson() = runTest {
        val list = repo.list()
        assertEquals(1, list.size)
        assertEquals("r-borsch", list[0].id)
        assertEquals("soup", list[0].type)
        assertEquals("Борщ", list[0].title)
    }

    @Test fun getParsesSingleRecipeFromJson() = runTest {
        val recipe = repo.get("r-borsch")
        assertEquals("Борщ", recipe.title)
        assertEquals(2.0, recipe.ingredients.first().amount, 0.0)
        assertEquals("шт", recipe.ingredients.first().unit)
    }
}
