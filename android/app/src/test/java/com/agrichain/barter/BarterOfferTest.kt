package com.agrichain.barter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BarterOfferTest {

    @Test
    fun `parses offer fields`() {
        val offer = BarterOffer.parse(
            JSONObject(
                """
                {"offerId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","status":"open",
                 "batchId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                 "actorId":"farmer:11111111-1111-4111-8111-111111111111",
                 "withActorId":"farmer:22222222-2222-4222-8222-222222222222",
                 "giveKg":10,"wantBatchId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                 "wantKg":5,"ts":1764000000000}
                """.trimIndent()
            )
        )

        assertEquals("open", offer.status)
        assertEquals(10, offer.giveKg)
        assertEquals(5, offer.wantKg)
        assertEquals("farmer:22222222-2222-4222-8222-222222222222", offer.withActorId)
    }

    @Test
    fun `batch label shortens the id`() {
        val batch = BatchInfo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "wheat", "A", 90)

        assertEquals("wheat · 90 kg · bbbbbbbb", batch.label)
    }
}
