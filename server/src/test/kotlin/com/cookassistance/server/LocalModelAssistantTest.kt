package com.cookassistance.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeTextTest {
    @Test fun formatsRecipeInTrainingFormat() {
        val recipe = Recipe(
            id = "r1", title = "Овсяная каша", description = "", type = "breakfast",
            ingredients = listOf(
                Ingredient("овсяные хлопья", 100.0, "г"),
                Ingredient("молоко", 400.0, "мл"),
                Ingredient("соль", 0.5, "ч.л."),
            ),
            steps = listOf("Нагрейте молоко.", "Добавьте хлопья."),
            cookingTimeMin = 15, servings = 2, imageUrl = "", collections = emptyList(),
        )
        assertEquals(
            "Овсяная каша. Порций: 2.\n" +
                "Ингредиенты: овсяные хлопья — 100 г; молоко — 400 мл; соль — 0,5 ч.л..\n" +
                "1. Нагрейте молоко.\n" +
                "2. Добавьте хлопья.",
            recipeText(recipe),
        )
    }

    @Test fun formatsWholeUnitsWithoutDecimals() {
        assertEquals("2", num(2.0))
        assertEquals("0,5", num(0.5))
        assertEquals("1,5", num(1.5))
        assertEquals("400", num(400.0))
    }
}

class LocalModelAssistantTest {
    private fun store() = RecipeStore(
        listOf(
            Recipe(
                id = "r1", title = "Овсяная каша", description = "", type = "breakfast",
                ingredients = listOf(Ingredient("овсяные хлопья", 100.0, "г")),
                steps = listOf("Нагрейте молоко."),
                cookingTimeMin = 15, servings = 2, imageUrl = "", collections = emptyList(),
            ),
        ),
        emptyList(),
        "r1",
    )

    @Test fun forwardsRecipeAndQuestionAndMapsAnswer() = runBlocking {
        var capturedUrl = ""
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedBody = (request.body as TextContent).text
            respond(
                content = """{"answer":"Варите 5 минут","truncated":false}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val service = LocalModelAssistant(client, "http://127.0.0.1:8000", store())

        val reply = service.answer(AssistRequest("r1", "Сколько варить?"))

        assertEquals("Варите 5 минут", reply.answer)
        assertNull(reply.timerMinutes)
        assertEquals(false, reply.ignored)
        assertEquals("http://127.0.0.1:8000/answer", capturedUrl)
        assertTrue(capturedBody.contains("Овсяная каша. Порций: 2."))
        assertTrue(capturedBody.contains("Сколько варить?"))
    }

    @Test fun blankAnswerThrowsInvalidUpstream() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"answer":"  "}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val service = LocalModelAssistant(client, "http://127.0.0.1:8000", store())

        val thrown = runCatching { service.answer(AssistRequest("r1", "Как варить?")) }.exceptionOrNull()
        assertTrue(thrown is InvalidUpstream)
    }
}

class BackendSelectionTest {
    @Test fun selectsLocalBackend() {
        val http = HttpClient(MockEngine { error("unused") })
        val service = selectAssistant("local", http, "key", "model", "http://127.0.0.1:8000", RecipeStore(emptyList(), emptyList(), "x"))
        assertTrue(service is LocalModelAssistant)
    }

    @Test fun defaultsToDeepSeek() {
        val http = HttpClient(MockEngine { error("unused") })
        val service = selectAssistant(null, http, "key", "model", "http://127.0.0.1:8000", RecipeStore(emptyList(), emptyList(), "x"))
        assertTrue(service is DeepSeekAssistant)
    }
}
