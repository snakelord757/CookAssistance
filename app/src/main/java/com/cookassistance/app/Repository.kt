package com.cookassistance.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

interface RecipeRepository {
    suspend fun list(): List<Recipe>
    suspend fun get(id: String): Recipe
}

class NetworkRecipeRepository(private val http: HttpClient, private val baseUrl: String) : RecipeRepository {
    override suspend fun list(): List<Recipe> = http.get("${baseUrl.trimEnd('/')}/recipes").body()
    override suspend fun get(id: String): Recipe = http.get("${baseUrl.trimEnd('/')}/recipes/$id").body()
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
