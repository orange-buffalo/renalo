package io.orangebuffalo.renalo.user

import io.micronaut.context.annotation.Value
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.event.ApplicationStartupEvent
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

@Singleton
class DefaultAdminInitializer(
    private val userRepository: UserRepository,
    private val passwordHasher: PasswordHasher,
    @param:Value("\${renalo.default-admin.username:admin}") private val adminUsername: String,
) : ApplicationEventListener<ApplicationStartupEvent> {
    private val logger = LoggerFactory.getLogger(DefaultAdminInitializer::class.java)
    private val random = SecureRandom()

    override fun onApplicationEvent(event: ApplicationStartupEvent) {
        if (userRepository.countByType(UserType.ADMIN) > 0) {
            return
        }

        val password = generatedPassword()
        userRepository.save(
            User(
                username = adminUsername,
                passwordHash = passwordHasher.hash(password),
                type = UserType.ADMIN,
            ),
        )

        logger.warn(
            """

            ============================================================
            Renalo default admin user created
            ------------------------------------------------------------
            Username: $adminUsername
            Password: $password
            ------------------------------------------------------------
            Store these credentials now. The generated password is only
            logged at creation time.
            ============================================================
            """.trimIndent(),
        )
    }

    private fun generatedPassword(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
