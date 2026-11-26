package com.agrichain.mesh

import kotlin.random.Random

/** Kinds of traffic the flood controller knows about. */
enum class RelayTraffic {
    /** Signed ledger events: degree-aware broadcast flood. */
    EVENTS,
    /** Signed ACK: directed traffic, relay promptly and reliably. */
    ACK,
    /** Signed local chat: broadcast flood on free local links only. */
    CHAT,
    /** Large fragmented media frame; clamped harder in dense meshes. */
    FRAGMENT,
    /** Link-local control frame: never leaves this hop. */
    LINK_LOCAL,
}

data class RelayDecision(
    val shouldRelay: Boolean,
    val newTtl: Int,
    val delayMs: Int,
)

/**
 * Clean-room Kotlin port of BitChat's relay flood-control policy (the Swift
 * `RelayController.decide` behaviour, not its code). AgriChain keeps its own
 * JSON envelope, P-256 signatures and Android transports; only the policy is
 * borrowed. Jitter is returned here so the engine can stagger a fan-out and
 * let duplicate suppression win in dense meshes.
 */
object RelayPolicy {
    const val DEFAULT_TTL = 7
    const val HIGH_DEGREE_THRESHOLD = 6

    private const val FRAGMENT_TTL_SPARSE = 7
    private const val FRAGMENT_TTL_DENSE = 5
    private const val FRAGMENT_MIN_DELAY = 8
    private const val FRAGMENT_MAX_DELAY = 25
    private const val DIRECTED_MIN_DELAY = 20
    private const val DIRECTED_MAX_DELAY = 60

    fun decide(
        ttl: Int,
        traffic: RelayTraffic,
        degree: Int,
        senderIsSelf: Boolean = false,
        recipientIsSelf: Boolean = false,
        maxTtl: Int = DEFAULT_TTL,
        random: Random = Random.Default,
    ): RelayDecision {
        val cap = minOf(ttl, maxTtl)
        if (traffic == RelayTraffic.LINK_LOCAL) {
            return RelayDecision(false, cap, 0)
        }
        if (cap <= 1 || senderIsSelf || recipientIsSelf) {
            return RelayDecision(false, cap, 0)
        }
        if (traffic == RelayTraffic.ACK) {
            return RelayDecision(
                true,
                cap - 1,
                random.nextInt(DIRECTED_MIN_DELAY, DIRECTED_MAX_DELAY + 1),
            )
        }
        if (traffic == RelayTraffic.FRAGMENT) {
            val fragmentCap = minOf(cap, if (degree >= HIGH_DEGREE_THRESHOLD) {
                FRAGMENT_TTL_DENSE
            } else {
                FRAGMENT_TTL_SPARSE
            })
            if (fragmentCap <= 1) return RelayDecision(false, fragmentCap, 0)
            return RelayDecision(
                true,
                fragmentCap - 1,
                random.nextInt(FRAGMENT_MIN_DELAY, FRAGMENT_MAX_DELAY + 1),
            )
        }

        // EVENTS and CHAT use the broadcast budget. Dense meshes clamp hard;
        // thin chains keep the full incoming depth because each hop is cheap.
        val limit = when {
            degree >= HIGH_DEGREE_THRESHOLD -> maxOf(2, minOf(cap, 5))
            degree <= 2 -> cap
            else -> maxOf(2, minOf(cap, 6))
        }
        val delay = when (degree) {
            in 0..2 -> random.nextInt(10, 41)
            in 3..5 -> random.nextInt(60, 151)
            in 6..9 -> random.nextInt(80, 181)
            else -> random.nextInt(100, 221)
        }
        return RelayDecision(true, limit - 1, delay)
    }
}
