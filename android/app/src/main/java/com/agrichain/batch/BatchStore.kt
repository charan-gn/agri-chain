package com.agrichain.batch

import android.content.Context
import com.agrichain.core.Event
import org.json.JSONArray

class BatchStore(context: Context) {

    private val prefs = context.getSharedPreferences("agrichain_batches", Context.MODE_PRIVATE)

    fun all(): List<Event> {
        val raw = prefs.getString("events", "[]") ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            Event.fromJsonObject(array.getJSONObject(index))
        }
    }

    fun save(event: Event) {
        val events = all()
        if (events.any { it.eventId == event.eventId }) return
        val array = JSONArray()
        (events + event).forEach { array.put(it.toJsonObject()) }
        prefs.edit().putString("events", array.toString()).apply()
    }

    fun registerEvent(batchId: String): Event? =
        all().firstOrNull { it.batchId == batchId && it.type == "REGISTER" }
}
