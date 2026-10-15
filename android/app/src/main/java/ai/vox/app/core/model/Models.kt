package ai.vox.app.core.model

/** Things Vox can be allowed to access during a session. Nothing is enabled until the user turns it on. */
enum class Capability(val label: String) {
    VOICE("Microphone"),
    SCREEN("Screen capture"),
    CAMERA("Camera"),
    LIVE_VIDEO("Live analysis"),
    NOTIFICATIONS("Notifications"),
}

enum class ResponseLength { SHORT, MEDIUM, DETAILED }

/** What the backend should treat a request as. Maps 1:1 to the gateway `task` field. */
enum class AiTask(val wire: String) {
    CHAT("chat"),
    SCREEN("screen"),
    LIVE("live"),
    VIDEO("video"),
    REPLY("reply"),
    NOTIFICATIONS("notifications"),
}

enum class Role { USER, ASSISTANT }

sealed interface ContentPart {
    data class Text(val text: String) : ContentPart
    /** Base64 (no data-URI prefix) image bytes. Never logged; `toString` is redacted. */
    data class Image(val base64: String, val mediaType: String = "image/jpeg") : ContentPart {
        override fun toString() = "Image(${base64.length} b64 chars, $mediaType)"
    }
}

data class AiMessage(val role: Role, val parts: List<ContentPart>)

/** One entry in a conversation. [display] is what the user sees; [parts] is what is sent to the model. */
data class Turn(
    val id: String,
    val role: Role,
    val display: String,
    val parts: List<ContentPart> = listOf(ContentPart.Text(display)),
    val timestampMs: Long,
    val isError: Boolean = false,
    /** For user turns: how to re-run this turn on "regenerate". */
    val task: AiTask = AiTask.CHAT,
    val spoken: Boolean = false,
    val imageCount: Int = parts.count { it is ContentPart.Image },
)

/** Visual state of the Vox orb. */
enum class OrbState { INACTIVE, IDLE, LISTENING, THINKING, SPEAKING, ANALYZING }

data class Settings(
    val gatewayUrl: String = "",
    val voiceName: String? = null,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val responseLength: ResponseLength = ResponseLength.MEDIUM,
    val speakReplies: Boolean = true,
    val continuousConversation: Boolean = false,
    val saveConversations: Boolean = false,
    val liveIntervalSec: Int = 4,
    val liveChangeThreshold: Float = 0.04f,
    val speakLiveObservations: Boolean = true,
    val maxScreenAnalysesPerSession: Int = 60,
    val maxLiveFramesPerSession: Int = 120,
    val maxVideoFrames: Int = 24,
    val autoEndMinutes: Int = 30,
    val consentAccepted: Boolean = false,
)
