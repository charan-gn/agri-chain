package com.agrichain.relay

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/** v3 wire constants. See contracts/MESSAGE_SPEC.md. */
object V3 {
    const val VERSION = 1
    const val MAX_MESSAGE = 198
    const val MAX_BODY = 110
    const val SIG_LEN = 64
    const val ACTOR_ID_LEN = 8
    const val MSG_ID_LEN = 12
    const val ID12_LEN = 12

    const val FLAG_HAS_TO = 0x01
    const val FLAG_LEDGER_SIGNED = 0x02
    const val FLAG_RESERVED = 0xFC
}

enum class V3Type(val code: Int) {
    ANNOUNCE(0x01),
    LISTING(0x10),
    ORDER(0x11),
    ORDER_ACCEPT(0x12),
    ORDER_CANCEL(0x13),
    DELIVERED(0x14),
    BARTER_OFFER(0x15),
    BARTER_ACCEPT(0x16),
    TAMPER_ALERT(0x20),
    BATCH_REGISTER(0x21),
    BIND(0x23),
    PRICE_TABLE(0x40),
    PRICE_BEACON(0x41),
    RECEIPT(0x42),
    NOTICE(0x43);

    val ledgerSigned: Boolean
        get() = this in LEDGER_TYPES

    companion object {
        private val LEDGER_TYPES = setOf(PRICE_TABLE, PRICE_BEACON, RECEIPT, NOTICE)
        fun fromCode(code: Int): V3Type =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("unknown message type 0x%02x".format(code))
    }
}

data class V3Message(
    val type: V3Type,
    val ts: Long,
    val fromId: ByteArray,
    val body: ByteArray,
    val flags: Int = 0,
    val toId: ByteArray? = null,
    val signature: ByteArray? = null,
) {
    val hasTo: Boolean get() = flags and V3.FLAG_HAS_TO != 0
    val ledgerSigned: Boolean get() = flags and V3.FLAG_LEDGER_SIGNED != 0

    override fun equals(other: Any?): Boolean =
        other is V3Message &&
            type == other.type &&
            ts == other.ts &&
            flags == other.flags &&
            fromId.contentEquals(other.fromId) &&
            (toId?.contentEquals(other.toId ?: ByteArray(0)) ?: (other.toId == null)) &&
            body.contentEquals(other.body) &&
            (signature?.contentEquals(other.signature ?: ByteArray(0)) ?: (other.signature == null))

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + ts.hashCode()
        result = 31 * result + flags
        result = 31 * result + fromId.contentHashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + (toId?.contentHashCode() ?: 0)
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }
}

/** Stateless codec: header, envelope, msgId, ECDSA raw r||s and P-256 helpers. */
object V3Codec {

