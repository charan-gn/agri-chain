package com.agrichain.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class VectorsTest {

    private fun loadSuite(): JSONObject {
        val candidates = listOf(
            File("contracts/vectors.json"),
            File("../contracts/vectors.json"),
            File("../../contracts/vectors.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate contracts/vectors.json from ${File(".").absolutePath}")
        return JSONObject(file.readText(StandardCharsets.UTF_8))
    }

    private fun toKotlinValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> linkedMapOf<String, Any?>().apply {
            value.keys().forEach { key -> put(key, toKotlinValue(value.get(key))) }
        }
        is JSONArray -> (0 until value.length()).map { idx -> toKotlinValue(value.get(idx)) }
        else -> value
    }

    @Test
    fun `canonical vectors match contracts vectors json`() {
        val suite = loadSuite()
        val vectors = suite.getJSONArray("vectors")
        assertTrue("Expected >= 12 vectors", vectors.length() >= 12)

        for (i in 0 until vectors.length()) {
            val item = vectors.getJSONObject(i)
            val name = item.getString("name")
            @Suppress("UNCHECKED_CAST")
            val eventFields = toKotlinValue(item.getJSONObject("event")) as Map<String, Any?>
            val expectedCanonical = item.getString("canonical")
            val expectedSha256 = item.getString("sha256")

            val actualCanonical = EventCodec.canonical(eventFields)
            assertEquals("canonical mismatch for $name", expectedCanonical, actualCanonical)

            val actualSha256 = EventCodec.sha256Hex(actualCanonical.toByteArray(StandardCharsets.UTF_8))
            assertEquals("sha256 mismatch for $name", expectedSha256, actualSha256)
        }
    }

    @Test
    fun `reject cases throw IllegalArgumentException`() {
        val suite = loadSuite()
        val reject = suite.getJSONArray("reject")
        assertTrue(reject.length() >= 1)

        for (i in 0 until reject.length()) {
            val item = reject.getJSONObject(i)
            val name = item.getString("name")
            val value = toKotlinValue(item.get("value"))
            assertThrows("Expected rejection for $name", IllegalArgumentException::class.java) {
                CanonicalJson.encode(value)
            }
        }
    }

    @Test
    fun `unpaired surrogates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.encode("\uD800")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.encode("\uDC00")
        }
    }
}
