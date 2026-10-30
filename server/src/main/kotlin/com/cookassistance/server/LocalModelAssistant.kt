package com.cookassistance.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable data class LocalAnswerRequest(val recipe: String, val question: String)
@Serializable data class LocalAnswerReply(val answer: String = "", val truncated: Boolean = false)

/** Formats a decimal amount like Python's `f'{v:g}'.replace('.', ',')` used at training time. */
fun num(value: Double): String =
    BigDecimal(value.toString()).stripTrailingZeros().toPlainString().replace('.', ',')

/** Renders a recipe in the exact text format the adapter was trained on. */
fun recipeText(recipe: Recipe): String {
    val ingredients = recipe.ingredients.joinToString("; ") { "${it.name} — ${num(it.amount)} ${it.unit}" } + "."
    val steps = recipe.steps.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n")
    return "${recipe.title}. Порций: ${recipe.servings}.\nИнгредиенты: $ingredients\n$steps"
}

/**
 * Assistant backend backed by the locally trained model (Recipe Assistant V2).
 * Forwards the recipe text + question to the Python inference server and maps its
 * natural-language answer into [AssistReply]. No structured `ignored`/`timerMinutes`.
 */
class LocalModelAssistant(
    private val http: HttpClient,
    private val baseUrl: String,
    private val store: RecipeStore,
) : AssistantService {
    override suspend fun answer(question: AssistRequest): AssistReply {
        val recipe = store.get(question.recipeId) ?: throw UnknownRecipe()
        val reply = http.post("${baseUrl.trimEnd('/')}/answer") {
            contentType(ContentType.Application.Json)
            setBody(LocalAnswerRequest(recipeText(recipe), question.question))
        }.body<LocalAnswerReply>()
        val answer = reply.answer.trim()
        if (answer.isBlank()) throw InvalidUpstream()
        return AssistReply(answer = answer)
    }
}

/** Chooses the assistant backend from the `ASSIST_BACKEND` environment value. */
fun selectAssistant(
    backend: String?,
    http: HttpClient,
    key: String,
    model: String,
    localUrl: String,
    store: RecipeStore,
): AssistantService = when (backend?.trim()?.lowercase()) {
    "local" -> LocalModelAssistant(http, localUrl, store)
    else -> DeepSeekAssistant(http, key, model, store)
}
