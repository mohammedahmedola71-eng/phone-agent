package com.phoneagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Observe screen -> ask Claude -> run the chosen tool -> repeat, until Claude calls `finish`. */
object AgentRunner {
    private const val MAX_STEPS = 40

    private const val SYSTEM = """You are an agent operating an Android phone on the user's behalf.
Each turn you see the current screen as a numbered list of UI elements. Call exactly one tool per turn.
- Use open_app to launch apps; prefer it over hunting through the home screen.
- Element ids change after every action; only use ids from the most recent screen.
- If the screen did not change as expected, try a different approach or use wait.
- Never enter passwords, payment details or other secrets, and never send messages, make purchases,
  delete data or change account settings unless the task explicitly asks for it. If an action is
  irreversible or you are unsure, call finish and ask the user to confirm.
- When the task is complete, or you are blocked, call finish with a short summary."""

    val log = MutableStateFlow<List<String>>(emptyList())
    val running = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private fun say(line: String) { log.value = log.value + line }

    fun start(apiKey: String, task: String) {
        if (running.value) return
        val svc = AgentAccessibilityService.instance
        if (svc == null) { say("Enable the Phone Agent accessibility service first."); return }
        log.value = emptyList()
        running.value = true
        job = scope.launch {
            try {
                run(svc, ClaudeClient(apiKey), task)
            } catch (e: kotlinx.coroutines.CancellationException) {
                say("Stopped.")
            } catch (e: Exception) {
                say("Error: ${e.message}")
            } finally {
                running.value = false
            }
        }
    }

    fun stop() { job?.cancel() }

    private suspend fun run(svc: AgentAccessibilityService, client: ClaudeClient, task: String) {
        say("Task: $task")
        val messages = JSONArray()
        messages.put(userText("Task: $task\n\nCurrent screen:\n${svc.snapshot()}"))

        for (step in 1..MAX_STEPS) {
            val reply = withContext(Dispatchers.IO) { client.send(SYSTEM, Tools.all, messages) }
            val content = reply.getJSONArray("content")
            messages.put(JSONObject().put("role", "assistant").put("content", content))

            var toolUse: JSONObject? = null
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.getString("type") == "text" && block.getString("text").isNotBlank()) say(block.getString("text"))
                if (block.getString("type") == "tool_use") toolUse = block
            }
            if (toolUse == null) { say("Done."); return }

            val name = toolUse.getString("name")
            val input = toolUse.getJSONObject("input")
            say("$step. $name $input")
            if (name == "finish") { say("✔ ${input.optString("summary")}"); return }

            val result = execute(svc, name, input)
            svc.settle()
            if (!scope.isActive) return

            // Only the latest screen is useful; drop older ones to keep the context small.
            scrubOldScreens(messages)
            messages.put(JSONObject().put("role", "user").put("content", JSONArray().put(
                JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", toolUse.getString("id"))
                    .put("content", "$result\n\nCurrent screen:\n${svc.snapshot()}")
            )))
        }
        say("Stopped after $MAX_STEPS steps without finishing.")
    }

    private suspend fun execute(svc: AgentAccessibilityService, name: String, input: JSONObject): String = when (name) {
        "click" -> svc.click(input.getInt("id"))
        "type_text" -> svc.typeText(input.getInt("id"), input.getString("text"))
        "scroll" -> svc.scroll(if (input.has("id")) input.getInt("id") else null, input.getString("direction") == "forward")
        "open_app" -> svc.openApp(input.getString("name"))
        "back" -> svc.back()
        "home" -> svc.home()
        "wait" -> "Waited."
        else -> "Error: unknown tool $name."
    }

    private fun userText(text: String) =
        JSONObject().put("role", "user").put("content", text)

    private fun scrubOldScreens(messages: JSONArray) {
        for (i in 1 until messages.length()) {
            val m = messages.getJSONObject(i)
            val c = m.optJSONArray("content") ?: continue
            if (m.getString("role") != "user") continue
            for (j in 0 until c.length()) {
                val b = c.getJSONObject(j)
                if (b.optString("type") == "tool_result") {
                    val old = b.optString("content")
                    b.put("content", old.substringBefore("\n\nCurrent screen:") + "\n[earlier screen omitted]")
                }
            }
        }
    }
}
