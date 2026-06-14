package io.orangebuffalo.renalo.user

import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import jakarta.inject.Singleton

@Singleton
class PasswordHasher {
    private val secureRandom = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val iterations = 210_000
        val keyLength = 256
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLength)
        val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "pbkdf2-sha256:$iterations:${encoder.encodeToString(salt)}:${encoder.encodeToString(encoded)}"
    }

    fun verify(password: String, passwordHash: String): Boolean {
        val parts = passwordHash.split(":")
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") {
            return false
        }

        val iterations = parts[1].toIntOrNull() ?: return false
        val decoder = Base64.getUrlDecoder()
        val salt = runCatching { decoder.decode(parts[2]) }.getOrElse { return false }
        val expected = runCatching { decoder.decode(parts[3]) }.getOrElse { return false }
        val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, expected.size * 8))
            .encoded

        return MessageDigest.isEqual(expected, actual)
    }
}
