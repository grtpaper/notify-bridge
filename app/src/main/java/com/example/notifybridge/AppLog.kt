package com.example.notifybridge

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional connection log, shown on the Log screen. Only records while "Log screen" is turned on in
 * Settings. Lines start with S: (phone side) or R: (e-reader side). Kept in a small file so it
 * survives the app being restarted; the oldest lines are dropped when it gets large.
 */
object AppLog {
    private const val FILE = "bridge-log.txt"
    private const val MAX_CHARS = 80_000
    private val lock = Any()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun add(context: Context, text: String) {
        if (!Prefs(context).logEnabled) return
        synchronized(lock) {
            try {
                val f = File(context.applicationContext.filesDir, FILE)
                f.appendText("${format.format(Date())} $text\n")
                if (f.length() > MAX_CHARS) {
                    val kept = f.readText().takeLast(MAX_CHARS / 2).substringAfter('\n')
                    f.writeText(kept)
                }
            } catch (e: Exception) {
                // Logging must never break the bridge.
            }
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        try {
            val f = File(context.applicationContext.filesDir, FILE)
            if (f.exists()) f.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try {
                File(context.applicationContext.filesDir, FILE).delete()
            } catch (e: Exception) {
                // Nothing to clear.
            }
        }
    }
}
