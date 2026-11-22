package com.example.notifybridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Phone side. Android hands every notification posted by other apps to this service
 * once the user grants "Notification access". Allowed apps are passed on to SenderService.
 */
class NotificationForwarder : NotificationListenerService() {

    private val lastSeen = HashMap<String, Int>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            // Never let one odd notification crash the listener.
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val prefs = Prefs(this)
        if (prefs.mode != Prefs.MODE_SENDER || !prefs.running) return
        if (sbn.packageName !in prefs.allowed) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (!prefs.includeText) text = ""
        if (title.isBlank() && text.isBlank()) return

        // Apps re-post the same notification for progress or refresh; send each change once.
        val hash = (title + "\u0000" + text).hashCode()
        if (lastSeen[sbn.key] == hash) return
        if (lastSeen.size > 200) lastSeen.clear()
        lastSeen[sbn.key] = hash

        SenderService.instance?.enqueue(Message(appLabel(sbn.packageName), title, text, sbn.postTime))
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
