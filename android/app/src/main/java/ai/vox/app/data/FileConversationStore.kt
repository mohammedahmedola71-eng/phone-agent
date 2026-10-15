package ai.vox.app.data

import ai.vox.app.core.assistant.ConversationStore
import ai.vox.app.core.model.Role
import ai.vox.app.core.model.Turn
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Saves conversation *text* only (never images) in app-private storage, and only when the user turned saving on. */
class FileConversationStore(context: Context) : ConversationStore {
    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Saved(val id: String, val role: String, val text: String, val ts: Long, val error: Boolean = false)

    private fun file(name: String) = File(dir, "${name.filter { it.isLetterOrDigit() || it == '_' }}.json")

    override fun save(name: String, turns: List<Turn>) {
        val saved = turns.takeLast(200).filter { !it.auto }.map { Saved(it.id, it.role.name, it.display, it.timestampMs, it.isError) }
        runCatching { file(name).writeText(json.encodeToString(saved)) }
    }

    override fun load(name: String): List<Turn> = runCatching {
        json.decodeFromString<List<Saved>>(file(name).readText()).map {
            Turn(it.id, Role.valueOf(it.role), it.text, timestampMs = it.ts, isError = it.error)
        }
    }.getOrDefault(emptyList())

    override fun delete(name: String) { file(name).delete() }

    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }
}
