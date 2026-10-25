package com.cookassistance.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Ingredient(val name: String, val amount: Double, val unit: String)

@Serializable
data class Recipe(
    val id: String,
    val title: String,
    val description: String,
    val type: String,
    val ingredients: List<Ingredient>,
    val steps: List<String>,
    val cookingTimeMin: Int,
    val servings: Int,
    val imageUrl: String,
    val collections: List<String> = emptyList(),
)

@Serializable
data class CollectionInfo(val id: String, val title: String, val description: String)

@Serializable
data class AssistRequest(val recipeId: String, val question: String)

@Serializable
data class AssistReply(val answer: String = "", val ignored: Boolean = false)

val recipeJson = Json { ignoreUnknownKeys = true }

fun httpClient() = HttpClient(OkHttp) {
    expectSuccess = true
    install(ContentNegotiation) { json(recipeJson) }
    install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 30_000 }
}
