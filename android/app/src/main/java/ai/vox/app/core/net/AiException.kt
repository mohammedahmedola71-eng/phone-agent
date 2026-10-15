package ai.vox.app.core.net

/** Everything that can go wrong talking to the gateway, with a message that is safe and useful to show the user. */
sealed class AiException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause) {
    class NotConfigured(detail: String? = null) : AiException(detail ?: "Set your Vox gateway address and app token in Settings first.")
    class Network(cause: Throwable? = null) : AiException("Can't reach the Vox gateway. Check your connection and try again.", cause)
    class Unauthorized : AiException("The gateway rejected your app token. Check it in Settings.")
    class RateLimited(val retryAfterSec: Int?) :
        AiException("Usage limit reached." + (retryAfterSec?.let { " Try again in about ${maxOf(1, it / 60)} min." } ?: ""))
    class PayloadTooLarge(detail: String) : AiException("That request is too large for the gateway. $detail")
    class ModelUnavailable : AiException("The AI model is unavailable right now. Try again later or change the provider on your gateway.")
    class Server(detail: String) : AiException(detail)
}
