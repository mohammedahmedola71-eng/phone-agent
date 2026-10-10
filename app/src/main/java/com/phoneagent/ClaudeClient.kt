package com.phoneagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Minimal blocking client for the Anthropic Messages API (call from a background dispatcher). */
class ClaudeClient(private val apiKey: String, private val model: String = DEFAULT_MODEL) {
    private val http = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun send(system: String, tools: JSONArray, messages: JSONArray): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("tools", tools)
            .put("tool_choice", JSONObject().put("type", "any").put("disable_parallel_tool_use", true))
            .put("messages", messages)
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("API error ${resp.code}: ${text.take(300)}")
            return JSONObject(text)
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
