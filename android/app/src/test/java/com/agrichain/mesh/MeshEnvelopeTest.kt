package com.agrichain.mesh

import com.agrichain.core.Event
import com.agrichain.core.Signer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshEnvelopeTest {

    private val batchId = "22222222-2222-4222-8222-222222222222"

    private object TestSigner : Signer {
        override val actorId = "farmer:11111111-1111-4111-8111-111111111111"
        override fun sign(bytes: ByteArray): ByteArray = byteArrayOf(1, 2, 3)
    }

    private fun register() = Event.create(
        batchId = batchId,
        type = "REGISTER",
        data = mapOf("crop" to "wheat", "grade" to "A", "weightKg" to 100),
        signer = TestSigner,
        ts = 1764000000000,
        prevHash = "",
    )

    private fun gps(prevHash: String) = Event.create(
        batchId = batchId,
        type = "GPS",
        data = mapOf("accuracyM" to 5),
        signer = TestSigner,
        ts = 1764000001000,
        lat = "17.385044",
        lon = "78.486671",
        prevHash = prevHash,
    )

    @Test
    fun `compact envelope round trips and recomputes the hash`() {
        val first = register()
        val second = gps(first.hash)
        val original = MeshEnvelope.events("phone-a", listOf(first, second))

        val parsed = MeshEnvelope.parse(original.encode())

        assertEquals(MeshKind.EVENTS, parsed?.kind)
        assertEquals(original.events, parsed?.events)
        assertEquals(first.hash, parsed?.events?.get(0)?.hash)
        assertEquals("AQID", parsed?.events?.get(1)?.sig)
    }

    @Test
    fun `compact envelope is smaller than the v1 envelope`() {
        val event = register()
        val v2 = MeshEnvelope.events("phone-a", listOf(event)).encode()
        val v1 = JSONObject()
            .put("v", 1)
            .put("kind", "EVENTS")
            .put("senderId", "phone-a")
            .put("sentAt", 1764000000000)
            .put("events", JSONArray().put(event.toJsonObject()))
            .toString()

        assertTrue("v2=${v2.length} v1=${v1.length}", v2.length < v1.length)
    }

    @Test
    fun `v1 envelopes still parse`() {
        val event = register()
        val v1 = JSONObject()
            .put("v", 1)
            .put("kind", "EVENTS")
            .put("senderId", "phone-old")
            .put("sentAt", 1764000000000)
            .put("events", JSONArray().put(event.toJsonObject()))

        val parsed = MeshEnvelope.fromJson(v1)

        assertEquals(MeshKind.EVENTS, parsed?.kind)
        assertEquals(6, parsed?.ttl)
        assertEquals(listOf(event), parsed?.events)
    }

    @Test
    fun `ack envelopes round trip`() {
        val ack = MeshEnvelope.ack("phone-b", listOf("e1", "e2"))

        val parsed = MeshEnvelope.parse(ack.encode())

        assertEquals(MeshKind.ACK, parsed?.kind)
        assertEquals(listOf("e1", "e2"), parsed?.ackedEventIds)
        assertTrue(parsed?.events?.isEmpty() == true)
    }

    @Test
    fun `rejects wrong version kind and empty bodies`() {
        val json = MeshEnvelope.events("phone-a", listOf(register())).toJson()

        assertNull(MeshEnvelope.fromJson(JSONObject(json.toString()).put("v", 3)))
        assertNull(MeshEnvelope.fromJson(JSONObject(json.toString()).put("k", "X")))
        assertNull(
            MeshEnvelope.fromJson(
                JSONObject(json.toString()).put("ev", JSONArray())
            )
        )
        assertNull(
            MeshEnvelope.fromJson(
                JSONObject(json.toString())
                    .put("k", "A")
                    .put("ev", JSONArray())
                    .put("ack", JSONArray())
            )
        )
    }

    @Test
    fun `rejects an envelope with a malformed event`() {
        val json = MeshEnvelope.events("phone-a", listOf(register())).toJson()
        json.getJSONArray("ev").getJSONObject(0).remove("g")

        assertNull(MeshEnvelope.fromJson(json))
    }

    @Test
    fun `onlyEvents keeps the allowed types`() {
        val first = register()
        val second = gps(first.hash)
        val envelope = MeshEnvelope.events("phone-a", listOf(first, second))

        val filtered = envelope.onlyEvents { it.type == "REGISTER" }

        assertEquals(listOf("REGISTER"), filtered.events.map { it.type })
    }
}
