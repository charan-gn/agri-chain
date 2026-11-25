package com.agrichain.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceModelsTest {

    private fun trade(ts: Long, tokens: Int, paise: Int, grade: String = "A") = Trade(
        eventId = "event-$ts",
        batchId = "batch-1",
        ts = ts,
        tokens = tokens,
        pricePerKgPaise = paise,
        grade = grade,
        fromActorId = "farmer:aaaaaaaa-1111",
        toActorId = "buyer:bbbbbbbb-2222",
    )

    @Test
    fun `rupees keep two paise digits`() {
        assertEquals("₹24.50", PriceFormat.rupees(2450))
        assertEquals("₹24.05", PriceFormat.rupees(2405))
        assertEquals("₹0.09", PriceFormat.rupees(9))
        assertEquals("₹24.50/kg", PriceFormat.perKg(2450))
    }

    @Test
    fun `bars follow trade order and scale to the maximum`() {
        val bars = PriceBars.build(
            listOf(
                trade(1764000000000, 10, 2380),
                trade(1764000060000, 5, 2450),
                trade(1764000120000, 2, 2510),
            )
        )

        assertEquals(listOf(2380, 2450, 2510), bars.map { it.pricePerKgPaise })
        assertEquals(2380f / 2510f, bars[0].fraction, 0.0001f)
        assertEquals(1f, bars[2].fraction, 0.0001f)
        assertEquals("5 kg · grade A", bars[1].source)
    }

    @Test
    fun `bars keep only the last twenty trades`() {
        val trades = (1..25).map { index ->
            trade(1764000000000 + index * 1000L, index, 2000 + index)
        }

        val bars = PriceBars.build(trades)
        assertEquals(20, bars.size)
        assertEquals(2006, bars.first().pricePerKgPaise)
        assertEquals(2025, bars.last().pricePerKgPaise)
    }

    @Test
    fun `empty bars stay empty`() {
        assertTrue(PriceBars.build(emptyList()).isEmpty())
    }

    @Test
    fun `parses median20 response with grades and null price`() {
        val body = """
            {
              "crop": "wheat",
              "grade": null,
              "method": "median20",
              "sampleSize": 2,
              "pricePerKgPaise": 2500,
              "byGrade": {"A": 2500, "B": null, "C": null},
              "updatedAt": 1764000000000,
              "trades": [
                {
                  "batchId": "b1",
                  "eventId": "e1",
                  "ts": 1764000001000,
                  "tokens": 10,
                  "pricePerKgPaise": 2000,
                  "grade": "A",
                  "fromActorId": "farmer:1",
                  "toActorId": "buyer:2"
                }
              ]
            }
        """.trimIndent()

        val snapshot = TradePriceJson.parse(body)
        assertEquals("median20", snapshot.method)
        assertEquals(2500, snapshot.pricePerKgPaise)
        assertNull(snapshot.grade)
        assertEquals(2500, snapshot.byGrade["A"])
        assertNull(snapshot.byGrade["B"])
        assertEquals(1, snapshot.trades.size)
        assertEquals("e1", snapshot.trades[0].eventId)
        assertEquals(10, snapshot.trades[0].tokens)
        assertEquals("farmer:1", snapshot.trades[0].fromActorId)
    }

    @Test
    fun `cache round trip keeps nulls`() {
        val snapshot = TradePriceSnapshot(
            crop = "wheat",
            grade = null,
            method = "median20",
            sampleSize = 0,
            pricePerKgPaise = null,
            byGrade = mapOf("A" to null),
            updatedAt = 1764000000000,
            trades = emptyList(),
        )

        val parsed = TradePriceJson.parse(TradePriceJson.encode(snapshot))
        assertEquals("wheat", parsed.crop)
        assertNull(parsed.pricePerKgPaise)
        assertNull(parsed.byGrade["A"])
        assertTrue(parsed.trades.isEmpty())
    }

    @Test
    fun `base urls get a scheme and lose trailing slashes`() {
        assertEquals(
            "http://10.0.2.2:8000",
            PriceUrls.normalizeBaseUrl("10.0.2.2:8000"),
        )
        assertEquals(
            "https://ledger.example",
            PriceUrls.normalizeBaseUrl("https://ledger.example/"),
        )
        assertEquals(
            PriceUrls.DEFAULT_BASE_URL,
            PriceUrls.normalizeBaseUrl("   "),
        )
        assertEquals(
            "http://host:8000/trades?crop=wheat",
            PriceUrls.tradesUrl("http://host:8000/", " Wheat "),
        )
        assertEquals("wheat", PriceUrls.cacheKey(" Wheat "))
    }
}
