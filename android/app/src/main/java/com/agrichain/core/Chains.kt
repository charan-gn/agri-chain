package com.agrichain.core

/**
 * v2 chain key: `(batchId, actorId, stream)`. GPS is the box `trace` stream;
 * every other event is on `main`. Each chain has its own genesis (`prevHash=""`).
 */
object Chains {

    const val MAIN = "main"
    const val TRACE = "trace"

    fun streamFor(type: String): String = if (type == "GPS") TRACE else MAIN

    fun chainKey(event: Event): Pair<String, String> = event.actorId to streamFor(event.type)

    fun eventsFor(events: List<Event>, actorId: String, stream: String): List<Event> =
        events.filter { it.actorId == actorId && streamFor(it.type) == stream }

    fun head(events: List<Event>, actorId: String, stream: String, fallback: String = ""): String =
        eventsFor(events, actorId, stream).lastOrNull()?.hash ?: fallback

    fun heads(events: List<Event>): Map<Pair<String, String>, String> =
        events.groupBy { chainKey(it) }
            .mapValues { (_, chain) -> chain.last().hash }

    /**
     * Returns the prefix of [events] that connects to [serverHead] through prevHash.
     * Events after a gap are orphans: uploading them would only produce prev_mismatch,
     * so the caller must re-sign them with a new prevHash (new eventId, new ts).
     */
    fun connected(events: List<Event>, serverHead: String): List<Event> {
        val remaining = events.distinctBy { it.eventId }
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
        return ordered
    }

    fun orphans(events: List<Event>, serverHead: String): List<Event> {
        val connected = connected(events, serverHead).mapTo(mutableSetOf()) { it.eventId }
        return events.filter { it.eventId !in connected }
    }
}
