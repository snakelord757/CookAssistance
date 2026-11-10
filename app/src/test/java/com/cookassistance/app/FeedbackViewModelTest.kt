package com.cookassistance.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class FeedbackViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeFeedback(var fail: Boolean = false) : FeedbackRepository {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun send(message: String, contact: String) {
            if (fail) error("offline")
            calls += message to contact
        }
    }

    @Test fun emptyMessageShowsErrorAndDoesNotSend() = runTest(dispatcher) {
        val repo = FakeFeedback()
        val vm = FeedbackViewModel(repo)
        vm.submit()
        assertTrue(vm.state.value.messageError)
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun validMessageSendsAndShowsSent() = runTest(dispatcher) {
        val repo = FakeFeedback()
        val vm = FeedbackViewModel(repo)
        vm.message("Отличное приложение!")
        vm.contact("user@example.com")
        vm.submit(); runCurrent()
        assertEquals(listOf("Отличное приложение!" to "user@example.com"), repo.calls)
        assertTrue(vm.state.value.sent)
        assertFalse(vm.state.value.error)
    }

    @Test fun failureShowsError() = runTest(dispatcher) {
        val repo = FakeFeedback(fail = true)
        val vm = FeedbackViewModel(repo)
        vm.message("Отзыв")
        vm.submit(); runCurrent()
        assertTrue(vm.state.value.error)
        assertFalse(vm.state.value.sent)
    }
}
