package com.agrichain.relay

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** Typed v3 bodies. Field names and widths follow contracts/MESSAGE_SPEC.md. */
sealed interface V3Body {
    val type: V3Type

    data class Announce(val pubkey: ByteArray, val caps: Int, val nick: String) : V3Body {
        override val type get() = V3Type.ANNOUNCE
    }

    data class Listing(
        val crop: Int,
        val qtyKg: Long,
        val askPaise: Long,
        val region: Int,
        val validUntil: Long,
        val grade: Int,
        val batchId: ByteArray,
    ) : V3Body {
        override val type get() = V3Type.LISTING
    }

    data class Order(
        val listingId: ByteArray,
        val qtyKg: Long,
        val bidPaise: Long,
        val validUntil: Long,
    ) : V3Body {
        override val type get() = V3Type.ORDER
    }

    data class OrderAccept(val orderId: ByteArray) : V3Body {
        override val type get() = V3Type.ORDER_ACCEPT
    }

    data class OrderCancel(val orderId: ByteArray) : V3Body {
        override val type get() = V3Type.ORDER_CANCEL
    }

    data class Delivered(val orderId: ByteArray) : V3Body {
        override val type get() = V3Type.DELIVERED
    }

    data class BarterOffer(
        val giveCrop: Int,
        val giveKg: Long,
        val wantCrop: Int,
        val wantKg: Long,
        val region: Int,
        val validUntil: Long,
    ) : V3Body {
        override val type get() = V3Type.BARTER_OFFER
    }

    data class BarterAccept(val offerId: ByteArray) : V3Body {
        override val type get() = V3Type.BARTER_ACCEPT
    }

    data class TamperAlert(
        val batchId: ByteArray,
        val kind: Int,
        val lat: Int,
        val lon: Int,
        val detectedTs: Long,
    ) : V3Body {
        override val type get() = V3Type.TAMPER_ALERT
    }

    data class BatchRegister(
        val crop: Int,
        val weightKg: Long,
        val harvestTs: Long,
        val region: Int,
        val grade: Int,
    ) : V3Body {
        override val type get() = V3Type.BATCH_REGISTER
    }

    data class Bind(val batchId: ByteArray, val boxId: ByteArray) : V3Body {
        override val type get() = V3Type.BIND
    }

    data class PriceRow(val crop: Int, val pricePaise: Int, val trades: Int)

    data class PriceTable(
        val region: Int,
        val version: Long,
        val asOf: Long,
        val part: Int,
        val parts: Int,
        val rows: List<PriceRow>,
    ) : V3Body {
        override val type get() = V3Type.PRICE_TABLE
    }

    data class PriceBeacon(val region: Int, val version: Long, val hash8: ByteArray) : V3Body {
        override val type get() = V3Type.PRICE_BEACON
    }

    data class Receipt(
        val msgId: ByteArray,
        val status: Int,
        val reason: Int,
        val seq: Long,
        val head8: ByteArray,
    ) : V3Body {
        override val type get() = V3Type.RECEIPT
    }

    data class Notice(
        val kind: Int,
        val refId: ByteArray,
        val seq: Long,
        val qtyKg: Long,
        val pricePaise: Long,
    ) : V3Body {
        override val type get() = V3Type.NOTICE
    }
}

object V3Bodies {

