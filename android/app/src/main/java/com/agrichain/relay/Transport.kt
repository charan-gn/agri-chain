package com.agrichain.relay

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * v3 transport seam. Every transport fragments internally, so the relay router
 * only ever sees whole envelopes: `ttl(1) || signed message`.
 *
 * The interface deliberately mirrors the P4 guide so Wi-Fi, BLE, SMS, LoRa and
 * the JVM simulator are interchangeable.
 */
enum class TransportState {
    STOPPED,
    STARTING,
    RUNNING,
    DEGRADED,
    ERROR,
}

data class LinkInfo(
    val linkId: String,
    val transportId: String,
    val peerId: String? = null,
    val peerName: String? = null,
    /** True for SMS/LoRa links where re-offering pending traffic must be paced. */
    val slow: Boolean = false,
    val rssi: Int? = null,
)

data class Incoming(
    val envelope: ByteArray,
    val linkId: String? = null,
    val transportId: String = "",
    val receivedAt: Long = System.currentTimeMillis(),
)

sealed interface SendResult {
    data class Sent(val links: Int) : SendResult
    data class NoLink(val reason: String) : SendResult
    data class Failed(val reason: String, val retryable: Boolean = true) : SendResult
}

interface Transport {
    val id: String
    val meshLinks: Boolean
    val maxFrame: Int
    val state: StateFlow<TransportState>
    val links: StateFlow<Set<LinkInfo>>
    val incoming: Flow<Incoming>

    suspend fun start()
    suspend fun stop()

    /** [linkId] null means "every link this transport currently has". */
    suspend fun send(linkId: String?, envelope: ByteArray): SendResult
}

/** The relay only needs these three primitives from an identity. */
interface V3Signer {
    val actorId: ByteArray
    val publicKey: ByteArray

    /** Raw 64-byte r||s over the supplied unsigned message bytes. */
    fun sign(unsigned: ByteArray): ByteArray
}

data class RelayConfig(
    val nick: String = "phone",
    val caps: Int = 0,
    val meshTtl: Int = 7,
    val loraTtl: Int = 3,
    val maxSeen: Int = 10_000,
    val seenTtlMs: Long = 24 * 60 * 60 * 1000L,
    val maxPending: Int = 500,
    val pendingTtlMs: Long = 72 * 60 * 60 * 1000L,
    val maxListings: Int = 200,
    val relayJitterMs: IntRange = 20..120,
    val slowLinkPacingMs: Long = 2_000,
    val unverifiedPerMinute: Int = 30,
    val smsEnabled: Boolean = false,
    val smsGatewayNumber: String? = null,
    val smsFallbackAfterMs: Long = 20_000,
    val smsMaxParts: Int = 6,
    val loraMinGapMs: Long = 3_000,
    val gatewayCapsBit: Int = 0x10,
)
