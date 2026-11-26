package com.agrichain.mesh

import com.agrichain.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRelayTest {

    private class Store : RelayStore {
        private val seenEnvelopes = mutableSetOf<String>()
        private val keys = mutableMapOf<String, String>()

        override fun hasSeen(eventId: String) = false
        override fun markSeen(eventIds: Collection<String>) = Unit
        override fun enqueue(event: Event) = true
        override fun pending(): List<Event> = emptyList()
        override fun markSynced(eventIds: Collection<String>) = Unit
        override fun pendingCount() = 0

        override fun hasSeenEnvelope(envelopeId: String) = envelopeId in seenEnvelopes
        override fun markSeenEnvelope(envelopeId: String) {
            seenEnvelopes += envelopeId
        }

        override fun ackKey(signerId: String): String? = keys[signerId]
        override fun rememberAckKey(signerId: String, publicKeySpkiBase64: String) {
            keys.putIfAbsent(signerId, publicKeySpkiBase64)
        }
    }

    private class FakeTransport(override val id: String) : Transport {
        override val maxPayload = 4_096
        val sent = mutableListOf<MeshEnvelope>()

        override fun available() = true
        override fun start(onReceive: (MeshEnvelope, Transport) -> Unit, onLog: (String) -> Unit) = Unit
        override fun stop() = Unit
        override fun send(envelope: MeshEnvelope): Int {
            sent += envelope
            return 1
        }
    }

    @Test
    fun `signed chat is delivered once and forwarded with a lower ttl`() {
        val senderRadio = FakeTransport("ble")
        val sender = RelayEngine(
            "phone-a",
            Store(),
            ledger = null,
            transports = mutableListOf(senderRadio),
            signer = TestSigners.create("mesh:A"),
        )
        val outgoing = sender.sendChat("hello field")
        assertNotNull(outgoing)
        assertEquals(1, outgoing?.links)

        val chat = senderRadio.sent.single()
        assertEquals(MeshKind.CHAT, chat.kind)
        assertEquals("hello field", chat.chatText)
        assertTrue(chat.verifyChat())

        val receiverRadio = FakeTransport("ble")
        val received = mutableListOf<MeshEnvelope>()
        val receiver = RelayEngine(
            "phone-b",
            Store(),
            ledger = null,
            transports = mutableListOf(receiverRadio),
            signer = TestSigners.create("mesh:B"),
            chatListener = { received += it },
        )

        receiver.receive(chat, fromTransportId = "ble", fromPeerId = "ble-a")
        assertEquals(1, received.size)
        assertEquals("hello field", received[0].chatText)
        val forwarded = receiverRadio.sent.single()
        assertEquals(MeshKind.CHAT, forwarded.kind)
        assertEquals(chat.ttl - 1, forwarded.ttl)

        receiver.receive(chat, fromTransportId = "ble", fromPeerId = "ble-a")
        assertEquals(1, received.size)
        assertEquals(1, receiverRadio.sent.size)
    }

    @Test
    fun `directed chat is hidden from other phones but still floods onward`() {
        val radio = FakeTransport("wifi")
        val received = mutableListOf<MeshEnvelope>()
        val engine = RelayEngine(
            "phone-b",
            Store(),
            ledger = null,
            transports = mutableListOf(radio),
            chatListener = { received += it },
        )
        val chat = MeshEnvelope.chat(
            "phone-a",
            "private note",
            TestSigners.create("mesh:A"),
            recipientId = "phone-c",
        )

        engine.receive(chat, fromTransportId = "wifi", fromPeerId = "wifi-a")

        assertTrue(received.isEmpty())
        assertEquals(1, radio.sent.size)
        assertEquals("phone-c", radio.sent.single().recipientId)
    }

    @Test
    fun `user announcement is reported and forwarded on the local link`() {
        val radio = FakeTransport("ble")
        val users = mutableListOf<MeshEnvelope>()
        val engine = RelayEngine(
            "phone-b",
            Store(),
            ledger = null,
            transports = mutableListOf(radio),
            userListener = { users += it },
        )

        engine.receive(
            MeshEnvelope.user("phone-a", "Ramesh"),
            fromTransportId = "ble",
            fromPeerId = "ble-a",
        )

        assertEquals(1, users.size)
        assertEquals("Ramesh", users[0].senderName)
        assertEquals(MeshKind.USER, radio.sent.single().kind)
    }

    @Test
    fun `signed chat can use the wifi link`() {
        val wifi = FakeTransport("wifi")
        val engine = RelayEngine(
            "phone-a",
            Store(),
            ledger = null,
            transports = mutableListOf(wifi),
            signer = TestSigners.create("mesh:A"),
        )

        val result = engine.sendChat("over wi-fi")

        assertEquals(1, result?.links)
        assertEquals(1, wifi.sent.size)
        assertEquals("over wi-fi", wifi.sent.single().chatText)
    }

    @Test
    fun `unsigned chat is dropped`() {
        val radio = FakeTransport("ble")
        val received = mutableListOf<MeshEnvelope>()
        val engine = RelayEngine(
            "phone-b",
            Store(),
            ledger = null,
            transports = mutableListOf(radio),
            signer = TestSigners.create("mesh:B"),
            chatListener = { received += it },
        )
        val unsigned = MeshEnvelope(
            kind = MeshKind.CHAT,
            envelopeId = "chat-1",
            senderId = "phone-x",
            sentAt = System.currentTimeMillis(),
            ttl = 6,
            chatText = "spoofed",
        )

        val result = engine.receive(unsigned, fromTransportId = "ble", fromPeerId = "ble-x")

        assertEquals(0, received.size)
        assertTrue(!result.forwarded)
        assertTrue(radio.sent.isEmpty())
    }

    @Test
    fun `chat never goes over sms or lora`() {
        val sms = FakeTransport("sms")
        val lora = FakeTransport("meshtastic")
        val ble = FakeTransport("ble")
        val engine = RelayEngine(
            "phone-a",
            Store(),
            ledger = null,
            transports = mutableListOf(sms, lora, ble),
            signer = TestSigners.create("mesh:A"),
        )

        val result = engine.sendChat("local only")

        assertEquals(1, result?.links)
        assertEquals(1, ble.sent.size)
        assertTrue(sms.sent.isEmpty())
        assertTrue(lora.sent.isEmpty())
    }

    @Test
    fun `a changed chat signing key is ignored after first use`() {
        val received = mutableListOf<MeshEnvelope>()
        val engine = RelayEngine(
            "phone-b",
            Store(),
            ledger = null,
            transports = mutableListOf(FakeTransport("ble")),
            signer = TestSigners.create("mesh:B"),
            chatListener = { received += it },
        )
        val firstKey = TestSigners.create("mesh:X1")
        val secondKey = TestSigners.create("mesh:X2")

        engine.receive(
            MeshEnvelope.chat("phone-x", "first", firstKey),
            fromTransportId = "ble",
            fromPeerId = "ble-x",
        )
        engine.receive(
            MeshEnvelope.chat("phone-x", "second", secondKey),
            fromTransportId = "ble",
            fromPeerId = "ble-x",
        )

        assertEquals(1, received.size)
        assertEquals("first", received[0].chatText)
    }

    @Test
    fun `chat arriving from sms is not bridged into the local mesh`() {
        val radio = FakeTransport("ble")
        val received = mutableListOf<MeshEnvelope>()
        val engine = RelayEngine(
            "phone-b",
            Store(),
            ledger = null,
            transports = mutableListOf(radio),
            signer = TestSigners.create("mesh:B"),
            chatListener = { received += it },
        )
        val chat = MeshEnvelope.chat("phone-a", "sms chat", TestSigners.create("mesh:A"))

        val result = engine.receive(chat, fromTransportId = "sms", fromPeerId = "+910000000001")

        assertEquals(0, received.size)
        assertTrue(!result.forwarded)
        assertTrue(radio.sent.isEmpty())
    }

    @Test
    fun `ttl one delivers locally but is not forwarded`() {
        val radio = FakeTransport("ble")
        val received = mutableListOf<MeshEnvelope>()
        val engine = RelayEngine(
            "phone-b",
            Store(),
            ledger = null,
            transports = mutableListOf(radio),
            signer = TestSigners.create("mesh:B"),
            chatListener = { received += it },
        )
        val chat = MeshEnvelope.chat("phone-a", "last hop", TestSigners.create("mesh:A"), ttl = 1)

        val result = engine.receive(chat, fromTransportId = "ble", fromPeerId = "ble-a")

        assertEquals(1, received.size)
        assertTrue(!result.forwarded)
        assertTrue(radio.sent.isEmpty())
    }
}
