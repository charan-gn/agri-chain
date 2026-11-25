package com.agrichain.batch

import com.agrichain.core.Event
import com.agrichain.core.Signer

/**
 * `BIND` is signed by the current batch owner on its own `main` chain. The latest
 * BIND wins; `boxId=null` unbinds. The box does not need the owner's head.
 */
object BindEvent {

    private val BOX_ACTOR = Regex("^box:[0-9a-fA-F-]{36}$")

    fun create(
        batchId: String,
        boxId: String?,
        signer: Signer,
        prevHash: String,
        ts: Long = System.currentTimeMillis(),
        expiresAt: Long? = null,
    ): Event {
        require(prevHash.isEmpty() || prevHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "prevHash must be empty or 64 lowercase hex characters"
        }
        require(boxId == null || BOX_ACTOR.matches(boxId)) {
            "Box ID must look like box:<uuid>"
        }
        val data = linkedMapOf<String, Any?>("boxId" to boxId)
        if (expiresAt != null) data["expiresAt"] = expiresAt
        return Event.create(
            batchId = batchId,
            type = "BIND",
            data = data,
            signer = signer,
            ts = ts,
            prevHash = prevHash,
        )
    }
}
