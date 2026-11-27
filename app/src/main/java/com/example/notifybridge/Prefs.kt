package com.example.notifybridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Settings stored on this device. */
class Prefs(context: Context) {
    companion object {
        const val MODE_SENDER = "sender"
        const val MODE_RECEIVER = "receiver"
    }

    private val p = context.applicationContext
        .getSharedPreferences("notifybridge", Context.MODE_PRIVATE)

    /** Which role this device plays: MODE_SENDER (phone) or MODE_RECEIVER (e-reader). */
    var mode: String?
        get() = p.getString("mode", null)
        set(v) {
            p.edit().putString("mode", v).apply()
        }

    /** True while the user wants the bridge running (used to restart it after a reboot). */
    var running: Boolean
        get() = p.getBoolean("running", false)
        set(v) {
            p.edit().putBoolean("running", v).apply()
        }

    /** Sender only: package names whose notifications are forwarded. */
    var allowed: Set<String>
        get() = HashSet(p.getStringSet("allowed", emptySet()) ?: emptySet())
        set(v) {
            p.edit().putStringSet("allowed", HashSet(v)).apply()
        }

    /** Sender only: if false, only the app name and title are sent, not the message body. */
    var includeText: Boolean
        get() = p.getBoolean("includeText", true)
        set(v) {
            p.edit().putBoolean("includeText", v).apply()
        }

    /** Receiver only: the phone this e-reader connects to. */
    var peerAddress: String?
        get() = p.getString("peerAddress", null)
        set(v) {
            p.edit().putString("peerAddress", v).apply()
        }

    var peerName: String?
        get() = p.getString("peerName", null)
        set(v) {
            p.edit().putString("peerName", v).apply()
        }

    /**
     * Both roles: use a shared security code instead of Bluetooth pairing. The same setting and the
     * same code must be used on the phone and the e-reader.
     */
    var useCode: Boolean
        get() = p.getBoolean("useCode", false)
        set(v) {
            p.edit().putBoolean("useCode", v).apply()
        }

    var code: String
        get() = p.getString("code", "") ?: ""
        set(v) {
            p.edit().putString("code", v).apply()
        }

    /** Both roles: record connection events for the Log screen. */
    var logEnabled: Boolean
        get() = p.getBoolean("logEnabled", false)
        set(v) {
            p.edit().putBoolean("logEnabled", v).apply()
        }
}

/** Latest status text, shown on the main screen. */
object Bridge {
    @Volatile var senderStatus: String = "Stopped"
    @Volatile var receiverStatus: String = "Stopped"
}

object Channels {
    const val STATUS = "status"
    const val INCOMING = "incoming"

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(STATUS, "Bridge status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(INCOMING, "Notifications from phone", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
