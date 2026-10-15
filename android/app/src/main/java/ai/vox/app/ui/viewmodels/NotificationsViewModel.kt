package ai.vox.app.ui.viewmodels

import ai.vox.app.AppContainer
import ai.vox.app.core.model.AiMessage
import ai.vox.app.core.model.AiTask
import ai.vox.app.core.model.Capability
import ai.vox.app.core.model.ContentPart
import ai.vox.app.core.model.Role
import ai.vox.app.core.net.AiException
import ai.vox.app.core.net.AiRequest
import ai.vox.app.core.notifications.ReplyDraftMachine
import ai.vox.app.core.notifications.ReplyState
import ai.vox.app.notifications.NotificationItem
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class NotificationsViewModel(private val c: AppContainer) : ViewModel() {
    private val _selectedKey = MutableStateFlow<String?>(null)
    val selectedKey: StateFlow<String?> = _selectedKey.asStateFlow()
    private val _machine = MutableStateFlow<ReplyDraftMachine?>(null)
    val machine: StateFlow<ReplyDraftMachine?> = _machine.asStateFlow()
    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary.asStateFlow()
    private val _summarizing = MutableStateFlow(false)
    val summarizing: StateFlow<Boolean> = _summarizing.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var job: Job? = null

    init {
        // Stop processing notification content the moment notification assistance or the session ends.
        viewModelScope.launch {
            c.session.state.map { Capability.NOTIFICATIONS in it.enabled }.distinctUntilChanged().collect { on -> if (!on) reset() }
        }
    }

    fun select(item: NotificationItem?) {
        job?.cancel()
        _selectedKey.value = item?.key
        _machine.value = item?.let { ReplyDraftMachine(it.canReply) }
    }

    fun selected(items: List<NotificationItem>): NotificationItem? = items.firstOrNull { it.key == _selectedKey.value }

    fun clearNotice() { _notice.value = null }

    /** Summarize & prioritize everything currently visible. Uses only text from the notifications list. */
    fun summarize(items: List<NotificationItem>) {
        if (items.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            _summarizing.value = true
            try {
                val text = items.take(30).joinToString("\n") { "- [${it.appLabel}] ${it.title}: ${it.text.take(300)}" }
                val reply = c.provider.complete(
                    AiRequest(AiTask.NOTIFICATIONS, listOf(AiMessage(Role.USER, listOf(ContentPart.Text("Here are my current notifications:\n$text\n\nSummarize them and tell me what to deal with first.")))), c.settings.value.responseLength, spoken = c.session.isEnabled(Capability.VOICE)),
                )
                _summary.value = reply.text
                if (c.session.isEnabled(Capability.VOICE) && c.settings.value.speakReplies) c.speechOut.speak(reply.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _notice.value = e.userMessage
            } catch (e: Exception) {
                _notice.value = "Couldn't summarize right now."
            } finally {
                _summarizing.value = false
            }
        }
    }

    /** Ask the model for a reply draft. Nothing is sent anywhere except to the AI service for drafting. */
    fun draft(item: NotificationItem, instruction: String) {
        val m = _machine.value ?: return
        if (!m.startDrafting()) { _notice.value = "This notification can't be replied to directly."; return }
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val convo = if (item.conversation.isNotEmpty()) "Recent conversation:\n" + item.conversation.joinToString("\n") else "Message: ${item.text}"
                val prompt = buildString {
                    append("Notification from ${item.appLabel}.\nFrom/title: ${item.title}\n$convo\n\n")
                    append(if (instruction.isBlank()) "Write a short, natural reply." else "My instructions for the reply: ${instruction.trim()}")
                }
                val reply = c.provider.complete(AiRequest(AiTask.REPLY, listOf(AiMessage(Role.USER, listOf(ContentPart.Text(prompt)))), c.settings.value.responseLength, spoken = false))
                m.draftReady(reply.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                m.draftFailed(e.userMessage)
            } catch (e: Exception) {
                m.draftFailed("Couldn't draft a reply right now.")
            }
        }
    }

    fun edit(text: String) { _machine.value?.edit(text) }

    fun readAloud(text: String) { if (c.session.isEnabled(Capability.VOICE)) c.speechOut.speak(text) else _notice.value = "Turn on Voice to hear it read aloud." }

    /** Step 1 of sending: shows the exact text for approval. */
    fun requestSend() { _machine.value?.requestSend() }

    /** Step 2: the user approved the exact text in the confirmation dialog. */
    fun confirmSend(item: NotificationItem) {
        val m = _machine.value ?: return
        val text = m.confirmSend() ?: return
        val error = c.replier.send(item, text)
        if (error == null) m.sendSucceeded() else m.sendFailed(error)
    }

    fun cancel() { _machine.value?.cancel(); c.speechOut.stop() }

    fun openApp(item: NotificationItem) {
        if (!c.replier.open(item)) _notice.value = "Couldn't open ${item.appLabel} from here. Open it yourself from the home screen."
    }

    private fun reset() {
        job?.cancel()
        _selectedKey.value = null
        _machine.value = null
        _summary.value = null
        _summarizing.value = false
    }

    override fun onCleared() { job?.cancel() }
}
