package com.agrichain.mesh

import com.agrichain.core.Event
import com.agrichain.core.Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * JVM proof of the relay: virtual nodes, fake radio/SMS/LoRa links with loss and
 * reordering, and an idempotent fake ledger.
 */
class RelaySimulatorTest {

    private val batchId = "22222222-2222-4222-8222-222222222222"

    private object SignerA : Signer {
        override val actorId = "farmer:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        override fun sign(bytes: ByteArray): ByteArray = byteArrayOf(1)
    }

    private fun events(): List<Event> {
        val register = Event.create(
            batchId = batchId,
            type = "REGISTER",
            data = mapOf("crop" to "wheat", "grade" to "A", "weightKg" to 100),
            signer = SignerA,
            ts = 1_000,
            prevHash = "",
        )
        val tamper = Event.create(
            batchId = batchId,
            type = "TAMPER",
            data = mapOf("kind" to "light", "detail" to "lid opened"),
            signer = SignerA,
            ts = 2_000,
            prevHash = register.hash,
        )
        val gps = Event.create(
            batchId = batchId,
            type = "GPS",
            data = mapOf("accuracyM" to 5),
            signer = SignerA,
            ts = 3_000,
            lat = "17.385044",
            lon = "78.486671",
            prevHash = tamper.hash,
        )
        return listOf(register, tamper, gps)
    }

    private class SimStore : RelayStore {
        val outbox = LinkedHashMap<String, Event>()
        val seen = mutableSetOf<String>()
        val synced = mutableSetOf<String>()
        val seenEnvelopes = mutableSetOf<String>()

        override fun hasSeen(eventId: String) = eventId in seen
        override fun markSeen(eventIds: Collection<String>) {
            seen += eventIds
        }

        override fun enqueue(event: Event): Boolean =
            outbox.putIfAbsent(event.eventId, event) == null

        override fun pending(): List<Event> =
            outbox.values.filter { it.eventId !in synced }.sortedBy { it.ts }

        override fun markSynced(eventIds: Collection<String>) {
            eventIds.forEach { if (it in outbox) synced += it }
        }

        override fun pendingCount(): Int = pending().size

        override fun hasSeenEnvelope(envelopeId: String) = envelopeId in seenEnvelopes
        override fun markSeenEnvelope(envelopeId: String) {
            seenEnvelopes += envelopeId
        }
    }

    private class FakeLedger : LedgerGateway {
        val events = LinkedHashMap<String, Event>()
        private val byBatch = mutableMapOf<String, MutableList<Event>>()
        var online = true

        override fun available(): Boolean = online

        override fun upload(events: List<Event>): UploadResult {
            if (!online) return UploadResult(emptyList(), events.map { it.eventId }, "offline")
            val accepted = mutableListOf<String>()
            val rejected = mutableListOf<String>()
            for (event in events.sortedWith(compareBy({ it.ts }, { it.eventId }))) {
                if (this.events.containsKey(event.eventId)) {
                    accepted += event.eventId
                    continue
                }
                val head = byBatch[event.batchId]?.lastOrNull()?.hash.orEmpty()
                val canAppend = if (head.isEmpty()) {
                    event.prevHash.isEmpty() &&
                        (event.type == "REGISTER" ||
                            (event.batchId == "market" && event.type == "PRICE"))
                } else {
                    event.type != "REGISTER" && event.prevHash == head
                }
                if (!canAppend) {
                    rejected += event.eventId
                    continue
                }
                this.events[event.eventId] = event
                byBatch.getOrPut(event.batchId) { mutableListOf() } += event
                accepted += event.eventId
            }
            return UploadResult(accepted, rejected)
        }
    }

    private class SimNetwork {
        private data class Radio(val from: String, val to: String, val envelope: MeshEnvelope)
        private data class Sms(
            val from: String,
            val to: String,
            val chunk: String,
            val deliverAt: Long,
        )

