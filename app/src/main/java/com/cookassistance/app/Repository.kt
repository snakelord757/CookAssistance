package com.cookassistance.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow

interface RecipeRepository {
    val favorites: Flow<List<Recipe>>
    suspend fun list(): List<Recipe>
    suspend fun get(id: String): Recipe
    suspend fun save(recipe: Recipe)
    suspend fun remove(id: String)
}

class RecipeRepositoryImpl(
    private val http: HttpClient,
    private val baseUrl: String,
    private val store: FavoriteStore,
) : RecipeRepository {
    override val favorites: Flow<List<Recipe>> = store.observe()
    override suspend fun list(): List<Recipe> = http.get("${baseUrl.trimEnd('/')}/recipes").body()
    override suspend fun get(id: String): Recipe = store.find(id) ?: http.get("${baseUrl.trimEnd('/')}/recipes/$id").body()
    override suspend fun save(recipe: Recipe) = store.save(recipe)
    override suspend fun remove(id: String) = store.remove(id)
}

interface Assistant {
    suspend fun ask(id: String, question: String): AssistReply
}

class AssistantRepository(private val http: HttpClient, private val baseUrl: String) : Assistant {
    override suspend fun ask(id: String, question: String): AssistReply =
        http.post("${baseUrl.trimEnd('/')}/assist") {
            contentType(ContentType.Application.Json)
            setBody(AssistRequest(id, question))
        }.body()
}

interface FeedbackRepository {
    suspend fun send(message: String, contact: String)
}

class NetworkFeedbackRepository(private val http: HttpClient, private val baseUrl: String) : FeedbackRepository {
    override suspend fun send(message: String, contact: String) {
        http.post("${baseUrl.trimEnd('/')}/feedback") {
            contentType(ContentType.Application.Json)
            setBody(FeedbackRequest(message, contact))
        }
    }
}
