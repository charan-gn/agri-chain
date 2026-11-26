package com.agrichain.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * v3 identity: one EC P-256 key in AndroidKeyStore. The Keystore returns DER
 * signatures, so [sign] converts them to the raw 64-byte r||s form on the wire.
 *
 * actorId = first 8 bytes of SHA-256(compressed public key), as in the spec.
 */
class V3Identity private constructor(
    override val actorId: ByteArray,
    override val publicKey: ByteArray,
    private val privateKey: PrivateKey,
) : V3Signer {

    val actorHex: String get() = V3Codec.hex(actorId)
    val pubkeyHex: String get() = V3Codec.hex(publicKey)

    override fun sign(unsigned: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(unsigned)
        return V3Codec.derToRaw(signature.sign())
    }

    companion object {
        private const val TAG = "AgriMesh"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "agrichain-v3-identity"

        fun loadOrCreate(context: Context): V3Identity {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
                    .apply {
                        initialize(
                            KeyGenParameterSpec.Builder(
                                ALIAS,
                                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                            )
                                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                                .setDigests(KeyProperties.DIGEST_SHA256)
                                .build(),
                        )
                    }
                    .generateKeyPair()
                Log.i(TAG, "identity: generated v3 keystore key")
            }
            val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            val publicKey = V3Codec.compressedPublicKey(entry.certificate.publicKey)
            val actorId = V3Codec.actorId(publicKey)
            Log.i(TAG, "identity: actor=${V3Codec.hex(actorId)}")
            return V3Identity(actorId, publicKey, entry.privateKey)
        }
    }
}

/** Actor registration for `POST /v3/actors` (admin token required). */
object V3Registration {

    fun register(
        baseUrl: String,
        adminToken: String,
        identity: V3Identity,
        role: String,
        name: String,
        region: Int,
        phone: String?,
    ): JSONObject {
        val payload = JSONObject()
            .put("pubkey", identity.pubkeyHex)
            .put("role", role)
            .put("name", name)
            .put("region", region)
        if (!phone.isNullOrBlank()) payload.put("phone", phone)
        return request(
            "${baseUrl.trimEnd('/')}/v3/actors",
            "POST",
            payload,
            adminToken,
        )
    }

    /** `GET /v3/ledger` returns the pinned node key and the dictionary versions. */
    fun ledgerInfo(baseUrl: String): JSONObject = request(
        "${baseUrl.trimEnd('/')}/v3/ledger",
        "GET",
        null,
        null,
    )

    private fun request(
        url: String,
        method: String,
        payload: JSONObject?,
        adminToken: String?,
    ): JSONObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            if (!adminToken.isNullOrBlank()) {
                connection.setRequestProperty("X-Admin-Token", adminToken)
            }
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("$method $url -> $code: ${text.take(300)}")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
