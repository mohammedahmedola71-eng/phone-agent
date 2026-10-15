package ai.vox.app.capture

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide bridge between the capture foreground service and the rest of the app. */
class CaptureHub {
    /** The one-time MediaProjection consent result, handed from the UI to the service. Consumed on use. */
    private var grant: Pair<Int, Intent>? = null
    @Volatile var grabber: ScreenGrabber? = null
        private set
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    /** Set by the container: called when the system ends capture on its own. */
    var onStoppedBySystem: (() -> Unit)? = null
    /** A frame captured from the notification's "Look at screen" action, waiting for the user to ask about it. */
    private val _pendingLook = MutableStateFlow<Frame?>(null)
    val pendingLook: StateFlow<Frame?> = _pendingLook.asStateFlow()

    fun storeGrant(resultCode: Int, data: Intent) { grant = resultCode to data }
    fun takeGrant(): Pair<Int, Intent>? = grant.also { grant = null }
    fun hasGrant() = grant != null

    fun attach(g: ScreenGrabber) { grabber = g; _active.value = true }
    fun detach() { grabber = null; _active.value = false }
    fun setPendingLook(f: Frame?) { _pendingLook.value = f }
    fun takePendingLook(): Frame? = _pendingLook.value.also { _pendingLook.value = null }

    suspend fun grabScreen(maxEdge: Int = 1600): Frame? = grabber?.grab(maxEdge)
}

class ScreenFrameSource(private val hub: CaptureHub, private val maxEdge: Int = 1024) : FrameSource {
    override val label = "Screen"
    override suspend fun nextFrame(): Frame? = hub.grabScreen(maxEdge)
}
