package com.agrichain.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One local mesh chat message. Chat is never posted to the ledger. */
data class ChatMessage(
    val envelopeId: String,
    val senderId: String,
    val body: String,
    val ts: Long,
    val outgoing: Boolean,
    val status: String,
)

/**
 * Durable mesh chat history. `envelopeId` is the primary key so a relayed
 * duplicate cannot show twice, even if the relay restarts.
 */
class ChatStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "agrichain_chat.db"
        private const val DB_VERSION = 1
        private const val TABLE = "chat"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                envelope_id TEXT PRIMARY KEY,
                sender_id TEXT NOT NULL,
                body TEXT NOT NULL,
                ts INTEGER NOT NULL,
                outgoing INTEGER NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX chat_ts ON $TABLE (ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(message: ChatMessage) {
        val values = ContentValues().apply {
            put("envelope_id", message.envelopeId)
            put("sender_id", message.senderId)
            put("body", message.body)
            put("ts", message.ts)
            put("outgoing", if (message.outgoing) 1 else 0)
            put("status", message.status)
        }
        writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** Newest [limit] messages, returned oldest-first for a chat list. */
    fun recent(limit: Int = 200): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        readableDatabase.query(
            TABLE,
            arrayOf("envelope_id", "sender_id", "body", "ts", "outgoing", "status"),
            null,
            null,
            null,
            null,
            "ts DESC, rowid DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += ChatMessage(
                    envelopeId = cursor.getString(0),
                    senderId = cursor.getString(1),
                    body = cursor.getString(2),
                    ts = cursor.getLong(3),
                    outgoing = cursor.getInt(4) == 1,
                    status = cursor.getString(5),
                )
            }
        }
        return messages.asReversed()
    }

    fun updateStatus(envelopeId: String, status: String) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply { put("status", status) },
            "envelope_id = ?",
            arrayOf(envelopeId),
        )
    }

    /** Oldest local messages that never reached a mesh link. */
    fun outbox(limit: Int = 20): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        readableDatabase.query(
            TABLE,
            arrayOf("envelope_id", "sender_id", "body", "ts", "outgoing", "status"),
            "outgoing = 1 AND status = ?",
            arrayOf("waiting"),
            null,
            null,
            "ts ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += ChatMessage(
                    envelopeId = cursor.getString(0),
                    senderId = cursor.getString(1),
                    body = cursor.getString(2),
                    ts = cursor.getLong(3),
                    outgoing = cursor.getInt(4) == 1,
                    status = cursor.getString(5),
                )
            }
        }
        return messages
    }

    /**
     * Re-keys a waiting message after a successful retry. The envelope id is the
     * mesh dedupe key, so the row must carry the id that was actually sent.
     */
    fun replaceEnvelope(oldEnvelopeId: String, newEnvelopeId: String, status: String): Boolean =
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put("envelope_id", newEnvelopeId)
                put("status", status)
            },
            "envelope_id = ?",
            arrayOf(oldEnvelopeId),
        ) > 0

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
