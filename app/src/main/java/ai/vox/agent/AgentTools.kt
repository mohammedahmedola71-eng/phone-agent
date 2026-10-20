package ai.vox.agent

import ai.vox.service.VoxAccessibilityService
import ai.vox.service.VoxNotificationListener
import org.json.JSONArray
import org.json.JSONObject

/** Tool schemas exposed to the model, and their on-device implementations. */
object AgentTools {

    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", desc).put(
            "input_schema",
            JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)),
        )

    private fun int(d: String) = JSONObject().put("type", "integer").put("description", d)
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)

    val schemas: JSONArray = JSONArray()
        .put(tool("read_screen", "Return the visible UI elements as numbered lines."))
        .put(tool("click", "Click element by index from read_screen.", JSONObject().put("index", int("Element index")), listOf("index")))
        .put(tool("type_text", "Set text of an editable element.", JSONObject().put("index", int("Element index")).put("text", str("Text")), listOf("index", "text")))
        .put(tool("back", "Press Back."))
        .put(tool("home", "Press Home."))
        .put(tool("list_notifications", "List pending incoming messages captured this session."))
        .put(tool("send_reply", "Reply to a notification. The user must approve the text first.", JSONObject().put("key", str("Notification key")).put("text", str("Reply text")), listOf("key", "text")))

    /** Tools that act on the user's behalf in other apps and need explicit approval. */
    fun needsApproval(name: String) = name == "send_reply"

    fun run(name: String, input: JSONObject): String {
        val a11y = VoxAccessibilityService.instance
        return when (name) {
            "read_screen" -> a11y?.describeScreen() ?: "Accessibility service is not enabled."
            "click" -> ok(a11y?.click(input.getInt("index")))
            "type_text" -> ok(a11y?.typeText(input.getInt("index"), input.getString("text")))
            "back" -> ok(a11y?.back())
            "home" -> ok(a11y?.home())
            "list_notifications" -> VoxNotificationListener.pending.values.joinToString("\n") {
                "${it.key} | ${it.app} | ${it.title}: ${it.text}"
            }.ifBlank { "No pending messages." }
            "send_reply" -> ok(VoxNotificationListener.instance?.sendReply(input.getString("key"), input.getString("text")))
            else -> "Unknown tool: $name"
        }
    }

    private fun ok(b: Boolean?) = when (b) { true -> "ok"; false -> "failed"; null -> "service not enabled" }
}
