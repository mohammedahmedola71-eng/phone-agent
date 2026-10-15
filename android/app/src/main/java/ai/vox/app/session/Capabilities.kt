package ai.vox.app.session

import ai.vox.app.capture.CameraFrameSource
import ai.vox.app.capture.CaptureHub
import ai.vox.app.capture.ScreenCaptureService
import ai.vox.app.core.model.Capability
import ai.vox.app.core.session.CapabilityController
import ai.vox.app.live.LiveVideoEngine
import ai.vox.app.notifications.NotificationStore
import ai.vox.app.voice.AndroidSpeechInput
import ai.vox.app.voice.AndroidSpeechOutput
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private fun Context.has(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Microphone + spoken replies. Listening can only happen while this is enabled. */
class VoiceCapability(private val context: Context, private val input: AndroidSpeechInput, private val output: AndroidSpeechOutput) : CapabilityController {
    override val capability = Capability.VOICE
    override suspend fun start(): Result<Unit> = when {
        !context.has(Manifest.permission.RECORD_AUDIO) -> Result.failure(IllegalStateException("Microphone permission was not granted."))
        !input.isAvailable() -> Result.failure(IllegalStateException("No speech recognition service is available on this device. You can still type."))
        else -> Result.success(Unit)
    }
    override suspend fun stop() { input.stop(); output.shutdown() }
}

/** Screen capture via MediaProjection. Requires the user's one-time consent, handed over through [CaptureHub]. */
class ScreenCapability(private val context: Context, private val hub: CaptureHub) : CapabilityController {
    override val capability = Capability.SCREEN
    override suspend fun start(): Result<Unit> {
        if (!hub.hasGrant()) return Result.failure(IllegalStateException("Screen capture permission was not granted."))
        ScreenCaptureService.start(context)
        val ok = withTimeoutOrNull(6_000) { hub.active.first { it } } == true
        return if (ok) Result.success(Unit) else {
            ScreenCaptureService.stop(context)
            Result.failure(IllegalStateException("Couldn't start screen capture."))
        }
    }
    override suspend fun stop() {
        ScreenCaptureService.stop(context)
        hub.grabber?.release()
        hub.detach()
        hub.setPendingLook(null)
    }
}

class CameraCapability(private val context: Context, private val camera: CameraFrameSource) : CapabilityController {
    override val capability = Capability.CAMERA
    override suspend fun start(): Result<Unit> = when {
        !context.has(Manifest.permission.CAMERA) -> Result.failure(IllegalStateException("Camera permission was not granted."))
        !camera.bound -> Result.failure(IllegalStateException("The camera preview isn't ready."))
        else -> Result.success(Unit)
    }
    override suspend fun stop() { camera.unbind() }
}

class LiveCapability(private val engine: LiveVideoEngine) : CapabilityController {
    override val capability = Capability.LIVE_VIDEO
    override suspend fun start() = Result.success(Unit)
    override suspend fun stop() { engine.stop() }
}

class NotificationsCapability(private val store: NotificationStore) : CapabilityController {
    override val capability = Capability.NOTIFICATIONS
    override suspend fun start() = store.open()
    override suspend fun stop() { store.close() }
}
