package com.agrichain.buyer

import com.agrichain.core.Chains
import com.agrichain.core.Event
import com.agrichain.core.EventVerifier
import org.json.JSONArray

data class LocalVerification(
    val valid: Boolean,
    val checkedEvents: Int,
    val checkedChains: Int,
    val missingKeys: Int,
    val errors: List<String>,
) {
    val complete: Boolean get() = missingKeys == 0 && errors.isEmpty()
}

/**
 * Buyer-side verification (H3). The ledger's `verified` flag is only a hint: this
 * recomputes every hash, checks every P-256 signature with the registered actor key,
 * walks each `(actorId, stream)` chain, and checks that GPS/TAMPER comes from a box
 * that a BIND authorized before the event.
 */
object LocalVerifier {

    private data class RawEvent(val event: Event, val chainSeq: Long)

    fun verify(eventsJson: JSONArray, keys: Map<String, String>): LocalVerification {
        val raw = (0 until eventsJson.length()).mapNotNull { index ->
            runCatching {
                val json = eventsJson.getJSONObject(index)
                RawEvent(
                    event = Event.fromJsonObject(json),
                    chainSeq = json.optLong("chainSeq", json.optLong("seq", 0)),
                )
            }.getOrNull()
        }
        if (raw.isEmpty()) {
            return LocalVerification(false, 0, 0, 0, listOf("No events to verify"))
        }

        val errors = mutableListOf<String>()
        var missingKeys = 0
        var checkedChains = 0

        val chains = raw.groupBy { Chains.chainKey(it.event) }
        for ((key, chain) in chains) {
            checkedChains++
            val ordered = chain.sortedWith(
                compareBy<RawEvent>({ it.chainSeq }, { it.event.ts }, { it.event.eventId })
            )
            var head = ""
            for (item in ordered) {
                val event = item.event
                if (event.prevHash != head) {
                    errors += "${event.type} ${event.eventId.take(8)}: prevHash does not match"
                    break
                }
                if (!EventVerifier.hashMatches(event)) {
                    errors += "${event.type} ${event.eventId.take(8)}: hash mismatch"
                    break
                }
                val publicKey = keys[event.actorId]
                if (publicKey == null) {
                    missingKeys++
                } else if (!EventVerifier.signatureMatches(event, publicKey)) {
                    errors += "${event.type} ${event.eventId.take(8)}: bad signature"
                    break
                }
                head = event.hash
            }
            if (key.second == Chains.MAIN && key.first.startsWith("farmer:")) {
                // REGISTER must be the first main event for its batch.
                val first = ordered.firstOrNull()?.event
                if (first != null && first.type != "REGISTER") {
                    errors += "farmer main chain does not start with REGISTER"
                }
            }
        }

        val binds = raw.map { it.event }
            .filter { it.type == "BIND" }
            .sortedBy { it.ts }
        for (item in raw) {
            val event = item.event
            when (event.type) {
                "REGISTER" -> if (!event.actorId.startsWith("farmer:")) {
                    errors += "REGISTER signed by ${event.actorId.take(12)}… is not a farmer"
                }
                "GPS", "TAMPER" -> {
                    if (!event.actorId.startsWith("box:")) {
                        errors += "${event.type} signed by a non-box actor"
                        continue
                    }
                    // The latest BIND before the event wins; boxId=null unbinds.
                    val latest = binds.lastOrNull { it.ts <= event.ts }
                    val expiresAt = (latest?.data?.get("expiresAt") as? Number)?.toLong()
                    val authorized = latest?.data?.get("boxId")?.toString() == event.actorId &&
                        (expiresAt == null || expiresAt > event.ts)
                    if (!authorized) {
                        errors += "${event.type} from ${event.actorId.take(12)}… has no active BIND"
                    }
                }
            }
        }

        return LocalVerification(
            valid = errors.isEmpty(),
            checkedEvents = raw.size,
            checkedChains = checkedChains,
            missingKeys = missingKeys,
            errors = errors.distinct().take(6),
        )
    }
}
