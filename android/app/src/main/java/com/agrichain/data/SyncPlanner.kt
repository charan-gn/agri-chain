package com.agrichain.data

import com.agrichain.core.Chains
import com.agrichain.core.Event

data class SyncPlan(
    val ordered: List<Event>,
    val orphans: List<Event>,
) {
    val isEmpty: Boolean get() = ordered.isEmpty() && orphans.isEmpty()
}

object SyncPlanner {

    fun streamFor(type: String): String = Chains.streamFor(type)

    /**
     * Orders one chain so each event follows its prevHash starting after [serverHead].
     * Events that do not connect are returned as orphans: the contract says to create
     * a new event (new eventId, new ts, new prevHash), never to mutate the signed one.
     */
    fun planChain(events: List<Event>, serverHead: String): SyncPlan {
        val distinct = events.distinctBy { it.eventId }
        if (distinct.isEmpty()) return SyncPlan(emptyList(), emptyList())
        val ordered = Chains.connected(distinct, serverHead)
        val used = ordered.mapTo(mutableSetOf()) { it.eventId }
        return SyncPlan(
            ordered = ordered,
            orphans = distinct.filter { it.eventId !in used }.sortedBy { it.ts },
        )
    }

    fun order(
        batchEvents: List<Event>,
        serverEventIds: Set<String>,
        serverHead: String,
    ): List<Event> {
        val remaining = batchEvents
            .distinctBy { it.eventId }
            .filter { it.eventId !in serverEventIds }
        return planChain(remaining, serverHead).ordered
    }

    /**
     * Orders v2 per-(actorId, stream) chains inside one batch, keeping REGISTER and BIND
     * chains before box GPS/TAMPER chains without breaking intra-chain prevHash order.
     */
    fun planV2(
        batchEvents: List<Event>,
        serverEventIds: Set<String>,
        chainHeads: Map<Pair<String, String>, String>,
    ): SyncPlan {
        val remaining = batchEvents
            .distinctBy { it.eventId }
            .filter { it.eventId !in serverEventIds }
        if (remaining.isEmpty()) return SyncPlan(emptyList(), emptyList())

        val ordered = mutableListOf<Event>()
        val orphans = mutableListOf<Event>()
        remaining
            .groupBy { Chains.chainKey(it) }
            .entries
            .sortedWith(
                compareBy(
                    { (_, list) -> list.minOf { priority(it.type) } },
                    { (_, list) -> list.minOf { it.ts } },
                )
            )
            .forEach { (chainKey, list) ->
                val plan = planChain(list, chainHeads[chainKey].orEmpty())
                ordered += plan.ordered
                orphans += plan.orphans
            }
        return SyncPlan(ordered, orphans)
    }

    fun orderV2(
        batchEvents: List<Event>,
        serverEventIds: Set<String>,
        chainHeads: Map<Pair<String, String>, String>,
    ): List<Event> = planV2(batchEvents, serverEventIds, chainHeads).ordered

    private fun priority(type: String): Int = when (type) {
        "REGISTER" -> 0
        "BIND" -> 1
        "TRANSFER", "BARTER", "DELIVER" -> 2
        "TAMPER" -> 3
        "GPS" -> 4
        else -> 5
    }
}
