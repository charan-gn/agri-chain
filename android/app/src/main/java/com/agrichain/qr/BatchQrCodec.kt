package com.agrichain.qr

import java.util.UUID

/**
 * Batch QR payloads. v3 batches are 24-character message ids, while the first
 * two prototypes used UUIDv4 codes; both keep the same `agri://batch/` prefix
 * so old printed labels still parse.
 */
object BatchQrCodec {

    private const val PREFIX = "agri://batch/"
    private val UUID_V4 = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    )
    private val V3_HEX = Regex("^[0-9a-fA-F]{24}$")

    fun payload(batchId: String): String {
        require(V3_HEX.matches(batchId) || UUID_V4.matches(batchId)) {
            "batchId must be a 24-hex v3 id or a UUIDv4"
        }
        return PREFIX + batchId.lowercase()
    }

    fun parse(payload: String): String? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val batchId = trimmed.removePrefix(PREFIX)
        return when {
            V3_HEX.matches(batchId) -> batchId.lowercase()
            UUID_V4.matches(batchId) -> batchId.also { UUID.fromString(it) }
            else -> null
        }
    }
}
