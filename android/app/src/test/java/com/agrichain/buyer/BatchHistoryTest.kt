package com.agrichain.buyer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchHistoryTest {

    private val farmer = "farmer:11111111-1111-4111-8111-111111111111"
    private val buyer = "buyer:33333333-3333-4333-8333-333333333333"
    private val box = "box:55555555-5555-4555-8555-555555555555"
    private val batchId = "22222222-2222-4222-8222-222222222222"

    private val summary = JSONObject(
        """
        {
          "batchId": "$batchId",
          "crop": "wheat",
          "grade": "A",
          "initialKg": 100,
          "balances": {"$buyer": 40, "$farmer": 60},
          "ownerId": "$farmer",
          "tamperCount": 1,
          "lastHash": "aa",
          "verified": false,
          "events": [
            {
              "eventId": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
              "batchId": "$batchId",
              "type": "REGISTER",
              "actorId": "$farmer",
              "ts": 1764000000000,
              "lat": null,
              "lon": null,
              "data": {"crop": "wheat", "grade": "A", "weightKg": 100},
              "prevHash": "",
              "hash": "aa",
              "sig": "sig"
            },
            {
              "eventId": "ffffffff-ffff-4fff-8fff-ffffffffffff",
              "batchId": "$batchId",
              "type": "GPS",
              "actorId": "$box",
              "ts": 1764000001000,
              "lat": "17.385044",
              "lon": "78.486671",
              "data": {"accuracyM": 5},
              "prevHash": "aa",
              "hash": "bb",
              "sig": "sig"
            },
            {
              "eventId": "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
              "batchId": "$batchId",
              "type": "TRANSFER",
              "actorId": "$farmer",
              "ts": 1764000002000,
              "lat": null,
              "lon": null,
              "data": {
                "toActorId": "$buyer",
                "tokens": 40,
                "pricePerKgPaise": 2000
              },
              "prevHash": "bb",
              "hash": "cc",
              "sig": "sig"
            },
            {
              "eventId": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
              "batchId": "$batchId",
              "type": "TAMPER",
              "actorId": "$box",
              "ts": 1764000003000,
              "lat": null,
              "lon": null,
              "data": {"kind": "light"},
              "prevHash": "cc",
              "hash": "dd",
              "sig": "sig"
            }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun `parses summary, balances and route`() {
        val history = BatchHistory.parse(summary)

        assertEquals(batchId, history.batchId)
        assertEquals("wheat", history.crop)
        assertEquals("A", history.grade)
        assertEquals(100, history.initialKg)
        assertEquals(farmer, history.ownerId)
        assertFalse(history.verified)
        assertEquals(1, history.tamperCount)
        assertEquals(
            listOf(farmer to 60, buyer to 40),
            history.balances
        )
        assertEquals(listOf(17.385044 to 78.486671), history.route)
        assertEquals(
            listOf("REGISTER", "GPS", "TRANSFER", "TAMPER"),
            history.events.map { it.type }
        )
    }

    @Test
    fun `describes transfer with price and short actors`() {
        val transfer = BatchHistory.parse(summary).events[2]

        assertEquals("40 kg → buyer:33333333 @ ₹20.00/kg", transfer.detail)
        assertEquals("farmer:11111111", BatchHistory.shortActor(farmer))
    }

    @Test
    fun `handles a batch with no gps and no tamper`() {
        val plain = JSONObject(
            """{"batchId":"$batchId","crop":"rice","grade":"B","initialKg":5,
               "balances":{"$farmer":5},"ownerId":"$farmer","tamperCount":0,
               "verified":true,"events":[]}"""
        )
        val history = BatchHistory.parse(plain)

        assertTrue(history.route.isEmpty())
        assertTrue(history.events.isEmpty())
        assertEquals(listOf(farmer to 5), history.balances)
    }
}