        val engines = mutableMapOf<String, RelayEngine>()
        val stores = mutableMapOf<String, SimStore>()
        val transports = mutableMapOf<String, Transport>()
        val smsTransports = mutableMapOf<String, SimSmsTransport>()
        val numbers = mutableMapOf<String, String>()
        val nodeByNumber = mutableMapOf<String, String>()
        private val links = mutableMapOf<String, MutableSet<String>>()
        private val inboxes = mutableMapOf<String, SmsReassembler>()
        private val radioQueue = ArrayDeque<Radio>()
        private val smsQueue = ArrayDeque<Sms>()

        var radioLoss: (String, String) -> Boolean = { _, _ -> false }
        var smsLoss: (String, String, String) -> Boolean = { _, _, _ -> false }
        var shuffleSms = false
        var smsDelay = 0L
        private var clock = 0L

        fun add(node: String, engine: RelayEngine, store: SimStore, transport: Transport) {
            engines[node] = engine
            stores[node] = store
            transports[node] = transport
            inboxes[node] = SmsReassembler()
        }

        fun addSms(node: String, number: String, transport: SimSmsTransport) {
            numbers[node] = number
            nodeByNumber[number] = node
            smsTransports[node] = transport
        }

        fun connectRadio(first: String, second: String) {
            links.getOrPut(first) { mutableSetOf() } += second
            links.getOrPut(second) { mutableSetOf() } += first
        }

        fun radioSend(from: String, envelope: MeshEnvelope) {
            links[from].orEmpty().forEach { to ->
                if (!radioLoss(from, to)) radioQueue.addLast(Radio(from, to, envelope))
            }
        }

        fun smsSend(from: String, to: String, chunks: List<String>) {
            val target = nodeByNumber[to] ?: return
            chunks.forEach { chunk ->
                if (!smsLoss(from, target, chunk)) {
                    smsQueue.addLast(Sms(from, target, chunk, clock + smsDelay))
                }
            }
        }

        fun deliverAll(maxSteps: Int = 5_000) {
            var steps = 0
            while ((radioQueue.isNotEmpty() || smsQueue.isNotEmpty()) && steps++ < maxSteps) {
                clock++
                if (radioQueue.isNotEmpty()) {
                    val next = radioQueue.removeFirst()
                    engines.getValue(next.to).receive(next.envelope)
                    continue
                }
                val due = smsQueue.filter { it.deliverAt <= clock }
                if (due.isEmpty()) continue
                smsQueue.removeAll(due.toSet())
                val ordered = if (shuffleSms) due.shuffled(Random(steps)) else due
                for (sms in ordered) {
                    val envelope = inboxes.getValue(sms.to).accept(sms.chunk, steps.toLong())
                        ?: continue
                    if (envelope.kind == MeshKind.EVENTS) {
                        smsTransports[sms.to]?.replyTo = numbers[sms.from]
                    }
                    engines.getValue(sms.to).receive(envelope)
                }
            }
            check(steps < maxSteps) { "simulation did not settle" }
        }
    }

    private class SimRadio(
        private val node: String,
        private val network: SimNetwork,
    ) : Transport {
        override val id = "radio-$node"
        override val maxPayload = 32_000
        override fun available() = true
        override fun start(onReceive: (MeshEnvelope, Transport) -> Unit, onLog: (String) -> Unit) = Unit
        override fun stop() = Unit
        override fun send(envelope: MeshEnvelope): Int {
            network.radioSend(node, envelope)
            return 1
        }
    }

    private class SimSmsTransport(
        private val node: String,
        private val network: SimNetwork,
        private val gateway: String?,
    ) : Transport {
        override val id = "sms-$node"
        override val maxPayload = 160
        @Volatile
        var replyTo: String? = null

        override fun available() = gateway != null || replyTo != null
        override fun start(onReceive: (MeshEnvelope, Transport) -> Unit, onLog: (String) -> Unit) = Unit
        override fun stop() = Unit

        override fun send(envelope: MeshEnvelope): Int {
            val filtered = SmsPolicy.filter(envelope).let { filtered ->
                if (filtered.kind == MeshKind.EVENTS && filtered.events.size > 4) {
                    filtered.copy(events = filtered.events.take(4))
                } else {
                    filtered
                }
            }
            if (filtered.events.isEmpty() && filtered.ackedEventIds.isEmpty()) return 0
            val destination = if (envelope.kind == MeshKind.ACK) {
                replyTo ?: gateway
            } else {
                gateway
            } ?: return 0
            val chunks = SmsCodec.chunks(filtered)
            network.smsSend(node, destination, chunks)
            return chunks.size
        }
    }

