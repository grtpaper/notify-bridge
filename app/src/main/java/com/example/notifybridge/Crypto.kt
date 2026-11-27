package com.example.notifybridge

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional protection that replaces Bluetooth pairing. Both devices are given the same security
 * code; the phone encrypts every message with a key made from that code, so anything that is not
 * the e-reader sees only scrambled data. Encrypted payload layout: [1][12-byte IV][ciphertext+tag].
 * Plain (unprotected) payloads are JSON and start with '{', so the two are easy to tell apart.
 */
object Crypto {
    const val MIN_CODE_LENGTH = 8
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 10_000
    private val SALT = "NotifyBridge-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    private var cachedCode: String? = null
    private var cachedKey: SecretKeySpec? = null

    fun isValidCode(code: String?): Boolean = code != null && code.length >= MIN_CODE_LENGTH

    fun isEncrypted(payload: ByteArray): Boolean = payload.isNotEmpty() && payload[0] == VERSION

    @Synchronized
    private fun key(code: String): SecretKeySpec {
        val cached = cachedKey
        if (cached != null && code == cachedCode) return cached
        val spec = PBEKeySpec(code.toCharArray(), SALT, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val k = SecretKeySpec(bytes, "AES")
        cachedCode = code
        cachedKey = k
        return k
    }

    fun encrypt(plain: ByteArray, code: String): ByteArray {
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plain)
        val out = ByteArray(1 + IV_BYTES + body.size)
        out[0] = VERSION
        System.arraycopy(iv, 0, out, 1, IV_BYTES)
        System.arraycopy(body, 0, out, 1 + IV_BYTES, body.size)
        return out
    }

    /** Returns the original bytes, or null if the code is wrong or the data was altered. */
    fun decrypt(payload: ByteArray, code: String): ByteArray? {
        if (payload.size < 1 + IV_BYTES + TAG_BITS / 8) return null
        return try {
            val iv = payload.copyOfRange(1, 1 + IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(payload, 1 + IV_BYTES, payload.size - 1 - IV_BYTES)
        } catch (e: Exception) {
            null
        }
    }
}
