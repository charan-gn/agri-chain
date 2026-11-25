package com.agrichain.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.agrichain.core.Event
import com.agrichain.mesh.RelayStore
import org.json.JSONObject

/**
 * Durable outbox for events waiting to reach the ledger, plus the persisted relay
 * state: `seenEvents`, `seenEnvelopes`, `sentTo` (peer -> eventIds), `acked`
 * (peer + eventId) and the trusted ACK signer keys (H6).
 */
class SyncStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), RelayStore {

    companion object {
        private const val DB_NAME = "agrichain_sync.db"
        private const val DB_VERSION = 4
        private const val TABLE = "outbox"
        private const val SEEN = "seen"
        private const val SEEN_ENVELOPES = "seen_envelopes"
        private const val SENT_TO = "sent_to"
        private const val ACKED = "acked"
        private const val ACK_KEYS = "ack_keys"
        private const val ENVELOPE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val SENT_TTL_MS = 7 * 24 * 60 * 60 * 1000L

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

        private const val CREATE_SENT_TO = """
            CREATE TABLE sent_to (
                peer_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                sent_at INTEGER NOT NULL,
                PRIMARY KEY (peer_id, event_id)
            )
        """

        private const val CREATE_ACKED = """
            CREATE TABLE acked (
                peer_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                acked_at INTEGER NOT NULL,
                PRIMARY KEY (peer_id, event_id)
            )
        """

        private const val CREATE_ACK_KEYS = """
            CREATE TABLE ack_keys (
                signer_id TEXT PRIMARY KEY,
                pub_key TEXT NOT NULL,
                seen_at INTEGER NOT NULL
            )
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_OUTBOX)
        db.execSQL(CREATE_SEEN)
        db.execSQL(CREATE_SEEN_ENVELOPES)
        db.execSQL(CREATE_SENT_TO)
        db.execSQL(CREATE_ACKED)
        db.execSQL(CREATE_ACK_KEYS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(CREATE_SEEN)
        if (oldVersion < 3) db.execSQL(CREATE_SEEN_ENVELOPES)
        if (oldVersion < 4) {
            db.execSQL(CREATE_SENT_TO)
            db.execSQL(CREATE_ACKED)
            db.execSQL(CREATE_ACK_KEYS)
        }
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

    override fun markSentTo(peerId: String, eventIds: Collection<String>) {
        if (peerId.isBlank() || eventIds.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete(SENT_TO, "sent_at < ?", arrayOf((now - SENT_TTL_MS).toString()))
            for (eventId in eventIds) {
                db.insertWithOnConflict(
                    SENT_TO,
                    null,
                    ContentValues().apply {
                        put("peer_id", peerId)
                        put("event_id", eventId)
                        put("sent_at", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun sentTo(peerId: String): Set<String> {
        val ids = mutableSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT event_id FROM $SENT_TO WHERE peer_id = ?",
            arrayOf(peerId),
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        return ids
    }

    override fun markAcked(peerId: String, eventIds: Collection<String>) {
        if (peerId.isBlank() || eventIds.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (eventId in eventIds) {
                db.insertWithOnConflict(
                    ACKED,
                    null,
                    ContentValues().apply {
                        put("peer_id", peerId)
                        put("event_id", eventId)
                        put("acked_at", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun acked(peerId: String): Set<String> {
        val ids = mutableSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT event_id FROM $ACKED WHERE peer_id = ?",
            arrayOf(peerId),
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        return ids
    }

    override fun ackKey(signerId: String): String? =
        readableDatabase.rawQuery(
            "SELECT pub_key FROM $ACK_KEYS WHERE signer_id = ? LIMIT 1",
            arrayOf(signerId),
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun rememberAckKey(signerId: String, publicKeySpkiBase64: String) {
        if (signerId.isBlank() || publicKeySpkiBase64.isBlank()) return
        writableDatabase.insertWithOnConflict(
            ACK_KEYS,
            null,
            ContentValues().apply {
                put("signer_id", signerId)
                put("pub_key", publicKeySpkiBase64)
                put("seen_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    override fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE synced = 0", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
