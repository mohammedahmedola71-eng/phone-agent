package ai.vox.app

import ai.vox.app.ui.VoxRoot
import ai.vox.app.ui.theme.VoxTheme
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    private val container get() = (application as VoxApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent { VoxTheme { VoxRoot(container) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Mic and camera never run while Vox isn't on screen. Screen capture and notification help are session-controlled and shown in the notification shade. */
    override fun onStop() {
        super.onStop()
        container.onAppBackgrounded()
    }

    /** "Share to Vox" from another app: only parks the video in Video Lab. Nothing is read or uploaded until the user confirms there. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { container.sharedVideos.offer(it) }
        }
    }
}
