package com.agrichain.box

import com.agrichain.core.Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GpsEventsTest {

    private val signer = object : Signer {
        override val actorId = "box:33333333-3333-4333-8333-333333333333"
        override fun sign(bytes: ByteArray): ByteArray = "test-signature".toByteArray()
    }

    @Test
    fun `formats coordinates with exactly six decimals`() {
        assertEquals("17.385044", GpsEvents.format(17.385044))
        assertEquals("-78.486671", GpsEvents.format(-78.486671))
        assertEquals("0.000000", GpsEvents.format(0.0))
    }

    @Test
    fun `gps event keeps previous hash and integer accuracy`() {
        val event = GpsEvents.create(
            batchId = "22222222-2222-4222-8222-222222222222",
            signer = signer,
            latitude = 17.385044,
            longitude = 78.486671,
            accuracyM = 8,
            prevHash = "abc123",
            ts = 1764000000123,
        )

        assertEquals("GPS", event.type)
        assertEquals("17.385044", event.lat)
        assertEquals("78.486671", event.lon)
        assertEquals(8, event.data["accuracyM"])
        assertEquals("abc123", event.prevHash)
        assertEquals(64, event.hash.length)
    }

    @Test
    fun `second fix chains to the first hash`() {
        val first = GpsEvents.create(
            batchId = "22222222-2222-4222-8222-222222222222",
            signer = signer,
            latitude = 17.385044,
            longitude = 78.486671,
            accuracyM = 8,
            prevHash = "",
            ts = 1764000000123,
        )
        val second = GpsEvents.create(
            batchId = "22222222-2222-4222-8222-222222222222",
            signer = signer,
            latitude = 17.385045,
            longitude = 78.486672,
            accuracyM = 6,
            prevHash = first.hash,
            ts = 1764000003123,
        )

        assertEquals(first.hash, second.prevHash)
        assertNotEquals(first.hash, second.hash)
    }

    @Test
    fun `negative accuracy is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GpsEvents.create(
                batchId = "22222222-2222-4222-8222-222222222222",
                signer = signer,
                latitude = 17.385044,
                longitude = 78.486671,
                accuracyM = -1,
                prevHash = "",
            )
        }
    }

    @Test
    fun `out-of-range latitude is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GpsEvents.create(
                batchId = "22222222-2222-4222-8222-222222222222",
                signer = signer,
                latitude = 91.0,
                longitude = 78.486671,
                accuracyM = 5,
                prevHash = "",
            )
        }
    }
}
