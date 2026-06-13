package app.renalo.user

import java.security.SecureRandom
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
}
