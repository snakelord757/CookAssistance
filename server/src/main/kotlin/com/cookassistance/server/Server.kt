package com.cookassistance.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class RecipeStore(
    private val recipes: List<Recipe>,
    private val collections: List<CollectionInfo>,
    private val dishOfTheDayId: String,
) {
    fun all(): List<Recipe> = recipes
    fun byType(type: String): List<Recipe> = recipes.filter { it.type == type }
    fun get(id: String): Recipe? = recipes.firstOrNull { it.id == id }
    fun dishOfTheDay(): Recipe? = get(dishOfTheDayId)
    fun collections(): List<CollectionInfo> = collections
    fun byCollection(id: String): List<Recipe> = recipes.filter { id in it.collections }
}

fun Application.recipeModule(store: RecipeStore) {
    install(ContentNegotiation) { json(json) }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        get("/recipes") {
            val type = call.request.queryParameters["type"]
            call.respond(if (type.isNullOrBlank()) store.all() else store.byType(type))
        }

        get("/recipes/dish-of-the-day") {
            val recipe = store.dishOfTheDay()
            if (recipe == null) call.respond(HttpStatusCode.NotFound, ErrorReply("dish_of_the_day_not_found"))
            else call.respond(recipe)
        }

        get("/recipes/{id}") {
            val id = call.parameters["id"].orEmpty()
            val recipe = store.get(id)
            if (recipe == null) call.respond(HttpStatusCode.NotFound, ErrorReply("recipe_not_found"))
            else call.respond(recipe)
        }

        get("/collections") { call.respond(store.collections()) }

        get("/collections/{id}/recipes") {
            val id = call.parameters["id"].orEmpty()
            call.respond(store.byCollection(id))
        }

        post("/feedback") {
            val request = try { call.receive<FeedbackRequest>() } catch (_: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorReply("invalid_request"))
            }
            if (request.message.isBlank() || request.message.length > 2000) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorReply("invalid_message"))
            }
            call.respond(FeedbackReply("accepted"))
        }
    }
}

fun main() {
    val store = RecipeStore(recipes, collections, dishOfTheDayId)
    val http = HttpClient(OkHttp) {
        expectSuccess = true
        install(ClientContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 12_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 12_000 }
    }
    val service = selectAssistant(
        System.getenv("ASSIST_BACKEND"),
        http,
        System.getenv("DEEPSEEK_API_KEY").orEmpty(),
        System.getenv("DEEPSEEK_MODEL") ?: "deepseek-chat",
        System.getenv("LOCAL_MODEL_URL") ?: "http://127.0.0.1:8000",
        store,
    )
    embeddedServer(Netty, host = "127.0.0.1", port = 8080) {
        recipeModule(store)
        assistantModule(service)
    }.start(wait = true)
}
