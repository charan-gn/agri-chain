package com.agrichain.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.agrichain.core.Event
import com.agrichain.mesh.RelayStore
import org.json.JSONObject

/**
 * Durable outbox for events waiting to reach the ledger, plus the persisted
 * seen-set used by the mesh relay. BatchStore remains the per-screen event log;
 * this queue survives restarts and tracks upload state.
 */
class SyncStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), RelayStore {

    companion object {
        private const val DB_NAME = "agrichain_sync.db"
        private const val DB_VERSION = 2
        private const val TABLE = "outbox"
        private const val SEEN = "seen"

        private const val CREATE_OUTBOX = """
            CREATE TABLE outbox (
                event_id TEXT PRIMARY KEY,
                batch_id TEXT NOT NULL,
                ts INTEGER NOT NULL,
                json TEXT NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val CREATE_SEEN = """
            CREATE TABLE seen (
                event_id TEXT PRIMARY KEY,
                seen_at INTEGER NOT NULL
            )
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_OUTBOX)
        db.execSQL(CREATE_SEEN)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(CREATE_SEEN)
    }

    override fun enqueue(event: Event): Boolean {
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

    override fun pending(): List<Event> {
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

    override fun markSynced(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        val placeholders = eventIds.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "UPDATE $TABLE SET synced = 1 WHERE event_id IN ($placeholders)",
            eventIds.toTypedArray(),
        )
    }

    override fun hasSeen(eventId: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM $SEEN WHERE event_id = ? LIMIT 1",
            arrayOf(eventId),
        ).use { it.moveToFirst() }

    override fun markSeen(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (eventId in eventIds) {
                db.insertWithOnConflict(
                    SEEN,
                    null,
                    ContentValues().apply {
                        put("event_id", eventId)
                        put("seen_at", now)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE synced = 0", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
