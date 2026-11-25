package com.agrichain.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.agrichain.core.Event
import com.agrichain.mesh.RelayStore
import org.json.JSONObject

/**
 * Durable outbox for events waiting to reach the ledger, plus persisted
 * seen-event and seen-envelope sets used by the mesh relay.
 */
class SyncStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), RelayStore {

    companion object {
        private const val DB_NAME = "agrichain_sync.db"
        private const val DB_VERSION = 3
        private const val TABLE = "outbox"
        private const val SEEN = "seen"
        private const val SEEN_ENVELOPES = "seen_envelopes"
        private const val ENVELOPE_TTL_MS = 24 * 60 * 60 * 1000L

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

        private const val CREATE_SEEN_ENVELOPES = """
            CREATE TABLE seen_envelopes (
                envelope_id TEXT PRIMARY KEY,
                seen_at INTEGER NOT NULL
            )
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_OUTBOX)
        db.execSQL(CREATE_SEEN)
        db.execSQL(CREATE_SEEN_ENVELOPES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(CREATE_SEEN)
        if (oldVersion < 3) db.execSQL(CREATE_SEEN_ENVELOPES)
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

    override fun hasSeenEnvelope(envelopeId: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM $SEEN_ENVELOPES WHERE envelope_id = ? LIMIT 1",
            arrayOf(envelopeId),
        ).use { it.moveToFirst() }

    override fun markSeenEnvelope(envelopeId: String) {
        val now = System.currentTimeMillis()
        writableDatabase.delete(
            SEEN_ENVELOPES,
            "seen_at < ?",
            arrayOf((now - ENVELOPE_TTL_MS).toString()),
        )
        writableDatabase.insertWithOnConflict(
            SEEN_ENVELOPES,
            null,
            ContentValues().apply {
                put("envelope_id", envelopeId)
                put("seen_at", now)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    override fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE synced = 0", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
