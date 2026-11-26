package com.agrichain.mesh

/**
 * Clean-room Kotlin port of BitChat's bounded, time-expired deduplicator.
 * Older entries fall out by age or when the cache exceeds [maxCount]; a trim
 * drops to 75% of the cap so it does not evict on every message. Used for
 * envelope ids in [RelayEngine]; event ids remain in the SQLite outbox.
 */
class RelayDedupe(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxCount: Int = DEFAULT_MAX_COUNT,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun isDuplicate(id: String): Boolean {
        cleanup()
        if (entries.any { it.id == id }) return true
        add(id)
        return false
    }

    @Synchronized
    fun markProcessed(id: String) {
        cleanup()
        if (entries.none { it.id == id }) add(id)
    }

    @Synchronized
    fun contains(id: String): Boolean {
        cleanup()
        return entries.any { it.id == id }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun add(id: String) {
        entries.addLast(Entry(id, clock()))
        if (entries.size > maxCount) {
            val target = (maxCount * 3) / 4
            repeat(entries.size - target) { entries.removeFirst() }
        }
    }

    private fun cleanup() {
        val cutoff = clock() - maxAgeMs
        while (entries.isNotEmpty() && entries.first().timestamp < cutoff) {
            entries.removeFirst()
        }
    }

    private data class Entry(val id: String, val timestamp: Long)

    companion object {
        const val DEFAULT_MAX_AGE_MS = 10 * 60_000L
        const val DEFAULT_MAX_COUNT = 512
    }
}
