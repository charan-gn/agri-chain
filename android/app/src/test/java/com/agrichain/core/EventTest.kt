package com.agrichain.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets

class EventTest {

    private val fields = linkedMapOf<String, Any?>(
        "eventId" to "11111111-1111-4111-8111-111111111111",
        "batchId" to "22222222-2222-4222-8222-222222222222",
        "type" to "REGISTER",
        "actorId" to "farmer:33333333-3333-4333-8333-333333333333",
        "ts" to 1764000000000L,
        "lat" to null,
        "lon" to null,
        "data" to linkedMapOf(
            "crop" to "wheat",
            "grade" to "A",
            "weightKg" to 100,
        ),
        "prevHash" to "",
    )

    private val expectedCanonical =
        """{"actorId":"farmer:33333333-3333-4333-8333-333333333333","batchId":"22222222-2222-4222-8222-222222222222","data":{"crop":"wheat","grade":"A","weightKg":100},"eventId":"11111111-1111-4111-8111-111111111111","lat":null,"lon":null,"prevHash":"","ts":1764000000000,"type":"REGISTER"}"""

    @Test
    fun `canonical and hash match the README test vector`() {
        val canonical = EventCodec.canonical(fields)
        assertEquals(expectedCanonical, canonical)
        assertEquals(
            "cfad1baad5f9915b39aba2d330ca28b79feb12706d275fc7533c376361ebed80",
            EventCodec.sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    @Test
    fun `data key order does not change the hash`() {
        val reordered = fields.toMutableMap()
        reordered["data"] = linkedMapOf(
            "weightKg" to 100,
            "crop" to "wheat",
            "grade" to "A",
        )
        assertEquals(
            EventCodec.sha256Hex(expectedCanonical.toByteArray(StandardCharsets.UTF_8)),
            EventCodec.sha256Hex(EventCodec.canonical(reordered).toByteArray(StandardCharsets.UTF_8)),
        )
    }

    @Test
    fun `floats are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.encode(mapOf("bad" to 1.5))
        }
    }
}
