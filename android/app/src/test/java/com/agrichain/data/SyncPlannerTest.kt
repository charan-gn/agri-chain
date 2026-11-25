package com.agrichain.data

import com.agrichain.core.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlannerTest {

    private val batchId = "22222222-2222-4222-8222-222222222222"

    private fun event(type: String, prevHash: String, hash: String, ts: Long) = Event(
        eventId = "event-$hash",
        batchId = batchId,
        type = type,
        actorId = "farmer:11111111-1111-4111-8111-111111111111",
        ts = ts,
        lat = null,
        lon = null,
        data = emptyMap(),
        prevHash = prevHash,
        hash = hash,
        sig = "sig",
    )

    @Test
    fun `orders a chain regardless of input order`() {
        val register = event("REGISTER", "", "r", 1)
        val firstGps = event("GPS", "r", "a", 2)
        val secondGps = event("GPS", "a", "b", 3)

        val ordered = SyncPlanner.order(
            listOf(secondGps, register, firstGps),
            emptySet(),
            "",
        )

        assertEquals(listOf("r", "a", "b"), ordered.map { it.hash })
    }

    @Test
    fun `starts after the server head and skips stored events`() {
        val register = event("REGISTER", "", "r", 1)
        val firstGps = event("GPS", "r", "a", 2)
        val secondGps = event("GPS", "a", "b", 3)

        val ordered = SyncPlanner.order(
            listOf(register, firstGps, secondGps),
            setOf(register.eventId, firstGps.eventId),
            "a",
        )

        assertEquals(listOf("b"), ordered.map { it.hash })
    }

    @Test
    fun `events that do not chain are returned as orphans`() {
        val late = event("GPS", "missing-a", "a", 3)
        val early = event("GPS", "missing-b", "b", 1)

        val plan = SyncPlanner.planChain(listOf(late, early), "unknown-head")

        // v2 never mutates a signed event, so the gap blocks the chain and both
        // events are reported for re-signing instead of being uploaded out of order.
        assertEquals(emptyList<Event>(), plan.ordered)
        assertEquals(listOf("b", "a"), plan.orphans.map { it.hash })
    }

    @Test
    fun `returns nothing when the server already has everything`() {
        val register = event("REGISTER", "", "r", 1)

        assertEquals(
            emptyList<Event>(),
            SyncPlanner.order(listOf(register), setOf(register.eventId), "r"),
        )
    }
}