    private fun radioNode(
        network: SimNetwork,
        name: String,
        ledger: FakeLedger? = null,
    ): Pair<RelayEngine, SimStore> {
        val store = SimStore()
        val transport = SimRadio(name, network)
        val engine = RelayEngine(name, store, ledger, mutableListOf(transport))
        network.add(name, engine, store, transport)
        return engine to store
    }

    @Test
    fun `sms radio only node reaches the ledger through an internet gateway`() {
        val network = SimNetwork()
        val ledger = FakeLedger()
        val storeA = SimStore()
        val smsA = SimSmsTransport("A", network, "+910000000002")
        val engineA = RelayEngine("A", storeA, null, mutableListOf(smsA))
        network.add("A", engineA, storeA, smsA)
        network.addSms("A", "+910000000001", smsA)

        val storeB = SimStore()
        val smsB = SimSmsTransport("B", network, null)
        val engineB = RelayEngine("B", storeB, ledger, mutableListOf(smsB))
        network.add("B", engineB, storeB, smsB)
        network.addSms("B", "+910000000002", smsB)

        events().forEach { storeA.enqueue(it) }
        engineA.broadcastPending()
        network.deliverAll()

        assertEquals(2, ledger.events.size) // REGISTER + TAMPER; GPS never goes over SMS
        assertEquals(0, storeB.pendingCount())
        assertEquals(1, storeA.pendingCount()) // GPS stays for Nearby/internet
        assertEquals(0, ledger.events.values.count { it.type == "GPS" })
    }

    @Test
    fun `sms policy keeps bind and barter events`() {
        val bind = Event.create(
            batchId = batchId,
            type = "BIND",
            data = mapOf("boxId" to "box:11111111-1111-4111-8111-111111111111"),
            signer = SignerA,
            ts = 1_500,
            prevHash = "",
        )
        val barter = Event.create(
            batchId = batchId,
            type = "BARTER",
            data = mapOf("offerId" to "offer-1", "action" to "offer"),
            signer = SignerA,
            ts = 1_600,
            prevHash = bind.hash,
        )
        val filtered = SmsPolicy.filter(MeshEnvelope.events("A", listOf(bind, barter)))
        assertEquals(listOf("BIND", "BARTER"), filtered.events.map { it.type })
    }

    @Test
    fun `sms chunks survive loss and reordering`() {
        val network = SimNetwork()
        network.shuffleSms = true
        network.smsDelay = 3
        val ledger = FakeLedger()
        val storeA = SimStore()
        val smsA = SimSmsTransport("A", network, "+910000000002")
        val engineA = RelayEngine("A", storeA, null, mutableListOf(smsA))
        network.add("A", engineA, storeA, smsA)
        network.addSms("A", "+910000000001", smsA)

        val storeB = SimStore()
        val smsB = SimSmsTransport("B", network, null)
        val engineB = RelayEngine("B", storeB, ledger, mutableListOf(smsB))
        network.add("B", engineB, storeB, smsB)
        network.addSms("B", "+910000000002", smsB)

        events().forEach { storeA.enqueue(it) }
        var dropped = true
        network.smsLoss = { _, _, chunk ->
            if (dropped && chunk.contains("|1/")) {
                dropped = false
                true
            } else {
                false
            }
        }
        engineA.broadcastPending()
        network.deliverAll()
        assertEquals(0, ledger.events.size)

        engineA.broadcastPending()
        network.deliverAll()
        assertEquals(2, ledger.events.size)
        assertEquals(0, storeB.pendingCount())
        assertTrue(storeA.pendingCount() <= 1)
    }

