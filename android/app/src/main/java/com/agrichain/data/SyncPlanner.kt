package com.agrichain.data

import com.agrichain.core.Event

object SyncPlanner {

    fun streamFor(type: String): String = if (type == "GPS") "trace" else "main"

    /**
     * Orders local events on one chain so each follows its prevHash starting after [serverHead].
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

    /**
     * Orders v2 per-(actorId, stream) chains inside one batch, keeping REGISTER and BIND
     * chains before box GPS/TAMPER chains without breaking intra-chain prevHash order.
     */
    fun orderV2(
        batchEvents: List<Event>,
        serverEventIds: Set<String>,
        chainHeads: Map<Pair<String, String>, String>,
    ): List<Event> {
        val remaining = batchEvents
            .distinctBy { it.eventId }
            .filter { it.eventId !in serverEventIds }
        if (remaining.isEmpty()) return emptyList()

        return remaining
            .groupBy { it.actorId to streamFor(it.type) }
            .entries
            .sortedWith(
                compareBy(
                    { (_, list) -> list.minOf { priority(it.type) } },
                    { (_, list) -> list.minOf { it.ts } },
                )
            )
            .flatMap { (chainKey, list) ->
                order(list, serverEventIds, chainHeads[chainKey].orEmpty())
            }
    }

    private fun priority(type: String): Int = when (type) {
        "REGISTER" -> 0
        "BIND" -> 1
        "TRANSFER", "BARTER", "DELIVER" -> 2
        "TAMPER" -> 3
        "GPS" -> 4
        else -> 5
    }
}
