package com.agrichain.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceModelsTest {

    private fun point(market: String, paise: Int) = PricePoint(
        crop = "wheat",
        market = market,
        pricePerKgPaise = paise,
        source = "demo-mandi",
        ts = 1764000000000,
    )

    @Test
    fun `rupees keep two paise digits`() {
        assertEquals("₹24.50", PriceFormat.rupees(2450))
        assertEquals("₹24.05", PriceFormat.rupees(2405))
        assertEquals("₹0.09", PriceFormat.rupees(9))
        assertEquals("₹24.50/kg", PriceFormat.perKg(2450))
    }

    @Test
    fun `bars are sorted by price and scaled to the maximum`() {
        val bars = PriceBars.build(
            listOf(
                point("khammam", 2380),
                point("warangal", 2450),
                point("nizamabad", 2510),
            )
        )

        assertEquals(listOf("nizamabad", "warangal", "khammam"), bars.map { it.label })
        assertEquals(1f, bars[0].fraction, 0.0001f)
        assertEquals(2450f / 2510f, bars[1].fraction, 0.0001f)
        assertEquals("demo-mandi", bars[0].source)
    }

    @Test
    fun `empty bars stay empty`() {
        assertTrue(PriceBars.build(emptyList()).isEmpty())
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
            "http://host:8000/prices",
            PriceUrls.listUrl("http://host:8000/", null, null),
        )
    }

    @Test
    fun `query filters are lowercased and encoded`() {
        assertEquals(
            "http://host:8000/prices?crop=wheat&market=khammam",
            PriceUrls.listUrl("host:8000", " Wheat ", " Khammam "),
        )
        assertEquals("wheat|khammam", PriceUrls.cacheKey("Wheat", "Khammam"))
        assertEquals("wheat|", PriceUrls.cacheKey("wheat", null))
    }
}
