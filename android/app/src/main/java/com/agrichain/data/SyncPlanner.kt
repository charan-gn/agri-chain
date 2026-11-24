package com.agrichain.data

import com.agrichain.core.Event

object SyncPlanner {

    /**
     * Orders local events so each one follows its prevHash, starting after the
     * server head. Events already on the server are dropped. Anything that does
     * not chain (for example a box log that starts at a missing REGISTER) is
     * appended by timestamp, oldest first, and left for the server to judge.
     */
    fun order(
        batchEvents: List<Event>,
        serverEventIds: Set<String>,
        serverHead: String,
    ): List<Event> {
        val remaining = batchEvents
            .distinctBy { it.eventId }
            .filter { it.eventId !in serverEventIds }
        if (remaining.isEmpty()) return emptyList()

        val byPrev = remaining.groupBy { it.prevHash }
        val ordered = mutableListOf<Event>()
        val used = mutableSetOf<String>()
        var cursor = serverHead

        while (true) {
            val next = byPrev[cursor]
                .orEmpty()
                .filter { it.eventId !in used }
                .minByOrNull { it.ts }
                ?: break
            used += next.eventId
            ordered += next
            cursor = next.hash
        }

        return ordered + remaining
            .filter { it.eventId !in used }
            .sortedBy { it.ts }
    }
}
