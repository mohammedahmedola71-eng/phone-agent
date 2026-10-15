package ai.vox.app.ui.components

import ai.vox.app.AppContainer
import ai.vox.app.VoxApp
import ai.vox.app.core.model.Capability
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Everything the UI needs to ask the user for access, with the proper Android permission flow for each capability. */
class CapabilityActions(
    val activate: () -> Unit,
    val toggle: (Capability) -> Unit,
    val requestCameraPermission: (onGranted: () -> Unit) -> Unit,
    val showConsent: Boolean,
    val consentAccepted: () -> Unit,
    val consentDismissed: () -> Unit,
)

@Composable
fun rememberCapabilityActions(container: AppContainer, onMessage: (String) -> Unit): CapabilityActions {
    val context = LocalContext.current
    var showConsent by remember { mutableStateOf(false) }
    var cameraCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun report(r: Result<Unit>) { r.onFailure { onMessage(it.message ?: "Couldn't turn that on.") } }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // Vox works either way; this only controls whether the "screen capture active" notification is visible in the shade.
        container.activate()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) container.enable(Capability.VOICE, ::report) else onMessage("Microphone permission denied. You can keep using Vox by typing.")
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraCallback?.invoke() else onMessage("Camera permission denied.")
        cameraCallback = null
    }
    val screenConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            container.capture.storeGrant(res.resultCode, data)
            container.enable(Capability.SCREEN, ::report)
        } else onMessage("Screen capture wasn't allowed, so screen awareness stays off.")
    }

    fun startActivation() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else container.activate()
    }

    fun enableNotifications() {
        if (container.notifications.isAccessGranted()) container.enable(Capability.NOTIFICATIONS, ::report)
        else {
            onMessage("Turn on notification access for Vox in the list that opens, then come back and tap Notifications again.")
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    return CapabilityActions(
        activate = { if (container.settings.value.consentAccepted) startActivation() else showConsent = true },
        toggle = { cap ->
            val enabled = container.session.isEnabled(cap)
            when {
                !container.session.state.value.active -> onMessage("Tap ACTIVATE VOX first.")
                enabled -> container.disable(cap)
                else -> when (cap) {
                    Capability.VOICE ->
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) container.enable(Capability.VOICE, ::report)
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    Capability.SCREEN -> {
                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenConsent.launch(mpm.createScreenCaptureIntent())
                    }
                    Capability.NOTIFICATIONS -> enableNotifications()
                    Capability.CAMERA, Capability.LIVE_VIDEO -> onMessage("Open Live Video to start the camera or live analysis.")
                }
            }
        },
        requestCameraPermission = { onGranted ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) onGranted()
            else { cameraCallback = onGranted; cameraPermission.launch(Manifest.permission.CAMERA) }
        },
        showConsent = showConsent,
        consentAccepted = {
            showConsent = false
            container.appScope.launch { container.settingsRepo.update { it.copy(consentAccepted = true) } }
            startActivation()
        },
        consentDismissed = { showConsent = false },
    )
}

fun Context.container(): AppContainer = (applicationContext as VoxApp).container
