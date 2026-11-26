package com.agrichain.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class WifiProtocolTest {

    private val actorA = ByteArray(8) { it.toByte() }
    private val actorB = ByteArray(8) { (it + 10).toByte() }

    @Test
    fun beaconRoundTrip() {
        val encoded = WifiProtocol.beacon(actorA, 45678)
        val decoded = WifiProtocol.parseBeacon(encoded, "192.168.1.7")
        assertEquals(V3Codec.hex(actorA), decoded?.actorId)
        assertEquals(45678, decoded?.port)
        assertEquals("192.168.1.7", decoded?.host)
    }

    @Test
    fun beaconRejectsGarbage() {
        assertNull(WifiProtocol.parseBeacon(ByteArray(13)))
        assertNull(WifiProtocol.parseBeacon(WifiProtocol.beacon(actorA, 1) + byteArrayOf(0)))
        val badMagic = WifiProtocol.beacon(actorA, 1).also { it[0] = 0x42 }
        assertNull(WifiProtocol.parseBeacon(badMagic))
        assertNull(WifiProtocol.parseBeacon(WifiProtocol.beacon(actorA, 1).also { it[13] = 0 }))
    }

    @Test
    fun frameRoundTripAndPing() {
        val out = ByteArrayOutputStream()
        val writer = DataOutputStream(out)
        val envelope = byteArrayOf(7, 1, 2, 3, 4)
        WifiProtocol.writeFrame(writer, envelope)
        WifiProtocol.writeFrame(writer, ByteArray(0))
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(envelope, WifiProtocol.readFrame(input))
        assertArrayEquals(ByteArray(0), WifiProtocol.readFrame(input))
        assertNull(WifiProtocol.readFrame(input))
    }

    @Test
    fun frameRejectsOversizeLength() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeInt(70_000)
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        runCatching { WifiProtocol.readFrame(input) }
            .onSuccess { throw AssertionError("oversize frame accepted") }
    }

    @Test
    fun actorIdComesFromEnvelopeHeader() {
        val message = ByteArray(20)
        message[0] = 1
        message[1] = 0x10
        actorA.copyInto(message, 7)
        val envelope = byteArrayOf(7) + message
        assertEquals(V3Codec.hex(actorA), WifiProtocol.actorIdFromEnvelope(envelope))
        assertNull(WifiProtocol.actorIdFromEnvelope(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun lowerActorIdInitiates() {
        assertTrue(WifiProtocol.shouldInitiate("0000000000000001", "0000000000000002"))
        assertFalse(WifiProtocol.shouldInitiate("0000000000000002", "0000000000000001"))
        assertFalse(WifiProtocol.shouldInitiate("0000000000000001", "0000000000000001"))
    }

    @Test
    fun serviceNameRoundTrip() {
        val name = WifiProtocol.serviceName("0011223344556677")
        assertEquals("0011223344556677", WifiProtocol.actorFromServiceName(name))
        assertNull(WifiProtocol.actorFromServiceName("Other-0011223344556677"))
        assertNull(WifiProtocol.actorFromServiceName("AgriMesh-not-hex"))
    }

    @Test
    fun derSignatureConvertsToRaw64Bytes() {
        val kp = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val data = "agrimesh".toByteArray()
        val signature = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(data)
            sign()
        }
        val raw = V3Codec.derToRaw(signature)
        assertEquals(64, raw.size)
    }
}
