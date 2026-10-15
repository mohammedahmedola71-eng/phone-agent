package ai.vox.app.core.assistant

import ai.vox.app.core.model.AiMessage
import ai.vox.app.core.model.AiTask
import ai.vox.app.core.model.ContentPart
import ai.vox.app.core.model.ResponseLength
import ai.vox.app.core.model.Role
import ai.vox.app.core.model.Turn
import ai.vox.app.core.net.AiException
import ai.vox.app.core.net.AiProvider
import ai.vox.app.core.net.AiRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface AssistantEvent {
    data class Reply(val turn: Turn, val spoken: Boolean) : AssistantEvent
    data class Failure(val message: String) : AssistantEvent
}

/** Keeps the model's context small: only the most recent [maxImages] images stay attached to history. */
object History {
    fun prune(turns: List<Turn>, maxImages: Int): List<Turn> {
        var remaining = maxImages
        val out = ArrayList<Turn>(turns.size)
        for (t in turns.asReversed()) {
            if (t.parts.none { it is ContentPart.Image }) { out += t; continue }
            val kept = ArrayList<ContentPart>()
            var dropped = 0
            for (p in t.parts.asReversed()) {
                if (p is ContentPart.Image) { if (remaining > 0) { remaining--; kept += p } else dropped++ } else kept += p
            }
            kept.reverse()
            if (dropped > 0) kept += ContentPart.Text("[$dropped earlier image(s) no longer attached]")
            out += t.copy(parts = kept)
        }
        out.reverse()
        return out
    }

    fun toWire(turns: List<Turn>, maxImages: Int): List<AiMessage> =
        prune(turns.filter { !it.isError }, maxImages).map { AiMessage(it.role, it.parts) }
}

/** Persists only what the user sees (text), never images. */
interface ConversationStore {
    fun save(name: String, turns: List<Turn>)
    fun load(name: String): List<Turn>
    fun delete(name: String)
}

/**
 * One conversation thread (chat, screen Q&A, live observations...). Pure Kotlin so it can be unit tested without Android.
 * Requests are cancellable and "regenerate" re-runs the last user turn.
 */
class Assistant(
    private val name: String,
    private val provider: AiProvider,
    private val scope: CoroutineScope,
    private val length: () -> ResponseLength,
    private val persist: () -> Boolean = { false },
    private val store: ConversationStore? = null,
    private val maxImagesInContext: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _events = MutableSharedFlow<AssistantEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AssistantEvent> = _events.asSharedFlow()
    private var job: Job? = null
    @Volatile private var runId = 0

    fun restore() { _turns.value = store?.load(name).orEmpty() }

    /** Sends a user turn and asks the model. [display] is shown in the chat; [parts] go to the model. */
    fun ask(task: AiTask, display: String, parts: List<ContentPart> = listOf(ContentPart.Text(display)), spoken: Boolean = false, auto: Boolean = false): Job {
        val user = Turn(newId(), Role.USER, display, parts, clock(), task = task, spoken = spoken, auto = auto)
        _turns.update { History.prune(it, maxImagesInContext - user.imageCount.coerceAtMost(maxImagesInContext)) + user }
        return run(task, spoken)
    }

    /** Drops the last answer (or error) and asks again with the same user turn. */
    fun regenerate(): Job? {
        val current = _turns.value
        val lastUserIdx = current.indexOfLast { it.role == Role.USER }
        if (lastUserIdx < 0 || _busy.value) return null
        val user = current[lastUserIdx]
        _turns.value = current.subList(0, lastUserIdx + 1)
        return run(user.task, user.spoken)
    }

    fun cancel() {
        runId++
        job?.cancel()
        job = null
        _busy.value = false
    }

    /** Clears the thread from memory *and* disk. */
    fun clear() {
        cancel()
        _turns.value = emptyList()
        store?.delete(name)
    }

    private fun run(task: AiTask, spoken: Boolean): Job {
        job?.cancel()
        val myRun = ++runId
        _busy.value = true
        val snapshot = _turns.value
        val launched = scope.launch {
            try {
                val reply = provider.complete(AiRequest(task, History.toWire(snapshot, maxImagesInContext), length(), spoken))
                val turn = Turn(newId(), Role.ASSISTANT, reply.text.trim(), timestampMs = clock(), task = task, spoken = spoken)
                _turns.update { it + turn }
                if (persist()) store?.save(name, _turns.value)
                _events.emit(AssistantEvent.Reply(turn, spoken))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                fail(e.userMessage, task)
            } catch (e: Exception) {
                fail("Something went wrong. Please try again.", task)
            } finally {
                if (runId == myRun) _busy.value = false
            }
        }
        job = launched
        return launched
    }

    private suspend fun fail(message: String, task: AiTask) {
        _turns.update { it + Turn(newId(), Role.ASSISTANT, message, timestampMs = clock(), isError = true, task = task) }
        _events.emit(AssistantEvent.Failure(message))
    }
}
