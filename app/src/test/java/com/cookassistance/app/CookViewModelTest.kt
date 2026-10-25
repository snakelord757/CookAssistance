package com.cookassistance.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CookViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeRepo(var items: List<Recipe>, var fail: Boolean = false) : RecipeRepository {
        var getFail = false
        var saveFail = false
        override val favorites = MutableStateFlow<List<Recipe>>(emptyList())
        override suspend fun list(): List<Recipe> {
            if (fail) error("offline")
            return items
        }
        override suspend fun get(id: String): Recipe {
            if (getFail) error("offline")
            return items.first { it.id == id }
        }
        override suspend fun dishOfTheDay(): Recipe = items.first()
        override suspend fun collections(): List<CollectionInfo> = emptyList()
        override suspend fun byCollection(id: String): List<Recipe> = emptyList()
        override suspend fun save(recipe: Recipe) {
            if (saveFail) error("disk full")
            favorites.value = favorites.value.filterNot { it.id == recipe.id } + recipe
        }
        override suspend fun remove(id: String) {
            favorites.value = favorites.value.filterNot { it.id == id }
        }
    }

    private class FakeAssistant(var result: AssistReply = AssistReply(), var fail: Boolean = false) : Assistant {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun ask(id: String, question: String): AssistReply {
            calls += id to question
            if (fail) error("offline")
            return result
        }
    }

    private fun recipe(id: String) = Recipe(
        id = id, title = "Борщ", description = "Суп", type = "soup",
        ingredients = listOf(Ingredient("Свекла", 2.0, "шт")),
        steps = listOf("Варите"), cookingTimeMin = 90, servings = 6, imageUrl = "https://x",
    )

    private fun vm(repo: RecipeRepository, assistant: Assistant = FakeAssistant()) = CookViewModel(repo, assistant)

    // catalog

    @Test fun catalogSuccessShowsRecipes() = runTest(dispatcher) {
        val model = vm(FakeRepo(listOf(recipe("r1"), recipe("r2"))))
        runCurrent()
        val content = model.catalog.value as CatalogUiState.Content
        assertEquals(listOf("r1", "r2"), content.recipes.map { it.id })
    }

    @Test fun catalogEmptyListShowsEmpty() = runTest(dispatcher) {
        val model = vm(FakeRepo(emptyList()))
        runCurrent()
        assertEquals(CatalogUiState.Empty, model.catalog.value)
    }

    @Test fun catalogFailureShowsErrorAndRetryRecovers() = runTest(dispatcher) {
        val repo = FakeRepo(listOf(recipe("r1")), fail = true)
        val model = vm(repo)
        runCurrent()
        assertEquals(CatalogUiState.Error, model.catalog.value)
        repo.fail = false
        model.loadCatalog(); runCurrent()
        assertTrue(model.catalog.value is CatalogUiState.Content)
    }

    // detail

    @Test fun selectLoadsDetail() = runTest(dispatcher) {
        val model = vm(FakeRepo(listOf(recipe("r1"), recipe("r2"))))
        runCurrent()
        model.select("r1"); runCurrent()
        assertEquals("r1", (model.detail.value as DetailUiState.Content).recipe.id)
    }

    @Test fun selectFailureShowsErrorAndRetryRecovers() = runTest(dispatcher) {
        val repo = FakeRepo(listOf(recipe("r1"))).apply { getFail = true }
        val model = vm(repo)
        runCurrent()
        model.select("r1"); runCurrent()
        assertEquals(DetailUiState.Error, model.detail.value)
        repo.getFail = false
        model.select("r1"); runCurrent()
        assertTrue(model.detail.value is DetailUiState.Content)
    }

    @Test fun switchingRecipeDiscardsStaleDetail() = runTest(dispatcher) {
        val pending = CompletableDeferred<Recipe>()
        val repo = object : RecipeRepository {
            override val favorites = MutableStateFlow<List<Recipe>>(emptyList())
            override suspend fun list(): List<Recipe> = listOf(recipe("r1"), recipe("r2"))
            override suspend fun get(id: String): Recipe = if (id == "r1") pending.await() else recipe("r2")
            override suspend fun dishOfTheDay(): Recipe = recipe("r1")
            override suspend fun collections(): List<CollectionInfo> = emptyList()
            override suspend fun byCollection(id: String): List<Recipe> = emptyList()
            override suspend fun save(recipe: Recipe) {}
            override suspend fun remove(id: String) {}
        }
        val model = vm(repo)
        runCurrent()
        model.select("r1"); runCurrent()
        assertTrue(model.detail.value is DetailUiState.Loading)
        model.select("r2"); runCurrent()
        assertEquals("r2", (model.detail.value as DetailUiState.Content).recipe.id)
        pending.complete(recipe("r1")); runCurrent()
        assertEquals("r2", (model.detail.value as DetailUiState.Content).recipe.id)
    }

    // cooking / recognition

    @Test fun recognizedTextIsStoredAsStringAndBounded() = runTest(dispatcher) {
        val model = vm(FakeRepo(listOf(recipe("r1"))))
        runCurrent()
        model.startCooking("r1")
        model.question("Сколько варить свеклу?")
        assertEquals("Сколько варить свеклу?", model.cooking.value.question)
        model.question("a".repeat(700))
        assertEquals(500, model.cooking.value.question.length)
    }

    @Test fun startAndStopCookingResetQuestion() = runTest(dispatcher) {
        val model = vm(FakeRepo(listOf(recipe("r1"))))
        runCurrent()
        model.startCooking("r1")
        model.question("Вопрос")
        assertEquals("Вопрос", model.cooking.value.question)
        model.stopCooking()
        assertEquals("", model.cooking.value.question)
        model.startCooking("r1")
        assertEquals("", model.cooking.value.question)
    }

    // assistant

    @Test fun askSendsQuestionAndShowsAnswer() = runTest(dispatcher) {
        val assistant = FakeAssistant(result = AssistReply(answer = "Варите 10 минут"))
        val model = vm(FakeRepo(listOf(recipe("r1"))), assistant)
        runCurrent()
        model.startCooking("r1")
        model.question("Сколько варить?")
        model.ask(); runCurrent()
        assertEquals(listOf("r1" to "Сколько варить?"), assistant.calls)
        assertEquals("Варите 10 минут", model.cooking.value.answer)
        assertFalse(model.cooking.value.thinking)
    }

    @Test fun askFailureShowsError() = runTest(dispatcher) {
        val assistant = FakeAssistant(fail = true)
        val model = vm(FakeRepo(listOf(recipe("r1"))), assistant)
        runCurrent()
        model.startCooking("r1")
        model.question("Сколько варить?")
        model.ask(); runCurrent()
        assertTrue(model.cooking.value.assistError)
        assertFalse(model.cooking.value.thinking)
    }

    @Test fun ignoredReplyDoesNotShowAnswer() = runTest(dispatcher) {
        val assistant = FakeAssistant(result = AssistReply(ignored = true))
        val model = vm(FakeRepo(listOf(recipe("r1"))), assistant)
        runCurrent()
        model.startCooking("r1")
        model.question("Какая погода?")
        model.ask(); runCurrent()
        assertTrue(model.cooking.value.ignored)
        assertEquals("", model.cooking.value.answer)
    }

    // favorites

    @Test fun toggleSavesAndRemovesFavorite() = runTest(dispatcher) {
        val repo = FakeRepo(listOf(recipe("r1")))
        val model = vm(repo)
        runCurrent()
        model.toggle(recipe("r1")); runCurrent()
        assertEquals(listOf("r1"), model.favorites.value.map { it.id })
        model.toggle(recipe("r1")); runCurrent()
        assertTrue(model.favorites.value.isEmpty())
    }

    @Test fun toggleFailureShowsSaveErrorAndNextSucceeds() = runTest(dispatcher) {
        val repo = FakeRepo(listOf(recipe("r1"))).apply { saveFail = true }
        val model = vm(repo)
        runCurrent()
        model.toggle(recipe("r1")); runCurrent()
        assertTrue(model.saveError.value)
        assertTrue(model.favorites.value.isEmpty())
        repo.saveFail = false
        model.toggle(recipe("r1")); runCurrent()
        assertEquals(listOf("r1"), model.favorites.value.map { it.id })
        assertFalse(model.saveError.value)
    }

    // timer

    @Test fun timerCountsDownAndMarksDone() = runTest(dispatcher) {
        val model = vm(FakeRepo(listOf(recipe("r1"))))
        runCurrent()
        model.startTimer(1)
        runCurrent()
        assertEquals(60, model.timer.value.remainingSeconds)
        assertTrue(model.timer.value.running)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, model.timer.value.remainingSeconds)
        assertTrue(model.timer.value.done)
        assertFalse(model.timer.value.running)
    }

    @Test fun pauseAndResumeTimer() = runTest(dispatcher) {
        val model = vm(FakeRepo(listOf(recipe("r1"))))
        runCurrent()
        model.startTimer(2)
        runCurrent()
        assertTrue(model.timer.value.running)
        advanceTimeBy(10_000)
        runCurrent()
        model.pauseTimer()
        assertEquals(110, model.timer.value.remainingSeconds)
        assertFalse(model.timer.value.running)
        model.resumeTimer()
        runCurrent()
        assertTrue(model.timer.value.running)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(100, model.timer.value.remainingSeconds)
    }

    @Test fun invalidTimerMinutesAreIgnored() = runTest(dispatcher) {
        val model = vm(FakeRepo(listOf(recipe("r1"))))
        runCurrent()
        model.startTimer(0)
        assertEquals(0, model.timer.value.totalSeconds)
        model.startTimer(999)
        assertEquals(0, model.timer.value.totalSeconds)
    }

    @Test fun askWithTimerIntentStartsTimer() = runTest(dispatcher) {
        val assistant = FakeAssistant(result = AssistReply(answer = "Таймер на 10 минут", timerMinutes = 10))
        val model = vm(FakeRepo(listOf(recipe("r1"))), assistant)
        runCurrent()
        model.startCooking("r1")
        model.question("Поставь таймер на 10 минут")
        model.ask(); runCurrent()
        assertEquals(600, model.timer.value.totalSeconds)
        assertTrue(model.timer.value.running)
    }
}