    val curve: ECParameterSpec by lazy {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    fun encodeUnsigned(
        type: V3Type,
        fromId: ByteArray,
        body: ByteArray,
        ts: Long,
        toId: ByteArray? = null,
        flags: Int = 0,
    ): ByteArray {
        require(fromId.size == V3.ACTOR_ID_LEN) { "fromId must be 8 bytes" }
        require(body.size <= V3.MAX_BODY) { "body over ${V3.MAX_BODY} bytes" }
        require(flags and V3.FLAG_RESERVED == 0) { "reserved flag bits set" }
        var effectiveFlags = flags
        if (toId != null) {
            require(toId.size == V3.ACTOR_ID_LEN) { "toId must be 8 bytes" }
            effectiveFlags = effectiveFlags or V3.FLAG_HAS_TO
        } else {
            require(effectiveFlags and V3.FLAG_HAS_TO == 0) { "hasTo set without toId" }
        }
        require(ts in 0..0xFFFFFFFFL) { "ts out of u32 range" }

        val out = ByteArrayOutputStream()
        out.write(V3.VERSION)
        out.write(type.code)
        out.write(effectiveFlags)
        out.write(be32(ts))
        out.write(fromId)
        if (toId != null) out.write(toId)
        out.write(body.size)
        out.write(body)
        val bytes = out.toByteArray()
        require(bytes.size <= V3.MAX_MESSAGE - V3.SIG_LEN) { "unsigned message too large" }
        return bytes
    }

    fun decode(data: ByteArray): V3Message {
        require(data.size in 18..V3.MAX_MESSAGE) { "bad message length" }
        require(data[0].toInt() and 0xFF == V3.VERSION) { "unsupported version" }
        val type = V3Type.fromCode(data[1].toInt() and 0xFF)
        val flags = data[2].toInt() and 0xFF
        require(flags and V3.FLAG_RESERVED == 0) { "reserved flag bits set" }

        val hasTo = flags and V3.FLAG_HAS_TO != 0
        val headerLen = 16 + if (hasTo) V3.ACTOR_ID_LEN else 0
        require(data.size >= headerLen) { "truncated header" }

        val ts = readU32(data, 3)
        val fromId = data.copyOfRange(7, 15)
        val toId = if (hasTo) data.copyOfRange(15, 23) else null
        val bodyLenOffset = headerLen - 1
        val bodyLen = data[bodyLenOffset].toInt() and 0xFF
        require(bodyLen <= V3.MAX_BODY) { "body too large" }

        val unsignedLen = headerLen + bodyLen
        val signature = when (data.size) {
            unsignedLen -> null
            unsignedLen + V3.SIG_LEN -> {
                val sig = data.copyOfRange(unsignedLen, data.size)
                require(sig.any { it.toInt() != 0 }) { "empty signature" }
                sig
            }
            else -> throw IllegalArgumentException("length does not match bodyLen/signature")
        }
        return V3Message(
            type = type,
            ts = ts,
            fromId = fromId,
            body = data.copyOfRange(headerLen, unsignedLen),
            flags = flags,
            toId = toId,
            signature = signature,
        )
    }

    fun unsignedBytes(message: V3Message): ByteArray = encodeUnsigned(
        message.type,
        message.fromId,
        message.body,
        message.ts,
        message.toId,
        message.flags,
    )

    fun messageId(unsigned: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(unsigned).copyOf(V3.MSG_ID_LEN)

    fun msgId(message: V3Message): ByteArray = messageId(unsignedBytes(message))

    fun encodeEnvelope(message: ByteArray, ttl: Int): ByteArray {
        require(ttl in 1..255) { "ttl out of range" }
        require(message.size <= V3.MAX_MESSAGE) { "message too large" }
        return byteArrayOf(ttl.toByte()) + message
    }

    fun decodeEnvelope(data: ByteArray): Pair<Int, ByteArray> {
        require(data.size in 2..V3.MAX_MESSAGE + 1) { "bad envelope length" }
        val ttl = data[0].toInt() and 0xFF
        require(ttl > 0) { "ttl must be >= 1" }
        return ttl to data.copyOfRange(1, data.size)
    }

    fun sign(message: V3Message, privateKey: ECPrivateKey): ByteArray {
        require(message.signature == null) { "message already signed" }
        require(actorId(compressedPublicKey(privateKey)).contentEquals(message.fromId)) {
            "private key does not match fromId"
        }
        val unsigned = unsignedBytes(message)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(unsigned)
        return unsigned + derToRaw(signer.sign())
    }

    fun verify(
        message: V3Message,
        pubkeyCompressed: ByteArray,
        checkFromId: Boolean = true,
    ): Boolean = runCatching {
        val signature = message.signature ?: return false
        require(signature.size == V3.SIG_LEN)
        if (checkFromId && !actorId(pubkeyCompressed).contentEquals(message.fromId)) return false
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(decompressPublicKey(pubkeyCompressed))
        verifier.update(unsignedBytes(message))
        verifier.verify(rawToDer(signature))
    }.getOrDefault(false)

    fun verify(messageBytes: ByteArray, pubkeyCompressed: ByteArray): Boolean =
        runCatching { verify(decode(messageBytes), pubkeyCompressed) }.getOrDefault(false)

    fun actorId(pubkeyCompressed: ByteArray): ByteArray {
        require(pubkeyCompressed.size == 33) { "pubkey must be compressed" }
        require(pubkeyCompressed[0].toInt() and 0xFF in setOf(2, 3)) { "pubkey must be compressed" }
        return MessageDigest.getInstance("SHA-256").digest(pubkeyCompressed).copyOf(V3.ACTOR_ID_LEN)
    }

    fun compressedPublicKey(key: PublicKey): ByteArray {
        val pub = key as ECPublicKey
        val x = fixed(pub.w.affineX, 32)
        val prefix = if (pub.w.affineY.testBit(0)) 3 else 2
        return byteArrayOf(prefix.toByte()) + x
    }

    fun compressedPublicKey(key: PrivateKey): ByteArray {
        val ec = key as ECPrivateKey
        val pub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(scalarMultiply(ec.s, curve.generator), curve),
        )
        return compressedPublicKey(pub)
    }

    fun decompressPublicKey(encoded: ByteArray): ECPublicKey {
        require(encoded.size == 33) { "pubkey must be compressed" }
        val prefix = encoded[0].toInt() and 0xFF
        require(prefix == 2 || prefix == 3) { "pubkey must be compressed" }
        val p = P
        val x = BigInteger(1, encoded.copyOfRange(1, 33))
        require(x < p) { "x out of range" }
        val alpha = (x.modPow(BigInteger.valueOf(3), p) - x.multiply(THREE) + B).mod(p)
        var y = alpha.modPow(P_PLUS_1_OVER_4, p)
        require(y.multiply(y).mod(p) == alpha) { "point not on curve" }
        if ((y.testBit(0)) != (prefix == 3)) y = p.subtract(y)
        return KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(x, y), curve),
        ) as ECPublicKey
    }

    fun privateKeyFromScalar(scalar: BigInteger): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(scalar, curve)) as ECPrivateKey

    fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "odd hex length" }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    internal fun be32(value: Long): ByteArray {
        require(value in 0..0xFFFFFFFFL) { "value out of u32 range" }
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
    }

    internal fun readU32(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private val P: BigInteger =
        BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B: BigInteger =
        BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val THREE = BigInteger.valueOf(3)
    private val P_PLUS_1_OVER_4: BigInteger = P.add(BigInteger.ONE).shiftRight(2)

    private fun scalarMultiply(scalar: BigInteger, point: ECPoint): ECPoint {
        // Small helper for deriving the public key from a test private scalar.
        var result: ECPoint? = null
        var addend = point
        var k = scalar
        while (k.signum() > 0) {
            if (k.testBit(0)) result = if (result == null) addend else add(result, addend)
            addend = add(addend, addend)
            k = k.shiftRight(1)
        }
        return result ?: throw IllegalArgumentException("zero scalar")
    }

    private fun add(a: ECPoint, b: ECPoint): ECPoint {
        val p = P
        if (a == ECPoint.POINT_INFINITY) return b
        if (b == ECPoint.POINT_INFINITY) return a
        if (a.affineX == b.affineX) {
            if (a.affineY.add(b.affineY).mod(p) == BigInteger.ZERO) return ECPoint.POINT_INFINITY
            // P-256 has a = -3, so the tangent slope is 3(x^2 - 1) / 2y.
            val lambda = a.affineX.multiply(a.affineX).subtract(BigInteger.ONE)
                .multiply(THREE)
                .multiply(a.affineY.shiftLeft(1).modInverse(p)).mod(p)
            val x = lambda.multiply(lambda).subtract(a.affineX.shiftLeft(1)).mod(p)
            val y = lambda.multiply(a.affineX.subtract(x)).subtract(a.affineY).mod(p)
            return ECPoint(x, y)
        }
        val lambda = b.affineY.subtract(a.affineY)
            .multiply(b.affineX.subtract(a.affineX).modInverse(p)).mod(p)
        val x = lambda.multiply(lambda).subtract(a.affineX).subtract(b.affineX).mod(p)
        val y = lambda.multiply(a.affineX.subtract(x)).subtract(a.affineY).mod(p)
        return ECPoint(x, y)
    }

    private fun fixed(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(size)
        val copy = if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size) else raw
        copy.copyInto(out, size - copy.size)
        return out
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var offset = 0
        require(der[offset++].toInt() and 0xFF == 0x30) { "bad DER sequence" }
        val (seqLen, seqOffset) = readDerLength(der, offset)
        offset = seqOffset
        require(offset + seqLen == der.size) { "bad DER sequence length" }

        require(der[offset++].toInt() and 0xFF == 0x02) { "bad DER r" }
        val (rLen, rOffset) = readDerLength(der, offset)
        val r = BigInteger(1, der.copyOfRange(rOffset, rOffset + rLen))
        offset = rOffset + rLen

        require(der[offset++].toInt() and 0xFF == 0x02) { "bad DER s" }
        val (sLen, sOffset) = readDerLength(der, offset)
        val s = BigInteger(1, der.copyOfRange(sOffset, sOffset + sLen))
        require(sOffset + sLen == der.size) { "trailing DER bytes" }
        return fixed(r, 32) + fixed(s, 32)
    }

    private fun readDerLength(der: ByteArray, offset: Int): Pair<Int, Int> {
        val first = der[offset].toInt() and 0xFF
        return if (first < 0x80) {
            first to offset + 1
        } else {
            val count = first and 0x7F
            require(count in 1..2) { "unsupported DER length" }
            var length = 0
            for (index in 0 until count) {
                length = (length shl 8) or (der[offset + 1 + index].toInt() and 0xFF)
            }
            length to offset + 1 + count
        }
    }

    private fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == V3.SIG_LEN) { "raw signature must be 64 bytes" }
        val r = derInteger(BigInteger(1, raw.copyOfRange(0, 32)))
        val s = derInteger(BigInteger(1, raw.copyOfRange(32, 64)))
        val content = r + s
        require(content.size < 0x80) { "signature too long for short DER length" }
        return byteArrayOf(0x30, content.size.toByte()) + content
    }

    private fun derInteger(value: BigInteger): ByteArray {
        var bytes = value.toByteArray()
        if (bytes.size > 1 && bytes[0].toInt() == 0 && bytes[1].toInt() and 0x80 == 0) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        return byteArrayOf(0x02, bytes.size.toByte()) + bytes
    }
}
