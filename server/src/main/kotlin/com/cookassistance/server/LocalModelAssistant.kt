package com.cookassistance.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable data class LocalChatMessage(val role: String, val content: String)
@Serializable data class LocalChatRequest(val model: String = "qwen3-recipe-v2", val messages: List<LocalChatMessage>)
@Serializable data class LocalChatChoice(val message: LocalChatMessage)
@Serializable data class LocalChatReply(val choices: List<LocalChatChoice>)

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
 * Calls the local model's OpenAI-compatible chat endpoint, passing the recipe as
 * the system message and the question as the user message (chat mode). Maps the
 * natural-language answer into [AssistReply]; no structured `ignored`/`timerMinutes`.
 */
class LocalModelAssistant(
    private val http: HttpClient,
    private val baseUrl: String,
    private val store: RecipeStore,
) : AssistantService {
    override suspend fun answer(question: AssistRequest): AssistReply {
        val recipe = store.get(question.recipeId) ?: throw UnknownRecipe()
        val reply = http.post("${baseUrl.trimEnd('/')}/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(LocalChatRequest(messages = listOf(
                LocalChatMessage("system", recipeText(recipe)),
                LocalChatMessage("user", question.question),
            )))
        }.body<LocalChatReply>()
        val answer = reply.choices.firstOrNull()?.message?.content?.trim().orEmpty()
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
