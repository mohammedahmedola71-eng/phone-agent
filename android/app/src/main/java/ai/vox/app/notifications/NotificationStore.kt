package ai.vox.app.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class NotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    /** Recent lines of the conversation if the app provides them (MessagingStyle), oldest first. */
    val conversation: List<String>,
    val postedAtMs: Long,
    internal val replyAction: NotificationCompat.Action?,
    internal val replyInput: RemoteInput?,
    internal val contentIntent: PendingIntent?,
) {
    val canReply get() = replyAction != null && replyInput != null
    val canOpenApp get() = contentIntent != null
}

/**
 * Holds the notifications Vox may currently see. The gate is [isOpen]: it is true only while a session has notification
 * assistance switched on. While closed, the listener service drops every notification immediately and nothing is stored.
 */
class NotificationStore(private val context: Context) {
    @Volatile var isOpen = false
        private set
    @Volatile private var listener: NotificationListenerService? = null
    private val _items = MutableStateFlow<List<NotificationItem>>(emptyList())
    val items: StateFlow<List<NotificationItem>> = _items.asStateFlow()

    fun isAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun attach(service: NotificationListenerService) { listener = service; if (isOpen) refresh() }
    fun detach() { listener = null }

    /** Starts reading notifications. Fails if the user hasn't granted notification access in system settings. */
    fun open(): Result<Unit> {
        if (!isAccessGranted()) return Result.failure(IllegalStateException("Notification access isn't enabled for Vox in system settings."))
        isOpen = true
        if (listener == null) {
            // Ask the system to (re)bind the listener; it will call attach() when connected.
            runCatching { NotificationListenerService.requestRebind(ComponentName(context, VoxNotificationListenerService::class.java)) }
        } else refresh()
        return Result.success(Unit)
    }

    /** Stops reading and forgets everything. */
    fun close() {
        isOpen = false
        _items.value = emptyList()
    }

    fun refresh() {
        if (!isOpen) return
        val active = runCatching { listener?.activeNotifications }.getOrNull() ?: return
        _items.value = active.mapNotNull(::convert).sortedByDescending { it.postedAtMs }
    }

    fun onPosted(sbn: StatusBarNotification) {
        if (!isOpen) return
        val item = convert(sbn) ?: return
        _items.update { (listOf(item) + it.filterNot { o -> o.key == item.key }).sortedByDescending { n -> n.postedAtMs } }
    }

    fun onRemoved(key: String) {
        if (!isOpen) return
        _items.update { it.filterNot { n -> n.key == key } }
    }

    private fun convert(sbn: StatusBarNotification): NotificationItem? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == context.packageName) return null
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        // Respect the app's own privacy marking: "secret" content must not appear outside the lock screen rules.
        if (n.visibility == Notification.VISIBILITY_SECRET) return null
        if (n.category in SKIPPED_CATEGORIES) return null
        val extras: Bundle = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val convo = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages.orEmpty().takeLast(6).map { m ->
                "${m.person?.name ?: "Me"}: ${m.text}"
            }
        }.getOrDefault(emptyList())
        val (action, input) = findReply(n)
        return NotificationItem(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            title = title,
            text = text,
            conversation = convo,
            postedAtMs = sbn.postTime,
            replyAction = action,
            replyInput = input,
            contentIntent = n.contentIntent,
        )
    }

    /** Only genuine free-text reply actions the app itself exposes. Nothing is faked or forced. */
    private fun findReply(n: Notification): Pair<NotificationCompat.Action?, RemoteInput?> {
        val actions = buildList {
            for (i in 0 until NotificationCompat.getActionCount(n)) NotificationCompat.getAction(n, i)?.let(::add)
            addAll(NotificationCompat.WearableExtender(n).actions)
        }
        val candidates = actions.mapNotNull { a ->
            val input = a.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return@mapNotNull null
            Triple(a, input, a.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
        }
        val best = candidates.firstOrNull { it.third } ?: candidates.firstOrNull() ?: return null to null
        return best.first to best.second
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private val SKIPPED_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_CALL, Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS,
        )
    }
}

/** Sends a reply through the app's own RemoteInput action - the same mechanism as typing into the notification. */
class NotificationReplier(private val context: Context) {
    /** Returns null on success (the app accepted the intent), or a user-facing error. Never called without UI approval. */
    fun send(item: NotificationItem, text: String): String? {
        val action = item.replyAction ?: return "This notification doesn't support replying."
        val input = item.replyInput ?: return "This notification doesn't support replying."
        val pending = action.actionIntent ?: return "This notification doesn't support replying."
        return try {
            val intent = Intent()
            val results = Bundle().apply { putCharSequence(input.resultKey, text) }
            RemoteInput.addResultsToIntent(arrayOf(input), intent, results)
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            pending.send(context, 0, intent)
            null
        } catch (e: PendingIntent.CanceledException) {
            "${item.appLabel} no longer accepts this reply (the notification expired)."
        } catch (e: Exception) {
            "Couldn't hand the reply to ${item.appLabel}."
        }
    }

    /** Fallback for notifications that can't be replied to: open the app's own screen for it. */
    fun open(item: NotificationItem): Boolean {
        val pending = item.contentIntent ?: return false
        return try { pending.send(); true } catch (e: PendingIntent.CanceledException) { false }
    }
}
