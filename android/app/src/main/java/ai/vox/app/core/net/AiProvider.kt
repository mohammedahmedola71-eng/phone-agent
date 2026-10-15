package ai.vox.app.core.net

import ai.vox.app.core.model.AiMessage
import ai.vox.app.core.model.AiTask
import ai.vox.app.core.model.ResponseLength

data class AiRequest(
    val task: AiTask,
    val messages: List<AiMessage>,
    val length: ResponseLength,
    val spoken: Boolean,
)

data class AiReply(val text: String, val model: String?)

data class GatewayStatus(
    val provider: String,
    val model: String,
    val requestsToday: Int,
    val requestsPerDay: Int,
    val imagesToday: Int,
    val imagesPerDay: Int,
    val requestsPerMinute: Int,
    val maxImagesPerRequest: Int,
)

/**
 * The only thing the app knows about "the AI". To change providers, change them on the gateway; to change the
 * transport entirely (e.g. a realtime voice model), add another implementation of this interface.
 */
interface AiProvider {
    suspend fun complete(request: AiRequest): AiReply
    suspend fun status(): GatewayStatus
}
