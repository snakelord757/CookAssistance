package com.cookassistance.server

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecisionTest {
    @Test fun unrelatedQuestionReturnsIgnored() {
        assertEquals(AssistReply(ignored = true), validatedDecision("""{"related":false,"answer":""}"""))
    }

    @Test fun relatedQuestionReturnsAnswer() {
        assertEquals(AssistReply(answer = "Варите 10 минут"), validatedDecision("""{"related":true,"answer":"Варите 10 минут"}"""))
    }

    @Test fun invalidJsonThrowsInvalidUpstream() {
        assertFailsWith<InvalidUpstream> { validatedDecision("not-json") }
    }

    @Test fun blankAnswerThrowsInvalidUpstream() {
        assertFailsWith<InvalidUpstream> { validatedDecision("""{"related":true,"answer":""}""") }
    }

    @Test fun timerMinutesIsValidated() {
        assertEquals(10, validatedDecision("""{"related":true,"answer":"Таймер","timerMinutes":10}""").timerMinutes)
        assertFailsWith<InvalidUpstream> { validatedDecision("""{"related":true,"answer":"Таймер","timerMinutes":0}""") }
        assertFailsWith<InvalidUpstream> { validatedDecision("""{"related":true,"answer":"Таймер","timerMinutes":999}""") }
    }
}

class AssistantRouteTest {
    private class FakeService(private val behavior: suspend (AssistRequest) -> AssistReply) : AssistantService {
        override suspend fun answer(question: AssistRequest): AssistReply = behavior(question)
    }

    @Test fun validQuestionReturnsAnswer() = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            assistantModule(FakeService { AssistReply(answer = "Кипятите 5 минут") })
        }
        val response = client.post("/assist") {
            contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"r-borsch","question":"Сколько варить?"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val reply = json.decodeFromString<AssistReply>(response.bodyAsText())
        assertEquals("Кипятите 5 минут", reply.answer)
    }

    @Test fun blankQuestionReturnsBadRequest() = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            assistantModule(FakeService { AssistReply() })
        }
        val response = client.post("/assist") {
            contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"r-borsch","question":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test fun missingConfigurationReturns503() = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            assistantModule(FakeService { throw MissingConfiguration() })
        }
        val response = client.post("/assist") {
            contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"r-borsch","question":"Как варить?"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test fun unknownRecipeReturns404() = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            assistantModule(FakeService { throw UnknownRecipe() })
        }
        val response = client.post("/assist") {
            contentType(ContentType.Application.Json)
            setBody("""{"recipeId":"nope","question":"Как варить?"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
