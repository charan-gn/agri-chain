package com.agrichain.box

import com.agrichain.core.Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TamperRulesTest {

    @Test
    fun `resting phone is not movement`() {
        assertFalse(TamperRules.isMovement(0.0, 0.0, 9.80665))
        assertTrue(TamperRules.movementDelta(0.0, 0.0, 9.80665) < 0.001)
    }

    @Test
    fun `shake over the delta threshold is movement`() {
        assertTrue(TamperRules.isMovement(0.0, 0.0, 15.0))
        assertTrue(TamperRules.isMovement(13.0, 0.0, 0.0))
    }

    @Test
    fun `light change needs a baseline and the threshold`() {
        assertFalse(TamperRules.isLightChange(-1.0, 900.0))
        assertFalse(TamperRules.isLightChange(400.0, 550.0))
        assertTrue(TamperRules.isLightChange(400.0, 650.0))
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val distance = TamperRules.distanceMeters(17.385044, 78.486671, 18.385044, 78.486671)
        assertTrue("distance was $distance", distance in 111_000.0..111_300.0)
        assertEquals(0.0, TamperRules.distanceMeters(17.0, 78.0, 17.0, 78.0), 0.001)
    }

    @Test
    fun `geofence breach follows the radius`() {
        // ~111 m north of the anchor.
        assertFalse(
            TamperRules.isOutsideGeofence(
                17.385044, 78.486671, 17.386044, 78.486671, TamperRules.DEFAULT_GEOFENCE_RADIUS_M,
            )
        )
        // ~222 m north of the anchor.
        assertTrue(
            TamperRules.isOutsideGeofence(
                17.385044, 78.486671, 17.387044, 78.486671, TamperRules.DEFAULT_GEOFENCE_RADIUS_M,
            )
        )
    }
}

class TamperEventsTest {

    private val signer = object : Signer {
        override val actorId = "box:33333333-3333-4333-8333-333333333333"
        override fun sign(bytes: ByteArray): ByteArray = "test-signature".toByteArray()
    }

    private val batchId = "22222222-2222-4222-8222-222222222222"

    @Test
    fun `tamper event carries kind detail and previous hash`() {
        val event = TamperEvents.create(
            batchId = batchId,
            signer = signer,
            kind = "geofence",
            detail = "240 m outside 200 m fence",
            prevHash = "abc123",
            ts = 1764000000123,
        )

        assertEquals("TAMPER", event.type)
        assertEquals("geofence", event.data["kind"])
        assertEquals("240 m outside 200 m fence", event.data["detail"])
        assertEquals("abc123", event.prevHash)
        assertNull(event.lat)
        assertNull(event.lon)
        assertEquals(64, event.hash.length)
    }

    @Test
    fun `blank detail is left out of the event data`() {
        val event = TamperEvents.create(
            batchId = batchId,
            signer = signer,
            kind = "movement",
            detail = " ",
            prevHash = "",
        )

        assertEquals(setOf("kind"), event.data.keys)
    }

    @Test
    fun `unknown kind is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TamperEvents.create(
                batchId = batchId,
                signer = signer,
                kind = "vibration",
                detail = null,
                prevHash = "",
            )
        }
    }
}
