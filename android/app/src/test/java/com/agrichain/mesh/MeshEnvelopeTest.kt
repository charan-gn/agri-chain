package com.agrichain.mesh

import com.agrichain.core.Event
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshEnvelopeTest {

    private fun event(id: String, hash: String) = Event(
        eventId = id,
        batchId = "22222222-2222-4222-8222-222222222222",
        type = "GPS",
        actorId = "box:55555555-5555-4555-8555-555555555555",
        ts = 1764000000000,
        lat = "17.385044",
        lon = "78.486671",
        data = mapOf("accuracyM" to 5),
        prevHash = "aa",
        hash = hash,
        sig = "sig",
    )

    private fun envelope(events: List<Event>) = MeshEnvelope(
        MeshEnvelope.VERSION,
        MeshEnvelope.KIND_EVENTS,
        "phone-a",
        1764000001000,
        events,
    )

    @Test
    fun `envelope round trips`() {
        val original = envelope(listOf(event("e1", "bb"), event("e2", "cc")))

        val parsed = MeshEnvelope.parse(original.encode())

        assertEquals(original, parsed)
    }

    @Test
    fun `rejects wrong version kind and empty events`() {
        val json = envelope(listOf(event("e1", "bb"))).toJson()

        assertNull(MeshEnvelope.fromJson(JSONObject(json.toString()).put("v", 2)))
        assertNull(MeshEnvelope.fromJson(JSONObject(json.toString()).put("kind", "OFFERS")))
        assertNull(
            MeshEnvelope.fromJson(
                JSONObject(json.toString()).put("events", org.json.JSONArray())
            )
        )
    }

    @Test
    fun `rejects an envelope with a malformed event`() {
        val json = envelope(listOf(event("e1", "bb"))).toJson()
        json.getJSONArray("events").getJSONObject(0).remove("hash")

        assertNull(MeshEnvelope.fromJson(json))
    }

    @Test
    fun `relay dedupes by event id`() {
        val sink = FakeSink()
        val relay = MeshRelay(sink)
        val batch = envelope(listOf(event("e1", "bb"), event("e2", "cc")))

        val first = relay.accept(batch)
        val second = relay.accept(batch)

        assertEquals(MeshResult(accepted = 2, duplicates = 0), first)
        assertEquals(MeshResult(accepted = 0, duplicates = 2), second)
        assertEquals(setOf("e1", "e2"), sink.seen)
    }

    private class FakeSink : EventSink {
        val seen = mutableSetOf<String>()

        override fun enqueue(event: Event): Boolean = seen.add(event.eventId)
    }
}