    fun encode(body: V3Body): ByteArray {
        val out = ByteArrayOutputStream()
        fun u8(value: Int) {
            require(value in 0..0xFF) { "value out of u8 range" }
            out.write(value)
        }
        fun u24(value: Int) {
            require(value in 0..0xFFFFFF) { "value out of u24 range" }
            out.write(value ushr 16)
            out.write(value ushr 8)
            out.write(value)
        }
        fun u32(value: Long) {
            require(value in 0..0xFFFFFFFFL) { "value out of u32 range" }
            out.write(V3Codec.be32(value))
        }
        fun i32(value: Int) {
            out.write(V3Codec.be32(value.toLong() and 0xFFFFFFFFL))
        }
        fun bytes(value: ByteArray, size: Int, name: String) {
            require(value.size == size) { "$name must be $size bytes" }
            out.write(value)
        }
        fun id12(value: ByteArray) = bytes(value, V3.ID12_LEN, "id12")

        when (body) {
            is V3Body.Announce -> {
                bytes(body.pubkey, 33, "pubkey")
                require(body.pubkey[0].toInt() and 0xFF in setOf(2, 3)) {
                    "pubkey must be compressed"
                }
                val nick = body.nick.toByteArray(Charsets.UTF_8)
                require(nick.size in 1..20) { "nick must be 1..20 UTF-8 bytes" }
                u8(body.caps)
                u8(nick.size)
                out.write(nick)
            }
            is V3Body.Listing -> {
                u8(body.crop)
                u32(body.qtyKg)
                u32(body.askPaise)
                u8(body.region)
                u32(body.validUntil)
                u8(body.grade)
                id12(body.batchId)
            }
            is V3Body.Order -> {
                id12(body.listingId)
                u32(body.qtyKg)
                u32(body.bidPaise)
                u32(body.validUntil)
            }
            is V3Body.OrderAccept -> id12(body.orderId)
            is V3Body.OrderCancel -> id12(body.orderId)
            is V3Body.Delivered -> id12(body.orderId)
            is V3Body.BarterOffer -> {
                u8(body.giveCrop)
                u32(body.giveKg)
                u8(body.wantCrop)
                u32(body.wantKg)
                u8(body.region)
                u32(body.validUntil)
            }
            is V3Body.BarterAccept -> id12(body.offerId)
            is V3Body.TamperAlert -> {
                id12(body.batchId)
                u8(body.kind)
                i32(body.lat)
                i32(body.lon)
                u32(body.detectedTs)
            }
            is V3Body.BatchRegister -> {
                u8(body.crop)
                u32(body.weightKg)
                u32(body.harvestTs)
                u8(body.region)
                u8(body.grade)
            }
            is V3Body.Bind -> {
                id12(body.batchId)
                bytes(body.boxId, V3.ACTOR_ID_LEN, "boxId")
            }
            is V3Body.PriceTable -> {
                require(body.rows.size in 1..19) { "price table needs 1..19 rows" }
                require(body.part in 1..body.parts && body.parts <= 0xFF) { "bad part/parts" }
                u8(body.region)
                u32(body.version)
                u32(body.asOf)
                u8(body.part)
                u8(body.parts)
                u8(body.rows.size)
                body.rows.forEach { row ->
                    u8(row.crop)
                    u24(row.pricePaise)
                    u8(row.trades)
                }
            }
            is V3Body.PriceBeacon -> {
                u8(body.region)
                u32(body.version)
                bytes(body.hash8, 8, "hash8")
            }
            is V3Body.Receipt -> {
                bytes(body.msgId, V3.MSG_ID_LEN, "msgId")
                u8(body.status)
                u8(body.reason)
                u32(body.seq)
                bytes(body.head8, 8, "head8")
            }
            is V3Body.Notice -> {
                u8(body.kind)
                id12(body.refId)
                u32(body.seq)
                u32(body.qtyKg)
                u32(body.pricePaise)
            }
        }
        val encoded = out.toByteArray()
        require(encoded.size <= V3.MAX_BODY) { "body over ${V3.MAX_BODY} bytes" }
        return encoded
    }

    fun parse(type: V3Type, body: ByteArray): V3Body {
        require(body.size <= V3.MAX_BODY) { "body over ${V3.MAX_BODY} bytes" }
        val reader = Reader(body)
        val parsed: V3Body = when (type) {
            V3Type.ANNOUNCE -> {
                val pubkey = reader.take(33, "pubkey")
                require(pubkey[0].toInt() and 0xFF in setOf(2, 3)) { "pubkey must be compressed" }
                val caps = reader.u8()
                val nickLen = reader.u8()
                require(nickLen in 1..20) { "bad nickLen" }
                V3Body.Announce(pubkey, caps, reader.take(nickLen, "nick").toString(Charsets.UTF_8))
            }
            V3Type.LISTING -> V3Body.Listing(
                reader.u8(), reader.u32(), reader.u32(), reader.u8(), reader.u32(),
                reader.u8(), reader.take(V3.ID12_LEN, "batchId"),
            )
            V3Type.ORDER -> V3Body.Order(
                reader.take(V3.ID12_LEN, "listingId"), reader.u32(), reader.u32(), reader.u32(),
            )
            V3Type.ORDER_ACCEPT -> V3Body.OrderAccept(reader.take(V3.ID12_LEN, "orderId"))
            V3Type.ORDER_CANCEL -> V3Body.OrderCancel(reader.take(V3.ID12_LEN, "orderId"))
            V3Type.DELIVERED -> V3Body.Delivered(reader.take(V3.ID12_LEN, "orderId"))
            V3Type.BARTER_OFFER -> V3Body.BarterOffer(
                reader.u8(), reader.u32(), reader.u8(), reader.u32(), reader.u8(), reader.u32(),
            )
            V3Type.BARTER_ACCEPT -> V3Body.BarterAccept(reader.take(V3.ID12_LEN, "offerId"))
            V3Type.TAMPER_ALERT -> V3Body.TamperAlert(
                reader.take(V3.ID12_LEN, "batchId"), reader.u8(), reader.i32(), reader.i32(), reader.u32(),
            )
            V3Type.BATCH_REGISTER -> V3Body.BatchRegister(
                reader.u8(), reader.u32(), reader.u32(), reader.u8(), reader.u8(),
            )
            V3Type.BIND -> V3Body.Bind(
                reader.take(V3.ID12_LEN, "batchId"), reader.take(V3.ACTOR_ID_LEN, "boxId"),
            )
            V3Type.PRICE_TABLE -> {
                val region = reader.u8()
                val version = reader.u32()
                val asOf = reader.u32()
                val part = reader.u8()
                val parts = reader.u8()
                val n = reader.u8()
                require(n in 1..19) { "bad table row count" }
                val rows = (0 until n).map {
                    V3Body.PriceRow(reader.u8(), reader.u24(), reader.u8())
                }
                V3Body.PriceTable(region, version, asOf, part, parts, rows)
            }
            V3Type.PRICE_BEACON -> V3Body.PriceBeacon(
                reader.u8(), reader.u32(), reader.take(8, "hash8"),
            )
            V3Type.RECEIPT -> V3Body.Receipt(
                reader.take(V3.MSG_ID_LEN, "msgId"), reader.u8(), reader.u8(), reader.u32(),
                reader.take(8, "head8"),
            )
            V3Type.NOTICE -> V3Body.Notice(
                reader.u8(), reader.take(V3.ID12_LEN, "refId"), reader.u32(), reader.u32(), reader.u32(),
            )
        }
        require(reader.remaining == 0) { "trailing bytes in ${type.name} body" }
        return parsed
    }

