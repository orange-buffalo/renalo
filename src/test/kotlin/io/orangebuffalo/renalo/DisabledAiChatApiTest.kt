package io.orangebuffalo.renalo

import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class DisabledAiChatApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun rejectsTheChatEndpoint() {
        userRepository.save(
            User(
                username = "alice",
                passwordHash = passwordHasher.hash("password"),
                type = UserType.USER,
            ),
        )
        val token = api().login("alice", "password")

        api().postJson(
            "/api/ai-chat/messages",
            """{ "content": "Hello" }""",
            token,
        ).statusCode().shouldBe(403)
        api().get("/api/ai-chat/conversations", token).statusCode().shouldBe(403)
        api().get("/api/ai-chat/conversations/1/history", token).statusCode().shouldBe(403)
    }
}
