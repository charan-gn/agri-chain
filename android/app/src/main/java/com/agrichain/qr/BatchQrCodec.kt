package com.agrichain.qr

import java.util.UUID

object BatchQrCodec {

    private const val PREFIX = "agri://batch/"
    private val UUID_V4 = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    )

    fun payload(batchId: String): String {
        require(UUID_V4.matches(batchId)) { "batchId must be a UUIDv4" }
        return PREFIX + batchId
    }

    fun parse(payload: String): String? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val batchId = trimmed.removePrefix(PREFIX)
        if (!UUID_V4.matches(batchId)) return null
        return batchId.also { UUID.fromString(it) }
    }
}
