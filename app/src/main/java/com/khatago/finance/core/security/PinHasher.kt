package com.khatago.finance.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local-only app-lock credential storage.
 *
 * KhataGo has no accounts and no server, so the only secret it ever handles is the optional
 * 4-6 digit PIN. It is stored as a PBKDF2WithHmacSHA256 digest with a random per-install salt —
 * never as the PIN itself — inside the app's private storage. There are no API keys, tokens or
 * credentials anywhere in this project (see docs/PRIVACY.md and the release audit in
 * docs/RELEASE.md).
 *
 * Threat model, stated honestly: this protects against a person holding an unlocked phone. It
 * does not protect a stolen, powered-off device from a forensic extraction; that is what the
 * device's own full-disk encryption is for, and KhataGo relies on it rather than pretending to
 * replace it.
 */
object PinHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    /** Encoded as `algorithm:iterations:salt(hex):hash(hex)` so old records keep verifying. */
    fun encode(pin: String): String {
        require(isValidPin(pin)) { "PIN must be 4-8 digits" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS)
        return "$ALGORITHM:$ITERATIONS:${salt.toHexString()}:${hash.toHexString()}"
    }

    fun matches(pin: String, encoded: String?): Boolean {
        if (encoded.isNullOrBlank()) return false
        val parts = encoded.split(":")
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = parts[2].toByteArrayFromHex() ?: return false
        val expected = parts[3].toByteArrayFromHex() ?: return false
        val actual = derive(pin, salt, iterations)
        // Constant-time comparison; MessageDigest.isEqual does not short-circuit on length.
        return MessageDigest.isEqual(expected, actual)
    }

    fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.toByteArrayFromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { i ->
                substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
