package com.mazharansari.axion.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mazharansari.axion.AxionApp

/**
 * Phase F (v0.8.5): hears incoming message notifications — ONLY after the owner grants
 * Notification access in Android settings (a system grant this app cannot give itself).
 * Messaging apps only; Axion's own notifications, ongoing and group-summary posts are skipped.
 * What is heard lands in [NotifyInbox]; whether it is SPOKEN is the owner's Settings toggle
 * (assistantAnnounce, default off). Replying and screen-reading arrive in later Phase F stages.
 */
class AxionNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotifyInbox.listenerConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotifyInbox.listenerConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName == packageName) return
        if (!NotifyInbox.isMessaging(s.packageName)) return
        val n = s.notification ?: return
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.packageName, 0)).toString()
        }.getOrDefault(s.packageName)
        if (!NotifyInbox.offer(s.packageName, label, title, text, System.currentTimeMillis())) return
        val settings = runCatching { (application as AxionApp).graph.settings.state.value }.getOrNull() ?: return
        if (settings.assistantAnnounce) {
            AssistantAnnouncer.speak(applicationContext, NotifyInbox.spokenLine(label, title, text))
        }
    }
}
