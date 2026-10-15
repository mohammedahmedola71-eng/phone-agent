package ai.vox.app.core.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ReplyState {
    data object Idle : ReplyState
    data object Drafting : ReplyState
    data class Drafted(val text: String) : ReplyState
    /** The exact text the user is being asked to approve. Nothing is sent until [ReplyDraftMachine.confirmSend]. */
    data class Confirming(val text: String) : ReplyState
    data class Sending(val text: String) : ReplyState
    data object Sent : ReplyState
    data class Failed(val message: String, val text: String) : ReplyState
}

/**
 * Enforces the "never send without explicit approval" rule as a state machine:
 * a send can only happen via Drafted -> Confirming -> Sending, and [confirmSend] returns the one string
 * that was shown to the user for approval.
 */
class ReplyDraftMachine(val canReply: Boolean) {
    private val _state = MutableStateFlow<ReplyState>(ReplyState.Idle)
    val state: StateFlow<ReplyState> = _state.asStateFlow()

    fun startDrafting(): Boolean {
        if (!canReply) return false
        if (_state.value is ReplyState.Sending || _state.value is ReplyState.Confirming) return false
        _state.value = ReplyState.Drafting
        return true
    }

    fun draftReady(text: String) {
        if (_state.value is ReplyState.Drafting) _state.value = ReplyState.Drafted(text.trim())
    }

    fun draftFailed(message: String) {
        if (_state.value is ReplyState.Drafting) _state.value = ReplyState.Failed(message, "")
    }

    /** User edited the draft (or typed one from scratch). Editing cancels any pending approval. */
    fun edit(text: String): Boolean {
        if (!canReply) return false
        return when (_state.value) {
            is ReplyState.Sending, is ReplyState.Drafting -> false
            else -> { _state.value = ReplyState.Drafted(text); true }
        }
    }

    /** User pressed Send: moves to the explicit-confirmation step. */
    fun requestSend(): Boolean {
        val s = _state.value
        if (!canReply || s !is ReplyState.Drafted || s.text.isBlank()) return false
        _state.value = ReplyState.Confirming(s.text)
        return true
    }

    /** User approved the exact text shown. Returns it exactly once; null if not in the confirming step. */
    fun confirmSend(): String? {
        val s = _state.value as? ReplyState.Confirming ?: return null
        _state.value = ReplyState.Sending(s.text)
        return s.text
    }

    fun sendSucceeded() { if (_state.value is ReplyState.Sending) _state.value = ReplyState.Sent }

    fun sendFailed(message: String) {
        val s = _state.value as? ReplyState.Sending ?: return
        _state.value = ReplyState.Failed(message, s.text)
    }

    /** Back out of confirmation or drop the draft. Always possible except mid-send. */
    fun cancel() {
        if (_state.value !is ReplyState.Sending) _state.value = ReplyState.Idle
    }

    fun backToEditing() {
        val s = _state.value
        if (s is ReplyState.Confirming) _state.value = ReplyState.Drafted(s.text)
        if (s is ReplyState.Failed && s.text.isNotBlank()) _state.value = ReplyState.Drafted(s.text)
    }
}
