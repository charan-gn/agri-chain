package com.agrichain.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.charset.StandardCharsets

class CanonicalVectorsTest {

    private fun loadVectors(): JSONObject {
        val stream = javaClass.getResourceAsStream("/event-vectors.json")
            ?: error("contracts/event-vectors.json is not on the test classpath")
        return JSONObject(stream.bufferedReader(StandardCharsets.UTF_8).readText())
    }

    private fun convert(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> linkedMapOf<String, Any?>().apply {
            value.keys().forEach { key -> put(key, convert(value.get(key))) }
        }
        is JSONArray -> (0 until value.length()).map { index -> convert(value.get(index)) }
        is Int, is Long, is Boolean, is String -> value
        is BigInteger -> value.longValueExact()
        is Number -> {
            val asLong = value.toLong()
            require(asLong.toDouble() == value.toDouble()) { "non-integer number in vectors" }
            asLong
        }
        else -> error("unsupported JSON value: ${value::class.java.name}")
    }

    @Test
    fun `all checked-in vectors match Kotlin canonical bytes`() {
        val vectors = loadVectors().getJSONArray("vectors")
        assertTrue("expected at least 12 vectors", vectors.length() >= 12)
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val name = vector.getString("name")
            val canonical = CanonicalJson.encode(convert(vector.get("value")))
            assertEquals(name, vector.getString("canonical"), canonical)
            assertEquals(
                name,
                vector.getString("sha256"),
                EventCodec.sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8)),
            )
        }
    }

    @Test
    fun `non-ascii is raw utf8 and control characters use escapes`() {
        assertEquals(
            """{"crop":"వరి 🌾"}""",
            CanonicalJson.encode(mapOf("crop" to "వరి 🌾")),
        )
        assertEquals(
            """{"note":"a\nb\tc\r\bd\fe\u0001"}""",
            CanonicalJson.encode(mapOf("note" to "a\nb\tc\r\bd\u000Ce\u0001")),
        )
    }

    @Test
    fun `invalid values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.encode(mapOf("bad" to 1.5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.encode(mapOf("bad" to "\ud800"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.encode(mapOf("bad\nkey" to 1))
        }
    }
}
