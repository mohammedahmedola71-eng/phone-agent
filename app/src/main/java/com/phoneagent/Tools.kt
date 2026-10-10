package com.phoneagent

import org.json.JSONArray
import org.json.JSONObject

/** Tool schemas Claude can call to operate the phone. */
object Tools {
    private fun tool(name: String, description: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("input_schema", JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", JSONArray(required)))

    private fun prop(type: String, description: String, enum: List<String>? = null) =
        JSONObject().put("type", type).put("description", description).also {
            if (enum != null) it.put("enum", JSONArray(enum))
        }

    val all: JSONArray = JSONArray()
        .put(tool("click", "Tap the element with the given id from the latest screen.",
            JSONObject().put("id", prop("integer", "Element id, e.g. 5 for [5]")), listOf("id")))
        .put(tool("type_text", "Replace the text of an editable element with the given text.",
            JSONObject().put("id", prop("integer", "Element id of an editable field"))
                .put("text", prop("string", "Text to enter")), listOf("id", "text")))
        .put(tool("scroll", "Scroll a scrollable element (or the first scrollable one if id omitted).",
            JSONObject().put("id", prop("integer", "Element id of a scrollable container"))
                .put("direction", prop("string", "forward = down/next, backward = up/previous", listOf("forward", "backward"))),
            listOf("direction")))
        .put(tool("open_app", "Launch an installed app by name, e.g. \"Settings\" or \"Chrome\".",
            JSONObject().put("name", prop("string", "App name")), listOf("name")))
        .put(tool("back", "Press the system Back button."))
        .put(tool("home", "Press the system Home button."))
        .put(tool("wait", "Wait for the screen to update, then re-read it."))
        .put(tool("finish", "End the task. Use when done, when blocked, or when you need the user's confirmation or input.",
            JSONObject().put("summary", prop("string", "What was accomplished, or what you need from the user")),
            listOf("summary")))
}
