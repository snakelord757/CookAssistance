package com.cookassistance.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedbackState(
    val message: String = "",
    val contact: String = "",
    val messageError: Boolean = false,
    val sending: Boolean = false,
    val sent: Boolean = false,
    val error: Boolean = false,
)

class FeedbackViewModel(private val feedback: FeedbackRepository) : ViewModel() {
    private val _state = MutableStateFlow(FeedbackState())
    val state: StateFlow<FeedbackState> = _state.asStateFlow()

    fun message(text: String) {
        _state.update { it.copy(message = text.take(2000), messageError = false) }
    }

    fun contact(text: String) {
        _state.update { it.copy(contact = text.take(200)) }
    }

    fun submit() {
        val message = _state.value.message.trim()
        if (message.isEmpty()) {
            _state.update { it.copy(messageError = true) }
            return
        }
        if (_state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = false, sent = false) }
            try {
                feedback.send(message, _state.value.contact.trim())
                _state.update { it.copy(sending = false, sent = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(sending = false, error = true) }
            }
        }
    }
}
