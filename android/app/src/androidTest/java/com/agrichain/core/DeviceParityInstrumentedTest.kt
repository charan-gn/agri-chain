package com.agrichain.core

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

@RunWith(AndroidJUnit4::class)
class DeviceParityInstrumentedTest {

    private fun toKotlinValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> linkedMapOf<String, Any?>().apply {
            value.keys().forEach { key -> put(key, toKotlinValue(value.get(key))) }
        }
        is JSONArray -> (0 until value.length()).map { idx -> toKotlinValue(value.get(idx)) }
        else -> value
    }

    @Test
    fun canonicalVectorsMatchOnDevice() {
        val instrContext = InstrumentationRegistry.getInstrumentation().context
        val raw = instrContext.assets.open("vectors.json").bufferedReader(StandardCharsets.UTF_8).use {
            it.readText()
        }
        val suite = JSONObject(raw)
        val vectors = suite.getJSONArray("vectors")
        assertTrue("Expected >= 12 vectors", vectors.length() >= 12)

        for (i in 0 until vectors.length()) {
            val item = vectors.getJSONObject(i)
            val name = item.getString("name")
            @Suppress("UNCHECKED_CAST")
            val eventFields = toKotlinValue(item.getJSONObject("event")) as Map<String, Any?>
            val expectedCanonical = item.getString("canonical")
            val expectedSha256 = item.getString("sha256")

            val actualCanonical = EventCodec.canonical(eventFields)
            assertEquals("canonical mismatch for $name", expectedCanonical, actualCanonical)

            val actualSha256 = EventCodec.sha256Hex(actualCanonical.toByteArray(StandardCharsets.UTF_8))
            assertEquals("sha256 mismatch for $name", expectedSha256, actualSha256)
        }

        val reject = suite.getJSONArray("reject")
        for (i in 0 until reject.length()) {
            val item = reject.getJSONObject(i)
            val name = item.getString("name")
            val value = toKotlinValue(item.get("value"))
            assertThrows("Expected rejection for $name", IllegalArgumentException::class.java) {
                CanonicalJson.encode(value)
            }
        }
    }

    @Test
    fun androidKeyStoreSignsAndVerifiesCanonicalBytes() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = ActorIdentity.loadOrCreate(targetContext, "farmer")
        val payload = """{"crop":"వరి","note":"🌾"}""".toByteArray(StandardCharsets.UTF_8)
        val sigBytes = identity.sign(payload)

        val spkiBytes = Base64.decode(identity.publicKeySpkiBase64, Base64.NO_WRAP)
        val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spkiBytes))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pubKey)
        verifier.update(payload)
        assertTrue("AndroidKeyStore ECDSA signature must verify", verifier.verify(sigBytes))
    }
}
