package com.aird.chat.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Same construction as the desktop client: P-256 ECDH, HKDF-SHA-256 with
 * info "aird-chat-e2e-v1", then AES-GCM. Message ciphertext uses the
 * conversation id as additional data. Key wraps do not.
 */
object ChatCrypto {
    private const val INFO = "aird-chat-e2e-v1"
    private val random = SecureRandom()

    data class Identity(val x: String, val y: String, val d: String) {
        fun publicJson(): String = """{"kty":"EC","crv":"P-256","x":"$x","y":"$y"}"""
        fun privateJson(): String = """{"kty":"EC","crv":"P-256","x":"$x","y":"$y","d":"$d"}"""
        fun kid(): String = fingerprint(x, y)
    }

    data class Wrap(
        val kid: String,
        val epkX: String,
        val epkY: String,
        val iv: String,
        val ct: String,
    )

    data class Sealed(val iv: String, val ct: String)

    fun fingerprint(x: String, y: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$x.$y".toByteArray(Charsets.UTF_8))
        return b64(digest).trimEnd('=')
    }

    fun generateIdentity(): Identity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        val pub = pair.public as java.security.interfaces.ECPublicKey
        val priv = pair.private as java.security.interfaces.ECPrivateKey
        return Identity(
            x = b64url(fixed32(pub.w.affineX)),
            y = b64url(fixed32(pub.w.affineY)),
            d = b64url(fixed32(priv.s)),
        )
    }

    fun unwrap(identity: Identity, epkX: String, epkY: String, convId: String, iv: String, ct: String): ByteArray {
        val key = deriveWrapKey(privateKey(identity.d), publicKey(epkX, epkY), convId)
        return aesDecrypt(key, unb64(iv), unb64(ct), aad = null)
    }

    fun wrap(rawKey: ByteArray, recipientX: String, recipientY: String, convId: String): Wrap {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val eph = generator.generateKeyPair()
        val pub = eph.public as java.security.interfaces.ECPublicKey
        val key = deriveWrapKey(eph.private, publicKey(recipientX, recipientY), convId)
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val ct = aesEncrypt(key, iv, rawKey, aad = null)
        return Wrap(
            kid = fingerprint(recipientX, recipientY),
            epkX = b64url(fixed32(pub.w.affineX)),
            epkY = b64url(fixed32(pub.w.affineY)),
            iv = b64(iv),
            ct = b64(ct),
        )
    }

    fun randomKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun encrypt(rawKey: ByteArray, convId: String, plaintext: String): Sealed {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val ct = aesEncrypt(rawKey, iv, plaintext.toByteArray(Charsets.UTF_8), convId.toByteArray(Charsets.UTF_8))
        return Sealed(b64(iv), b64(ct))
    }

    fun decrypt(rawKey: ByteArray, convId: String, iv: String, ct: String): String {
        val plain = aesDecrypt(rawKey, unb64(iv), unb64(ct), convId.toByteArray(Charsets.UTF_8))
        return plain.toString(Charsets.UTF_8)
    }

    private fun deriveWrapKey(privateKey: PrivateKey, publicKey: PublicKey, convId: String): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return hkdf(fixed32(agreement.generateSecret()), convId.toByteArray(Charsets.UTF_8), INFO.toByteArray(Charsets.UTF_8))
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val saltKey = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(saltKey, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1)
        return mac.doFinal().copyOf(32)
    }

    private fun aesEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    private fun aesDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    private fun privateKey(d: String): PrivateKey {
        val secret = BigInteger(1, unb64(d))
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(secret, ecParams()))
    }

    private fun publicKey(x: String, y: String): PublicKey {
        val point = ECPoint(BigInteger(1, unb64(x)), BigInteger(1, unb64(y)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, ecParams()))
    }

    private fun ecParams(): ECParameterSpec {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        return params.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun fixed32(value: BigInteger): ByteArray = fixed32(value.toByteArray())

    private fun fixed32(raw: ByteArray): ByteArray {
        if (raw.size == 32) return raw
        if (raw.size > 32) return raw.copyOfRange(raw.size - 32, raw.size)
        val out = ByteArray(32)
        raw.copyInto(out, 32 - raw.size)
        return out
    }

    private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    private fun b64url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun unb64(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        val pad = when (normalized.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.getDecoder().decode(normalized + pad)
    }
}
