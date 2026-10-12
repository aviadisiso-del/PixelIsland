package com.siso.pixelisland

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Gives the app access to notifications (for previews) and, as a side effect,
 * permission to read active media sessions.
 */
class IslandNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        IslandBus.listenerConnected?.invoke()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        val isCall = n.category == Notification.CATEGORY_CALL

        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Media notifications are handled through the media session instead.
        if (n.category == Notification.CATEGORY_TRANSPORT) return
        if (sbn.isOngoing && !isCall) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val pm = packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val icon = try {
            n.getLargeIcon()?.loadDrawable(this) ?: n.smallIcon?.loadDrawable(this)
        } catch (e: Exception) {
            null
        }

        IslandBus.notifications?.invoke(
            NotifInfo(
                key = sbn.key,
                appName = appName,
                title = title,
                text = text,
                icon = icon,
                isCall = isCall,
                contentIntent = n.contentIntent
            )
        )
    }
}
