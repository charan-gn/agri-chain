package com.agrichain.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchQrCodecTest {

    private val batchId = "22222222-2222-4222-8222-222222222222"

    @Test
    fun `payload round trips`() {
        assertEquals("agri://batch/$batchId", BatchQrCodec.payload(batchId))
        assertEquals(batchId, BatchQrCodec.parse(BatchQrCodec.payload(batchId)))
    }

    @Test
    fun `rejects foreign and malformed payloads`() {
        assertNull(BatchQrCodec.parse("https://example.com/batch/$batchId"))
        assertNull(BatchQrCodec.parse("agri://batch/not-a-uuid"))
        assertNull(BatchQrCodec.parse("agri://batch/00000000-0000-0000-0000-000000000000"))
    }
}
