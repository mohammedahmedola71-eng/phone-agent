package ai.vox.service

import ai.vox.session.SessionState
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class IncomingMessage(val key: String, val app: String, val title: String, val text: String)

/** Collects notifications during a session and sends replies only when the caller has user approval. */
class VoxNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!SessionState.isActive) return
        val extras = sbn.notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        pending[sbn.key] = IncomingMessage(sbn.key, sbn.packageName, title, text)
    }

    /** Fires the notification's inline-reply action. Call only after the user approved [reply]. */
    fun sendReply(key: String, reply: String): Boolean {
        if (!SessionState.isActive) return false
        val sbn = activeNotifications.firstOrNull { it.key == key } ?: return false
        val action = sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
            ?: return false
        val inputs = action.remoteInputs
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, reply) }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        action.actionIntent.send(applicationContext, 0, intent)
        pending.remove(key)
        return true
    }

    companion object {
        @Volatile var instance: VoxNotificationListener? = null
        val pending = java.util.concurrent.ConcurrentHashMap<String, IncomingMessage>()
    }
}
