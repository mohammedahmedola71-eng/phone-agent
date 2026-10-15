package ai.vox.app.capture

import ai.vox.app.MainActivity
import ai.vox.app.R
import ai.vox.app.VoxApp
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the MediaProjection. It exists only while screen capture is active, and always
 * shows a persistent notification with controls: look now, pause/resume live analysis, end the session.
 */
class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val container get() = (application as VoxApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_LOOK -> look()
            ACTION_TOGGLE_LIVE -> { container.live.togglePause(); refreshNotification() }
            ACTION_END -> container.endSessionAsync()
            ACTION_STOP -> shutDown()
            else -> if (container.capture.grabber == null) shutDown()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        // Must call startForeground *before* obtaining the projection (required on Android 14+).
        startForegroundCompat(buildNotification("Vox can see your screen"))
        val grant = container.capture.takeGrant()
        if (grant == null) { shutDown(); return }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(grant.first, grant.second)
            container.capture.attach(ScreenGrabber(applicationContext, projection) {
                container.capture.detach()
                container.capture.onStoppedBySystem?.invoke()
                shutDown()
            })
        } catch (e: Exception) {
            container.capture.detach()
            shutDown()
        }
    }

    private fun look() {
        scope.launch {
            val frame = container.capture.grabScreen()
            container.capture.setPendingLook(frame)
            val text = if (frame == null) "Couldn't capture the screen" else "Screen captured. Tap to ask Vox about it."
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun refreshNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification("Vox can see your screen"))
    }

    private fun shutDown() {
        container.capture.grabber?.release()
        container.capture.detach()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        container.capture.grabber?.release()
        container.capture.detach()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    private fun buildNotification(text: String): Notification {
        val nm = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.capture_channel_desc)
            })
        }
        fun svc(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, ScreenCaptureService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val liveOn = container.live.isRunning()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vox)
            .setContentTitle("Vox - screen awareness active")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Look at screen", svc(ACTION_LOOK, 1))
            .apply { if (liveOn) addAction(0, if (container.live.isPaused()) "Resume live" else "Pause live", svc(ACTION_TOGGLE_LIVE, 2)) }
            .addAction(0, "End session", svc(ACTION_END, 3))
            .build()
    }

    companion object {
        const val ACTION_START = "ai.vox.app.capture.START"
        const val ACTION_LOOK = "ai.vox.app.capture.LOOK"
        const val ACTION_TOGGLE_LIVE = "ai.vox.app.capture.TOGGLE_LIVE"
        const val ACTION_END = "ai.vox.app.capture.END"
        const val ACTION_STOP = "ai.vox.app.capture.STOP"
        private const val CHANNEL = "vox_capture"
        private const val NOTIF_ID = 4201

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}
