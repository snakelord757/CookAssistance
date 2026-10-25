package com.cookassistance.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

interface RecipeRepository {
    suspend fun list(): List<Recipe>
    suspend fun get(id: String): Recipe
}

class NetworkRecipeRepository(private val http: HttpClient, private val baseUrl: String) : RecipeRepository {
    override suspend fun list(): List<Recipe> = http.get("${baseUrl.trimEnd('/')}/recipes").body()
    override suspend fun get(id: String): Recipe = http.get("${baseUrl.trimEnd('/')}/recipes/$id").body()
}
