package com.agrichain.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.agrichain.core.Event
import org.json.JSONObject

/**
 * Durable outbox for events waiting to reach the ledger. BatchStore remains the
 * per-screen event log; this queue survives restarts and tracks upload state.
 */
class SyncStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {

    companion object {
        private const val DB_NAME = "agrichain_sync.db"
        private const val TABLE = "outbox"

        private const val CREATE = """
            CREATE TABLE outbox (
                event_id TEXT PRIMARY KEY,
                batch_id TEXT NOT NULL,
                ts INTEGER NOT NULL,
                json TEXT NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0
            )
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first schema; future migrations go here.
    }

    fun enqueue(event: Event): Boolean {
        val values = ContentValues().apply {
            put("event_id", event.eventId)
            put("batch_id", event.batchId)
            put("ts", event.ts)
            put("json", event.toJsonObject().toString())
            put("synced", 0)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    fun pending(): List<Event> {
        val events = mutableListOf<Event>()
        readableDatabase.query(
            TABLE,
            arrayOf("json"),
            "synced = 0",
            null,
            null,
            null,
            "ts ASC, event_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { Event.fromJsonObject(JSONObject(cursor.getString(0))) }
                    .getOrNull()
                    ?.let(events::add)
            }
        }
        return events
    }

    fun markSynced(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        val placeholders = eventIds.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "UPDATE $TABLE SET synced = 1 WHERE event_id IN ($placeholders)",
            eventIds.toTypedArray(),
        )
    }

    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE synced = 0", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
