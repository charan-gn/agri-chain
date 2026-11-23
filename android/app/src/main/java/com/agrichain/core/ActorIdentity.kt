package com.agrichain.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class ActorIdentity private constructor(
    override val actorId: String,
    private val privateKey: PrivateKey,
    val publicKeySpkiBase64: String,
) : Signer {

    override fun sign(bytes: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(bytes)
        return signature.sign()
    }

    companion object {
        private const val PREFS = "agrichain_identity"
        private const val KEYSTORE = "AndroidKeyStore"

        fun loadOrCreate(context: Context, role: String): ActorIdentity {
            val keyAlias = "agrichain-$role"
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

            if (!keyStore.containsAlias(keyAlias)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
                    .apply {
                        initialize(
                            KeyGenParameterSpec.Builder(
                                keyAlias,
                                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                            )
                                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                                .setDigests(KeyProperties.DIGEST_SHA256)
                                .build()
                        )
                    }
                    .generateKeyPair()
            }

            val entry = keyStore.getEntry(keyAlias, null) as KeyStore.PrivateKeyEntry
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val storedId = prefs.getString("actorId-$role", null)
            val actorId = storedId ?: "$role:${UUID.randomUUID()}".also {
                prefs.edit().putString("actorId-$role", it).apply()
            }

            return ActorIdentity(
                actorId = actorId,
                privateKey = entry.privateKey,
                publicKeySpkiBase64 = Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP),
            )
        }
    }
}
