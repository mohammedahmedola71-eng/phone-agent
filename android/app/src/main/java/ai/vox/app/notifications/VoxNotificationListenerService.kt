package ai.vox.app.notifications

import ai.vox.app.VoxApp
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Bound by the system once the user enables notification access. It does nothing with notifications unless
 * [NotificationStore.isOpen] - i.e. an active Vox session with notification assistance switched on.
 */
class VoxNotificationListenerService : NotificationListenerService() {
    private val store get() = (application as VoxApp).container.notifications

    override fun onListenerConnected() { store.attach(this) }
    override fun onListenerDisconnected() { store.detach() }

    override fun onNotificationPosted(sbn: StatusBarNotification) { store.onPosted(sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { store.onRemoved(sbn.key) }
}
