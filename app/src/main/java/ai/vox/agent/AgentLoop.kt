package ai.vox.agent

import ai.vox.session.SessionState
import org.json.JSONArray
import org.json.JSONObject

private const val SYSTEM = """You are VOX, a voice assistant that operates an Android phone for the user.
Use read_screen to see the screen, then act with click/type_text/back/home. Keep spoken replies short.
Never send messages without calling send_reply (the user approves each one). Stop when the task is done."""

class AgentLoop(
    private val claude: ClaudeClient,
    private val approve: suspend (String) -> Boolean,
    private val maxSteps: Int = 15,
) {
    private val history = JSONArray()

    /** Runs one user request to completion and returns the assistant's final spoken text. */
    suspend fun handle(userText: String): String {
        history.put(JSONObject().put("role", "user").put("content", userText))
        repeat(maxSteps) {
            if (!SessionState.isActive) return "Session ended."
            val res = claude.send(SYSTEM, history, AgentTools.schemas)
            val content = res.getJSONArray("content")
            history.put(JSONObject().put("role", "assistant").put("content", content))

            val results = JSONArray()
            val said = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.getString("type")) {
                    "text" -> said.append(block.getString("text"))
                    "tool_use" -> results.put(runTool(block))
                }
            }
            if (results.length() == 0) return said.toString()
            history.put(JSONObject().put("role", "user").put("content", results))
        }
        return "I stopped after $maxSteps steps."
    }

    private suspend fun runTool(block: JSONObject): JSONObject {
        val name = block.getString("name")
        val input = block.getJSONObject("input")
        SessionState.append("tool: $name $input")
        val output = if (AgentTools.needsApproval(name) && !approve(input.optString("text"))) {
            "User declined."
        } else {
            AgentTools.run(name, input)
        }
        return JSONObject().put("type", "tool_result").put("tool_use_id", block.getString("id")).put("content", output)
    }
}
