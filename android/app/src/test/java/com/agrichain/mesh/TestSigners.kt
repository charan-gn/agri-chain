package com.agrichain.mesh

import com.agrichain.core.PublicKeySigner
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** JVM P-256 signer for signed-ACK tests (AndroidKeyStore is device-only). */
object TestSigners {

    fun create(actorId: String = "mesh:11111111-1111-4111-8111-111111111111"): PublicKeySigner {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        return object : PublicKeySigner {
            override val actorId = actorId
            override val publicKeySpkiBase64 = publicKey
            override fun sign(bytes: ByteArray): ByteArray {
                val signature = Signature.getInstance("SHA256withECDSA")
                signature.initSign(pair.private)
                signature.update(bytes)
                return signature.sign()
            }
        }
    }
}
