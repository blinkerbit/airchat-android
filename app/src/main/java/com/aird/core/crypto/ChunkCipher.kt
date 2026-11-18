package com.aird.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object KeyDerivation {
    fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int = 100_000, bits: Int = 256): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, bits)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

data class SealedChunk(val bytes: ByteArray, val choice: CipherChoice)

object ChunkCipher {
    private val random = SecureRandom()

    fun seal(plain: ByteArray, key: ByteArray, choice: CipherChoice, aad: ByteArray = ByteArray(0)): SealedChunk {
        val body = when (choice) {
            CipherChoice.AES_256_GCM -> sealAes(plain, key, aad)
            CipherChoice.ASCON_128A -> sealAscon(plain, key, aad)
        }
        return SealedChunk(body, choice)
    }

    fun open(sealed: SealedChunk, key: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray = when (sealed.choice) {
        CipherChoice.AES_256_GCM -> openAes(sealed.bytes, key, aad)
        CipherChoice.ASCON_128A -> AsconAead.decrypt(key.copyOf(16), sealed.bytes, aad) ?: error("authentication failed")
    }

    private fun sealAes(plain: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val body = cipher.doFinal(plain)
        return iv + body
    }

    private fun openAes(blob: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size >= 12 + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    private fun sealAscon(plain: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(16).also { random.nextBytes(it) }
        val body = AsconAead.encrypt(key.copyOf(16), nonce, aad, plain)
        return nonce + body
    }
}

/** Compress-then-encrypt. The live upload path leaves both stages off. */
class ChunkPipeline(
    private val compress: (ByteArray) -> ByteArray = { it },
    private val choice: CipherChoice = CipherChoice.AES_256_GCM,
) {
    fun protect(plain: ByteArray, key: ByteArray): SealedChunk =
        ChunkCipher.seal(compress(plain), key, choice)
}