    @Test
    fun `multi hop radio relay reaches the gateway and ack clears the sender`() {
        val network = SimNetwork()
        val ledger = FakeLedger()
        val (engineA, storeA) = radioNode(network, "A")
        val (engineB, storeB) = radioNode(network, "B")
        val (engineC, storeC) = radioNode(network, "C", ledger)
        network.connectRadio("A", "B")
        network.connectRadio("B", "C")

        events().forEach { storeA.enqueue(it) }
        engineA.broadcastPending()
        network.deliverAll()

        assertEquals(3, ledger.events.size)
        assertEquals(0, storeA.pendingCount())
        assertEquals(0, storeB.pendingCount())
        assertEquals(0, storeC.pendingCount())
        assertEquals(3, ledger.events.values.map { it.type }.toSet().size)
    }

    @Test
    fun `lora transport bridges offline node into nearby gateway`() {
        val loraA = MeshtasticTransport(context = null, stubMode = true).apply { stubAvailable = true }
        val loraB = MeshtasticTransport(context = null, stubMode = true).apply { stubAvailable = true }
        val ledger = FakeLedger()

        val storeA = SimStore()
        val engineA = RelayEngine("A", storeA, null, mutableListOf(loraA))
        loraA.start({ env, _ -> engineA.receive(env) }, {})

        val storeB = SimStore()
        val engineB = RelayEngine("B", storeB, ledger, mutableListOf(loraB))
        loraB.start({ env, _ -> engineB.receive(env) }, {})

        events().forEach { storeA.enqueue(it) }
        engineA.broadcastPending()
        assertTrue(loraA.sentChunks.isNotEmpty())

        loraA.sentChunks.forEach { loraB.simulateReceive(it) }
        assertEquals(3, ledger.events.size)
        assertEquals(0, storeB.pendingCount())

        loraB.sentChunks.forEach { loraA.simulateReceive(it) }
        assertEquals(0, storeA.pendingCount())
    }

    @Test
    fun `duplicate envelopes do not double append or storm acks`() {
        val network = SimNetwork()
        val ledger = FakeLedger()
        val (engineA, storeA) = radioNode(network, "A")
        val (engineB, _) = radioNode(network, "B")
        radioNode(network, "C", ledger)
        network.connectRadio("A", "B")
        network.connectRadio("B", "C")

        events().forEach { storeA.enqueue(it) }
        val envelope = MeshEnvelope.events("A", storeA.pending())
        network.radioSend("A", envelope)
        network.radioSend("A", envelope)
        network.deliverAll()

        assertEquals(3, ledger.events.size)
        assertEquals(0, storeA.pendingCount())

        val ack = MeshEnvelope.ack("C", listOf("ev-1"))
        val firstAck = engineB.receive(ack)
        val secondAck = engineB.receive(ack)
        assertTrue(firstAck.forwarded)
        assertTrue(!secondAck.forwarded)
    }

    @Test
    fun `an ack does not hide a later event`() {
        val store = SimStore()
        val engine = RelayEngine("A", store, null, mutableListOf())
        val event = events().first()

        engine.receive(MeshEnvelope.ack("B", listOf(event.eventId)))
        assertEquals(0, store.pendingCount())

        engine.receive(MeshEnvelope.events("B", listOf(event)))
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun `ttl stops forwarding before the gateway`() {
        val network = SimNetwork()
        val ledger = FakeLedger()
        val (engineA, storeA) = radioNode(network, "A")
        val (engineB, storeB) = radioNode(network, "B")
        radioNode(network, "C", ledger)
        network.connectRadio("A", "B")
        network.connectRadio("B", "C")

        events().forEach { storeA.enqueue(it) }
        val envelope = MeshEnvelope.events("A", storeA.pending(), ttl = 1)
        network.radioSend("A", envelope)
        network.deliverAll()

        assertEquals(0, ledger.events.size)
        assertEquals(3, storeB.pendingCount())
        assertEquals(3, storeA.pendingCount())
    }
}
