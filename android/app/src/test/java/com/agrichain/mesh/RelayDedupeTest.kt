package com.agrichain.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDedupeTest {

    @Test
    fun `the same envelope id is only seen once`() {
        val dedupe = RelayDedupe(clock = { 1_000L })

        assertFalse(dedupe.isDuplicate("env-1"))
        assertTrue(dedupe.isDuplicate("env-1"))
        assertTrue(dedupe.contains("env-1"))
        dedupe.markProcessed("env-2")
        assertTrue(dedupe.isDuplicate("env-2"))
    }

    @Test
    fun `old ids expire`() {
        var now = 1_000L
        val dedupe = RelayDedupe(maxAgeMs = 100, clock = { now })

        assertFalse(dedupe.isDuplicate("env-1"))
        now += 101
        assertFalse(dedupe.isDuplicate("env-1"))
    }

    @Test
    fun `the cache stays bounded`() {
        val dedupe = RelayDedupe(maxCount = 8, clock = { 1_000L })

        repeat(40) { dedupe.markProcessed("env-$it") }

        assertTrue(dedupe.isDuplicate("env-39"))
        assertFalse(dedupe.isDuplicate("env-0"))
    }
}
