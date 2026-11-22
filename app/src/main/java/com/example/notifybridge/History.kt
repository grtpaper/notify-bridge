package com.example.notifybridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * E-reader side. Messages received from the phone, newest first, saved on the device so they
 * survive app restarts and can be read even when the e-reader blocks pop-up notifications.
 * Call from the main thread only.
 */
object History {
    private const val KEY = "items"
    private const val MAX_ITEMS = 200

    /** Increases every time the list changes, so screens know when to redraw. */
    @Volatile
    var version = 0
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("notifybridge_history", Context.MODE_PRIVATE)

    fun all(context: Context): List<Message> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Message(o.optString("app"), o.optString("title"), o.optString("text"), o.optLong("t"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, message: Message) {
        val items = ArrayList(all(context))
        items.add(0, message)
        while (items.size > MAX_ITEMS) items.removeAt(items.size - 1)
        save(context, items)
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, items: List<Message>) {
        val arr = JSONArray()
        for (m in items) {
            arr.put(
                JSONObject()
                    .put("app", m.app)
                    .put("title", m.title)
                    .put("text", m.text)
                    .put("t", m.time)
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
        version++
    }
}
