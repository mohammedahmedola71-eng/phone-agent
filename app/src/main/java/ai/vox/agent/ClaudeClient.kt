package ai.vox.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Minimal Anthropic Messages API client (non-streaming) with tool use. */
class ClaudeClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-5-5",
) {
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun send(system: String, messages: JSONArray, tools: JSONArray): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 1024)
                .put("system", system)
                .put("messages", messages)
                .put("tools", tools)
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) error("Claude API ${res.code}: $text")
                JSONObject(text)
            }
        }
}
