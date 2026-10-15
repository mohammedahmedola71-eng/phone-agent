package ai.vox.app.core.net

import ai.vox.app.core.model.ContentPart
import ai.vox.app.core.model.ResponseLength
import ai.vox.app.core.model.Role
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GatewayConfig(val baseUrl: String, val token: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class WirePart(val type: String, val text: String? = null, val mediaType: String? = null, val data: String? = null)

@Serializable
internal data class WireMessage(val role: String, val parts: List<WirePart>)

@Serializable
internal data class WireRequest(
    val task: String,
    val responseLength: String,
    val spoken: Boolean,
    val messages: List<WireMessage>,
)

@Serializable
internal data class WireReply(val text: String, val model: String? = null)

@Serializable
internal data class WireErrorBody(val code: String? = null, val message: String? = null, val retryAfterSec: Int? = null)

@Serializable
internal data class WireErrorEnvelope(val error: WireErrorBody? = null)

@Serializable
internal data class WireLimits(
    val requestsToday: Int = 0,
    val imagesToday: Int = 0,
    val requestsPerDay: Int = 0,
    val imagesPerDay: Int = 0,
    val requestsPerMinute: Int = 0,
    val maxImagesPerRequest: Int = 0,
)

@Serializable
internal data class WireStatus(val provider: String = "", val model: String = "", val limits: WireLimits = WireLimits())

/** Talks to the Vox gateway over HTTPS. Holds no provider secrets - only the per-install app token. */
class GatewayAiProvider(
    private val client: OkHttpClient = defaultClient(),
    private val config: () -> GatewayConfig,
) : AiProvider {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override suspend fun complete(request: AiRequest): AiReply {
        val wire = WireRequest(
            task = request.task.wire,
            responseLength = request.length.name.lowercase(),
            spoken = request.spoken,
            messages = request.messages.map { m ->
                WireMessage(
                    role = if (m.role == Role.USER) "user" else "assistant",
                    parts = m.parts.map { p ->
                        when (p) {
                            is ContentPart.Text -> WirePart("text", text = p.text)
                            is ContentPart.Image -> WirePart("image", mediaType = p.mediaType, data = p.base64)
                        }
                    },
                )
            },
        )
        val body = json.encodeToString(wire).toRequestBody(JSON)
        val res = execute(build("v1/complete").post(body).build())
        res.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw mapError(it.code, it.header("Retry-After"), raw)
            val parsed = runCatching { json.decodeFromString<WireReply>(raw) }.getOrNull()
                ?: throw AiException.Server("The gateway sent an unreadable answer.")
            return AiReply(parsed.text, parsed.model)
        }
    }

    override suspend fun status(): GatewayStatus {
        val res = execute(build("v1/status").get().build())
        res.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw mapError(it.code, it.header("Retry-After"), raw)
            val s = runCatching { json.decodeFromString<WireStatus>(raw) }.getOrNull()
                ?: throw AiException.Server("The gateway sent an unreadable status.")
            return GatewayStatus(
                s.provider, s.model, s.limits.requestsToday, s.limits.requestsPerDay, s.limits.imagesToday,
                s.limits.imagesPerDay, s.limits.requestsPerMinute, s.limits.maxImagesPerRequest,
            )
        }
    }

    private fun build(path: String): Request.Builder {
        val c = config()
        if (c.baseUrl.isBlank() || c.token.isBlank()) throw AiException.NotConfigured()
        val base = c.baseUrl.trim().trimEnd('/') + "/"
        val url = (base + path).toHttpUrlOrNull() ?: throw AiException.NotConfigured()
        return Request.Builder().url(url).header("Authorization", "Bearer ${c.token.trim()}").header("Accept", "application/json")
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(AiException.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    internal fun mapError(status: Int, retryAfterHeader: String?, raw: String): AiException {
        val err = runCatching { json.decodeFromString<WireErrorEnvelope>(raw).error }.getOrNull()
        return when {
            status == 401 -> AiException.Unauthorized()
            status == 429 -> AiException.RateLimited(err?.retryAfterSec ?: retryAfterHeader?.toIntOrNull())
            status == 413 -> AiException.PayloadTooLarge(err?.message.orEmpty())
            err?.code == "model_unavailable" -> AiException.ModelUnavailable()
            err?.code == "provider_unavailable" -> AiException.Server("The AI service is busy. Try again in a moment.")
            err?.message != null -> AiException.Server(err.message)
            else -> AiException.Server("The gateway returned an error ($status).")
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS) // frame uploads can be several MB on mobile data
            .readTimeout(150, TimeUnit.SECONDS)
            .build()
    }
}
