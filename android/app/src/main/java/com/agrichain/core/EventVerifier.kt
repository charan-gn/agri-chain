package com.agrichain.core

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Independent verifier used by the buyer (H3). The ledger's `verified` flag is only
 * a hint; the phone recomputes the canonical hash and checks the P-256 signature.
 */
object EventVerifier {

    fun canonical(event: Event): String = CanonicalJson.encode(
        linkedMapOf<String, Any?>(
            "eventId" to event.eventId,
            "batchId" to event.batchId,
            "type" to event.type,
            "actorId" to event.actorId,
            "ts" to event.ts,
            "lat" to event.lat,
            "lon" to event.lon,
            "data" to event.data,
            "prevHash" to event.prevHash,
        )
    )

    fun hashMatches(event: Event): Boolean =
        EventCodec.sha256Hex(canonical(event).toByteArray(Charsets.UTF_8)) == event.hash

    fun signatureMatches(event: Event, publicKeySpkiBase64: String): Boolean =
        verifyEcdsa(
            canonical(event).toByteArray(Charsets.UTF_8),
            event.sig,
            publicKeySpkiBase64,
        )

    fun verifyEcdsa(bytes: ByteArray, signatureBase64: String, publicKeySpkiBase64: String): Boolean {
        val signatureBytes = runCatching {
            Base64.getDecoder().decode(signatureBase64)
        }.getOrNull() ?: return false
        val keyBytes = runCatching {
            Base64.getDecoder().decode(publicKeySpkiBase64)
        }.getOrNull() ?: return false
        return runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(bytes)
            verifier.verify(signatureBytes)
        }.getOrDefault(false)
    }
}
