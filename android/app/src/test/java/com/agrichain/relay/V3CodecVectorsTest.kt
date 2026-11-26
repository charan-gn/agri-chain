package com.agrichain.relay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class V3CodecVectorsTest {

    private val suite: JSONObject by lazy {
        val stream = javaClass.getResourceAsStream("/vectors.json")
            ?: error("contracts/vectors.json is not on the test classpath")
        JSONObject(stream.bufferedReader(StandardCharsets.UTF_8).readText())
    }
    private val keys: JSONObject get() = suite.getJSONObject("keys")
    private val cases: JSONArray get() = suite.getJSONArray("vectors")

    private fun key(name: String): JSONObject = keys.getJSONObject(name)

    private fun id(name: String): ByteArray = V3Codec.hex(key(name).getString("actorIdHex"))

    private fun message(case: JSONObject): V3Message = V3Message(
        type = V3Type.fromCode(case.getInt("typeId")),
        ts = case.getLong("ts"),
        fromId = id(case.getString("from")),
        body = V3Bodies.encode(fromJson(V3Type.fromCode(case.getInt("typeId")), case.getJSONObject("body"))),
        flags = case.getInt("flags"),
        toId = if (case.isNull("to")) null else id(case.getString("to")),
    )

    private fun fromJson(type: V3Type, json: JSONObject): V3Body {
        fun h(name: String) = V3Codec.hex(json.getString(name))
        fun i(name: String) = json.getInt(name)
        fun l(name: String) = json.getLong(name)
        return when (type) {
            V3Type.ANNOUNCE -> V3Body.Announce(h("pubkey"), i("caps"), json.getString("nick"))
            V3Type.LISTING -> V3Body.Listing(
                i("crop"), l("qtyKg"), l("askPaise"), i("region"), l("validUntil"), i("grade"), h("batchId"),
            )
            V3Type.ORDER -> V3Body.Order(h("listingId"), l("qtyKg"), l("bidPaise"), l("validUntil"))
            V3Type.ORDER_ACCEPT -> V3Body.OrderAccept(h("orderId"))
            V3Type.ORDER_CANCEL -> V3Body.OrderCancel(h("orderId"))
            V3Type.DELIVERED -> V3Body.Delivered(h("orderId"))
            V3Type.BARTER_OFFER -> V3Body.BarterOffer(
                i("giveCrop"), l("giveKg"), i("wantCrop"), l("wantKg"), i("region"), l("validUntil"),
            )
            V3Type.BARTER_ACCEPT -> V3Body.BarterAccept(h("offerId"))
            V3Type.TAMPER_ALERT -> V3Body.TamperAlert(
                h("batchId"), i("kind"), i("lat"), i("lon"), l("detectedTs"),
            )
            V3Type.BATCH_REGISTER -> V3Body.BatchRegister(
                i("crop"), l("weightKg"), l("harvestTs"), i("region"), i("grade"),
            )
            V3Type.BIND -> V3Body.Bind(h("batchId"), h("boxId"))
            V3Type.PRICE_TABLE -> V3Body.PriceTable(
                i("region"), l("version"), l("asOf"), i("part"), i("parts"),
                json.getJSONArray("rows").let { rows ->
                    (0 until rows.length()).map { index ->
                        val row = rows.getJSONArray(index)
                        V3Body.PriceRow(row.getInt(0), row.getInt(1), row.getInt(2))
                    }
                },
            )
            V3Type.PRICE_BEACON -> V3Body.PriceBeacon(i("region"), l("version"), h("hash8"))
            V3Type.RECEIPT -> V3Body.Receipt(h("msgId"), i("status"), i("reason"), l("seq"), h("head8"))
            V3Type.NOTICE -> V3Body.Notice(i("kind"), h("refId"), l("seq"), l("qtyKg"), l("pricePaise"))
        }
    }

    private fun normalize(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().sorted().associateWith { key -> normalize(value.get(key)) }
        is JSONArray -> (0 until value.length()).map { normalize(value.get(it)) }
        is Map<*, *> -> value.entries.associate { (key, item) -> key to normalize(item) }
        is List<*> -> value.map { normalize(it) }
        is Int -> value.toLong()
        is ByteArray -> V3Codec.hex(value)
        else -> value
    }

    @Test
    fun `suite covers every type and the signed fixtures`() {
        val covered = (0 until cases.length()).map { cases.getJSONObject(it).getInt("typeId") }.toSet()
        assertEquals(V3Type.entries.map { it.code }.toSet(), covered)
        assertTrue(cases.length() >= 19)
        val signed = (0 until cases.length()).count { !cases.getJSONObject(it).isNull("signedMessageHex") }
        assertTrue(signed >= 2)
    }

    @Test
    fun `kotlin matches every Python vector`() {
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val type = V3Type.fromCode(case.getInt("typeId"))
            val body = fromJson(type, case.getJSONObject("body"))

            val bodyHex = V3Codec.hex(V3Bodies.encode(body))
            assertEquals(name, case.getString("bodyHex"), bodyHex)
            assertEquals(
                name,
                normalize(case.getJSONObject("body")),
                normalize(V3Bodies.toMap(V3Bodies.parse(type, V3Codec.hex(bodyHex)))),
            )

            val parsed = V3Message(
                type = type,
                ts = case.getLong("ts"),
                fromId = id(case.getString("from")),
                body = V3Codec.hex(bodyHex),
                flags = case.getInt("flags"),
                toId = if (case.isNull("to")) null else id(case.getString("to")),
            )
            assertEquals(name, case.getString("unsignedHex"), V3Codec.hex(V3Codec.unsignedBytes(parsed)))
            assertEquals(name, case.getString("msgIdHex"), V3Codec.hex(V3Codec.msgId(parsed)))

            val roundTrip = V3Codec.decode(V3Codec.unsignedBytes(parsed))
            assertEquals(name, parsed.msgIdHex(), V3Codec.hex(V3Codec.msgId(roundTrip)))
            assertEquals(name, null, roundTrip.signature)
        }
    }

    private fun V3Message.msgIdHex() = V3Codec.hex(V3Codec.msgId(this))

    @Test
    fun `static signatures verify and tampering fails`() {
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            if (case.isNull("signedMessageHex")) continue
            val name = case.getString("name")
            val message = V3Codec.hex(case.getString("signedMessageHex"))
            val pubkey = V3Codec.hex(key(case.getString("from")).getString("pubkeyCompressedHex"))
            assertTrue(name, V3Codec.verify(message, pubkey))
            assertFalse(name, V3Codec.verify(v3messageTampered(message, case.getInt("typeId")), pubkey))
        }
    }

    private fun v3messageTampered(message: ByteArray, typeId: Int): ByteArray {
        val copy = message.copyOf()
        copy[copy.size - V3.SIG_LEN - 1] = (copy[copy.size - V3.SIG_LEN - 1].toInt() xor 1).toByte()
        return copy
    }

    @Test
    fun `sign verify round trip uses raw r s`() {
        val privateKey = V3Bodies.scalarKey(7)
        val pubkey = V3Codec.compressedPublicKey(privateKey)
        assertEquals(
            "028e533b6fa0bf7b4625bb30667c01fb607ef9f8b8a80fef5b300628703187b2a3",
            V3Codec.hex(pubkey),
        )
        val message = V3Message(
            type = V3Type.ORDER,
            ts = 1764115200,
            fromId = V3Codec.actorId(pubkey),
            body = V3Bodies.encode(V3Body.Order(ByteArray(12) { it.toByte() }, 10, 1500, 1766707200)),
        )
        val signed = V3Codec.sign(message, privateKey)
        assertEquals(16 + message.body.size + V3.SIG_LEN, signed.size)
        assertTrue(V3Codec.verify(signed, pubkey))
        assertFalse(V3Codec.verify(signed, V3Codec.compressedPublicKey(V3Bodies.scalarKey(8))))
        assertEquals(64, V3Codec.decode(signed).signature!!.size)
    }

    @Test
    fun `envelope round trip and limits`() {
        val unsigned = V3Codec.hex(
            cases.getJSONObject(0).getString("unsignedHex"),
        )
        val envelope = V3Codec.encodeEnvelope(unsigned, 7)
        val (ttl, inner) = V3Codec.decodeEnvelope(envelope)
        assertEquals(7, ttl)
        assertTrue(inner.contentEquals(unsigned))
        assertTrue(envelope.size <= V3.MAX_MESSAGE + 1)

        val failures = runCatching { V3Codec.encodeEnvelope(unsigned, 0) }.isFailure &&
            runCatching { V3Codec.encodeEnvelope(ByteArray(V3.MAX_MESSAGE + 1), 7) }.isFailure
        assertTrue(failures)
    }

    @Test
    fun `price table max body fits the limit`() {
        val case = (0 until cases.length())
            .map { cases.getJSONObject(it) }
            .first { it.getString("name") == "price-table-max" }
        val body = V3Codec.hex(case.getString("bodyHex"))
        assertEquals(107, body.size)
        assertEquals(107, V3Bodies.encode(V3Bodies.parse(V3Type.PRICE_TABLE, body)).size)
    }

    @Test
    fun `rejects bad headers`() {
        val unsigned = V3Codec.hex(cases.getJSONObject(0).getString("unsignedHex"))
        val reserved = unsigned.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertTrue(runCatching { V3Codec.decode(reserved) }.isFailure)
        assertTrue(
            runCatching {
                V3Codec.decode(byteArrayOf(2) + unsigned.copyOfRange(1, unsigned.size))
            }.isFailure,
        )
        assertTrue(runCatching { V3Codec.decode(unsigned.copyOf(unsigned.size - 1)) }.isFailure)
        assertTrue(runCatching { V3Codec.decode(unsigned + 0) }.isFailure)
    }
}
