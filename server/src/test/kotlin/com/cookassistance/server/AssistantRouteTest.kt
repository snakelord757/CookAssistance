package com.cookassistance.server

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AssistantRouteTest {
    private fun service(block: suspend (Question) -> Reply) = object : AssistantService {
        override suspend fun answer(question: Question) = block(question)
    }
    private suspend fun HttpClient.ask(body: String) = post("/assist") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test fun missingCredentialIsExplicit503WithoutLeakingCause() = testApplication {
        application { assistantModule(service { throw MissingConfiguration() }) }
        val response = client.ask("""{"recipeId":1,"question":"First step?"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("assistant_not_configured", json.decodeFromString<ErrorReply>(response.bodyAsText()).error)
    }

    @Test fun realClientRejectsMissingKeyBeforeAnyNetworkCall() = runBlocking {
        val http = HttpClient()
        try {
            assertFailsWith<MissingConfiguration> { DeepSeekAssistant(http, "", "deepseek-flash").answer(Question(1, "First step?")) }
        } finally { http.close() }
        Unit
    }

    @Test fun invalidQuestionNeverCallsAssistant() = testApplication {
        var calls = 0
        application { assistantModule(service { calls++; Reply("unexpected") }) }
        listOf(
            """{"recipeId":0,"question":"Step?"}""",
            """{"recipeId":1,"question":"   "}""",
            json.encodeToString(Question(1, "a".repeat(501))),
            "not json"
        ).forEach { assertEquals(HttpStatusCode.BadRequest, client.ask(it).status) }
        assertEquals(0, calls)
    }

    @Test fun oversizedBodyIsRejectedBeforeAssistant() = testApplication {
        var calls = 0
        application { assistantModule(service { calls++; Reply("unexpected") }) }
        assertEquals(HttpStatusCode.PayloadTooLarge, client.ask("x".repeat(4097)).status)
        assertEquals(0, calls)
    }

    @Test fun unknownRecipeAndUpstreamFailureHaveDistinctSafeErrors() = testApplication {
        application { assistantModule(service { if (it.recipeId == 99) throw UnknownRecipe() else error("sensitive internal failure") }) }
        val unknown = client.ask("""{"recipeId":99,"question":"Step?"}""")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("recipe_not_found", json.decodeFromString<ErrorReply>(unknown.bodyAsText()).error)
        val failure = client.ask("""{"recipeId":1,"question":"Step?"}""")
        assertEquals(HttpStatusCode.BadGateway, failure.status)
        assertEquals("assistant_unavailable", json.decodeFromString<ErrorReply>(failure.bodyAsText()).error)
    }

    @Test fun recipeIdAndQuestionAreForwardedAndIgnoredReplyHasNoAnswer() = testApplication {
        var received: Question? = null
        application { assistantModule(service { received = it; Reply(ignored = true) }) }
        val response = client.ask("""{"recipeId":7,"question":"Weather?"}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(Question(7, "Weather?"), received)
        assertEquals(Reply(ignored = true), json.decodeFromString<Reply>(response.bodyAsText()))
    }
}