    /** JSON-friendly view, matching the `body` object in contracts/vectors.json. */
    fun toMap(body: V3Body): Map<String, Any> = when (body) {
        is V3Body.Announce -> mapOf(
            "pubkey" to V3Codec.hex(body.pubkey),
            "caps" to body.caps,
            "nick" to body.nick,
        )
        is V3Body.Listing -> mapOf(
            "crop" to body.crop,
            "qtyKg" to body.qtyKg,
            "askPaise" to body.askPaise,
            "region" to body.region,
            "validUntil" to body.validUntil,
            "grade" to body.grade,
            "batchId" to V3Codec.hex(body.batchId),
        )
        is V3Body.Order -> mapOf(
            "listingId" to V3Codec.hex(body.listingId),
            "qtyKg" to body.qtyKg,
            "bidPaise" to body.bidPaise,
            "validUntil" to body.validUntil,
        )
        is V3Body.OrderAccept -> mapOf("orderId" to V3Codec.hex(body.orderId))
        is V3Body.OrderCancel -> mapOf("orderId" to V3Codec.hex(body.orderId))
        is V3Body.Delivered -> mapOf("orderId" to V3Codec.hex(body.orderId))
        is V3Body.BarterOffer -> mapOf(
            "giveCrop" to body.giveCrop,
            "giveKg" to body.giveKg,
            "wantCrop" to body.wantCrop,
            "wantKg" to body.wantKg,
            "region" to body.region,
            "validUntil" to body.validUntil,
        )
        is V3Body.BarterAccept -> mapOf("offerId" to V3Codec.hex(body.offerId))
        is V3Body.TamperAlert -> mapOf(
            "batchId" to V3Codec.hex(body.batchId),
            "kind" to body.kind,
            "lat" to body.lat,
            "lon" to body.lon,
            "detectedTs" to body.detectedTs,
        )
        is V3Body.BatchRegister -> mapOf(
            "crop" to body.crop,
            "weightKg" to body.weightKg,
            "harvestTs" to body.harvestTs,
            "region" to body.region,
            "grade" to body.grade,
        )
        is V3Body.Bind -> mapOf(
            "batchId" to V3Codec.hex(body.batchId),
            "boxId" to V3Codec.hex(body.boxId),
        )
        is V3Body.PriceTable -> mapOf(
            "region" to body.region,
            "version" to body.version,
            "asOf" to body.asOf,
            "part" to body.part,
            "parts" to body.parts,
            "rows" to body.rows.map { listOf(it.crop, it.pricePaise, it.trades) },
        )
        is V3Body.PriceBeacon -> mapOf(
            "region" to body.region,
            "version" to body.version,
            "hash8" to V3Codec.hex(body.hash8),
        )
        is V3Body.Receipt -> mapOf(
            "msgId" to V3Codec.hex(body.msgId),
            "status" to body.status,
            "reason" to body.reason,
            "seq" to body.seq,
            "head8" to V3Codec.hex(body.head8),
        )
        is V3Body.Notice -> mapOf(
            "kind" to body.kind,
            "refId" to V3Codec.hex(body.refId),
            "seq" to body.seq,
            "qtyKg" to body.qtyKg,
            "pricePaise" to body.pricePaise,
        )
    }

    private class Reader(private val data: ByteArray) {
        private var offset = 0
        val remaining: Int get() = data.size - offset

        fun take(size: Int, name: String): ByteArray {
            require(size >= 0 && offset + size <= data.size) { "truncated $name" }
            return data.copyOfRange(offset, offset + size).also { offset += size }
        }

        fun u8(): Int = take(1, "u8")[0].toInt() and 0xFF

        fun u24(): Int {
            val raw = take(3, "u24")
            return ((raw[0].toInt() and 0xFF) shl 16) or
                ((raw[1].toInt() and 0xFF) shl 8) or
                (raw[2].toInt() and 0xFF)
        }

        fun u32(): Long = V3Codec.readU32(data, offset).also { offset += 4 }

        fun i32(): Int = u32().toInt()
    }

    fun scalarKey(scalar: Int): java.security.interfaces.ECPrivateKey =
        V3Codec.privateKeyFromScalar(BigInteger.valueOf(scalar.toLong()))
}
