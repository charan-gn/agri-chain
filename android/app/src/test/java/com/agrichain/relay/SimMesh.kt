package com.agrichain.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.yield
import java.security.interfaces.ECPrivateKey
import kotlin.random.Random

/**
 * Deterministic in-process mesh used by the P4 tests. It exercises the real
 * RelayCore and V3Codec; only radios, latency and loss are faked.
 */
class SimNetwork(
    var defaultLatencyMs: Long = 0,
    var defaultLoss: Double = 0.0,
) {
    internal data class Link(
        val linkId: String,
        val a: SimNode,
        val b: SimNode,
        val latencyMs: Long,
        val loss: Double,
    )

    internal val links = LinkedHashMap<String, Link>()

    suspend fun connect(
        a: SimNode,
        b: SimNode,
        linkId: String = "${a.id}-${b.id}",
        latencyMs: Long = defaultLatencyMs,
        loss: Double = defaultLoss,
    ): String {
        if (links.containsKey(linkId)) return linkId
        links[linkId] = Link(linkId, a, b, latencyMs, loss)
        a.transport.setLinks(a.transport.links.value + LinkInfo(
            linkId = linkId,
            transportId = a.transport.id,
            peerId = b.id,
            peerName = b.id,
        ))
        b.transport.setLinks(b.transport.links.value + LinkInfo(
            linkId = linkId,
            transportId = b.transport.id,
            peerId = a.id,
            peerName = a.id,
        ))
        // Dispatchers.Unconfined in SimNode makes the RelayCore link collector
        // run inline here, so ANNOUNCE/re-offer happens before connect returns.
        yield()
        return linkId
    }

    suspend fun disconnect(linkId: String) {
        val link = links.remove(linkId) ?: return
        link.a.transport.setLinks(link.a.transport.links.value.filter { it.linkId != linkId }.toSet())
        link.b.transport.setLinks(link.b.transport.links.value.filter { it.linkId != linkId }.toSet())
        yield()
    }

    suspend fun deliver(
        from: SimNode,
        to: SimNode,
        linkId: String,
        envelope: ByteArray,
    ): RelayOutcome {
        val link = links[linkId] ?: return RelayOutcome("", null, false, droppedReason = "no_link")
        if (link.loss > 0 && Random.nextDouble() < link.loss) {
            return RelayOutcome("", null, false, droppedReason = "lost")
        }
        if (link.latencyMs > 0) delay(link.latencyMs)
        return to.core.onEnvelope(envelope, linkId, from.transport.id)
    }
}

class SimTransport(
    override val id: String,
    val node: SimNode,
    val network: SimNetwork,
) : Transport {
    override val meshLinks: Boolean = true
    override val maxFrame: Int = V3.MAX_MESSAGE + 1
    override val state: StateFlow<TransportState> = MutableStateFlow(TransportState.RUNNING)

    private val _links = MutableStateFlow(emptySet<LinkInfo>())
    override val links: StateFlow<Set<LinkInfo>> get() = _links

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<Incoming> get() = _incoming

    var sendCount: Int = 0
        private set
    val sent: MutableList<ByteArray> = mutableListOf()

    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override suspend fun send(linkId: String?, envelope: ByteArray): SendResult {
        require(envelope.size <= maxFrame) { "simulated MTU exceeded" }
        sendCount += 1
        sent += envelope.copyOf()
        val targets = if (linkId == null) links.value.map { it.linkId } else listOf(linkId)
        if (targets.isEmpty()) return SendResult.NoLink("no links")
        var delivered = 0
        for (id in targets) {
            val link = network.links[id] ?: continue
            val target = if (link.a === node) link.b else link.a
            network.deliver(node, target, id, envelope)
            delivered += 1
        }
        return if (delivered == 0) {
            SendResult.NoLink("stale link")
        } else {
            SendResult.Sent(delivered)
        }
    }

    internal fun setLinks(value: Set<LinkInfo>) {
        _links.value = value
    }

    fun resetCounters() {
        sendCount = 0
        sent.clear()
    }
}

class SimNode(
    val id: String,
    scalar: Int,
    ledgerPubkey: ByteArray,
    network: SimNetwork,
    config: RelayConfig = RelayConfig(
        nick = id,
        relayJitterMs = 0..0,
        slowLinkPacingMs = 0,
        loraMinGapMs = 0,
    ),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
) {
    val key: ECPrivateKey = V3Bodies.scalarKey(scalar)
    val publicKey: ByteArray = V3Codec.compressedPublicKey(key)
    val actorId: ByteArray = V3Codec.actorId(publicKey)

    val transport = SimTransport("wifi", this, network)
    val core: RelayCore

    init {
        val signer = object : V3Signer {
            override val actorId get() = this@SimNode.actorId
            override val publicKey get() = this@SimNode.publicKey
            override fun sign(unsigned: ByteArray): ByteArray = V3Codec.signRaw(unsigned, key)
        }
        core = RelayCore(
            signer = signer,
            config = config,
            ledgerPubkey = ledgerPubkey,
            clock = { System.currentTimeMillis() },
            relayDelay = { },
            scope = scope,
        )
        core.attach(transport)
    }

    fun resetCounters() {
        transport.resetCounters()
    }
}
