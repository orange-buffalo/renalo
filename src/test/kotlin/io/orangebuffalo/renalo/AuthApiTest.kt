package io.orangebuffalo.renalo

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.auth.RememberMeTokenRepository
import io.orangebuffalo.renalo.auth.passkeys.PasskeyChallengeRepository
import io.orangebuffalo.renalo.auth.passkeys.PasskeyCredential
import io.orangebuffalo.renalo.auth.passkeys.PasskeyCredentialRepository
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class AuthApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var rememberMeTokenRepository: RememberMeTokenRepository

    @Inject
    lateinit var passkeyChallengeRepository: PasskeyChallengeRepository

    @Inject
    lateinit var passkeyCredentialRepository: PasskeyCredentialRepository

    private val objectMapper = ObjectMapper()

    @Test
    fun rejectsInvalidCredentials() {
        saveUser("alice", "correct-password", UserType.USER)

        val response = api().postJson(
            "/api/create-auth-token",
            """
                {"username":"alice","password":"wrong-password"}
            """.trimIndent(),
            null,
        )

        response.statusCode().shouldBe(401)
    }

    @Test
    fun rejectsInactiveUsersAsInvalidCredentials() {
        userRepository.save(
            User(
                username = "alice",
                passwordHash = passwordHasher.hash("correct-password"),
                type = UserType.USER,
                active = false,
            ),
        )

        val response = api().postJson(
            "/api/create-auth-token",
            """
                {"username":"alice","password":"correct-password"}
            """.trimIndent(),
            null,
        )

        response.statusCode().shouldBe(401)
    }

    @Test
    fun rejectsPasswordLoginForPasswordDisabledAccountOnlyWhenPasswordIsCorrect() {
        val user = saveUser("alice", "correct-password", UserType.USER)
        user.passwordSignInDisabled = true
        userRepository.update(user)

        api().postJson(
            "/api/create-auth-token",
            """
                {"username":"alice","password":"wrong-password"}
            """.trimIndent(),
            null,
        ).statusCode().shouldBe(401)

        val response = api().postJson(
            "/api/create-auth-token",
            """
                {"username":"alice","password":"correct-password"}
            """.trimIndent(),
            null,
        )

        response.statusCode().shouldBe(409)
        response.body().shouldEqualJson(
            """
                {
                  "code": "PASSWORD_SIGN_IN_DISABLED"
                }
            """.trimIndent(),
        )
    }

    @Test
    fun issuesTokenAndReturnsProfile() {
        saveUser("alice", "correct-password", UserType.USER)

        val token = api().login("alice", "correct-password")
        val profileResponse = api().get("/api/profile", token)

        profileResponse.statusCode().shouldBe(200)
        profileResponse.body().shouldEqualJson(
            """
                {
                  "username": "alice",
                  "type": "USER",
                  "passwordSignInDisabled": false,
                  "issueRefreshTokenOnPasskeyLogin": true
                }
            """.trimIndent(),
        )
    }

    @Test
    fun requiresTokenForProfile() {
        val response = api().get("/api/profile", null)

        response.statusCode().shouldBe(401)
    }

    @Test
    fun changesCurrentUsersPassword() {
        saveUser("alice", "old-password", UserType.USER)
        val token = api().login("alice", "old-password")

        val response = api().patchJson(
            "/api/profile/password",
            """
                {"currentPassword":"old-password","newPassword":"new-password"}
            """.trimIndent(),
            token,
        )

        response.statusCode().shouldBe(204)
        api().postJson(
            "/api/create-auth-token",
            """
                {"username":"alice","password":"old-password"}
            """.trimIndent(),
            null,
        ).statusCode().shouldBe(401)
        api().login("alice", "new-password").shouldNotBeBlank()
    }

    @Test
    fun requiresTokenForPasswordChange() {
        val response = api().patchJson(
            "/api/profile/password",
            """
                {"currentPassword":"old-password","newPassword":"new-password"}
            """.trimIndent(),
            null,
        )

        response.statusCode().shouldBe(401)
    }

    @Test
    fun disablesAndEnablesPasswordSignInWhenPasskeyExists() {
        val user = saveUser("alice", "correct-password", UserType.USER)
        savePasskey(user)
        val token = api().login("alice", "correct-password")

        val disableResponse = api().post("/api/profile/disable-password-sign-in", token)

        disableResponse.statusCode().shouldBe(200)
        disableResponse.body().shouldEqualJson(
            """
                {
                  "username": "alice",
                  "type": "USER",
                  "passwordSignInDisabled": true,
                  "issueRefreshTokenOnPasskeyLogin": true
                }
            """.trimIndent(),
        )
        userRepository.findByUsername("alice")!!.passwordSignInDisabled.shouldBe(true)

        val enableResponse = api().post("/api/profile/enable-password-sign-in", token)

        enableResponse.statusCode().shouldBe(200)
        enableResponse.body().shouldEqualJson(
            """
                {
                  "username": "alice",
                  "type": "USER",
                  "passwordSignInDisabled": false,
                  "issueRefreshTokenOnPasskeyLogin": true
                }
            """.trimIndent(),
        )
        userRepository.findByUsername("alice")!!.passwordSignInDisabled.shouldBe(false)
    }

    @Test
    fun rejectsPasswordSignInDisableWithoutPasskeys() {
        saveUser("alice", "correct-password", UserType.USER)
        val token = api().login("alice", "correct-password")

        val response = api().post("/api/profile/disable-password-sign-in", token)

        response.statusCode().shouldBe(409)
        response.body().shouldEqualJson(
            """
                {
                  "code": "PASSWORD_SIGN_IN_REQUIRES_PASSKEY"
                }
            """.trimIndent(),
        )
    }

    @Test
    fun removingLastPasskeyReEnablesPasswordSignIn() {
        val user = saveUser("alice", "correct-password", UserType.USER)
        val passkey = savePasskey(user)
        val token = api().login("alice", "correct-password")
        user.passwordSignInDisabled = true
        userRepository.update(user)

        val response = api().delete("/api/profile/passkeys/${passkey.id}", token)

        response.statusCode().shouldBe(204)
        userRepository.findByUsername("alice")!!.passwordSignInDisabled.shouldBe(false)
    }

    @Test
    fun requiresTokenForPasswordSignInSettings() {
        api().post("/api/profile/disable-password-sign-in", null).statusCode().shouldBe(401)
        api().post("/api/profile/enable-password-sign-in", null).statusCode().shouldBe(401)
    }

    @Test
    fun requiresTokenForCreatingSignInLink() {
        val response = api().post("/api/profile/sign-in-link", null)

        response.statusCode().shouldBe(401)
    }

    @Test
    fun createsAndConsumesOneTimeSignInLink() {
        saveUser("alice", "correct-password", UserType.USER)
        val token = api().login("alice", "correct-password")

        val createResponse = api().post("/api/profile/sign-in-link", token)

        createResponse.statusCode().shouldBe(200)
        val responseBody = objectMapper.readValue(createResponse.body(), Map::class.java)
        val link = responseBody["link"] as String
        link.shouldContain("/sign-in-link?token=")
        responseBody["expiresAt"].toString().shouldNotBeBlank()
        val signInLinkToken = link.substringAfter("token=")
        signInLinkToken.shouldNotBeBlank()

        val consumeResponse = api().postJson(
            "/api/create-auth-token-with-sign-in-link",
            """
                {"token":"$signInLinkToken"}
            """.trimIndent(),
            null,
        )

        consumeResponse.statusCode().shouldBe(200)
        val linkedToken = api().extractToken(consumeResponse.body())
        api().get("/api/profile", linkedToken).body().shouldEqualJson(
            """
                {
                  "username": "alice",
                  "type": "USER",
                  "passwordSignInDisabled": false,
                  "issueRefreshTokenOnPasskeyLogin": true
                }
            """.trimIndent(),
        )

        api().postJson(
            "/api/create-auth-token-with-sign-in-link",
            """
                {"token":"$signInLinkToken"}
            """.trimIndent(),
            null,
        ).statusCode().shouldBe(401)
    }

    @Test
    fun rejectsInvalidSignInLink() {
        val response = api().postJson(
            "/api/create-auth-token-with-sign-in-link",
            """
                {"token":"missing"}
            """.trimIndent(),
            null,
        )

        response.statusCode().shouldBe(401)
    }

    @Test
    fun rejectsBlankPasswordChangeValues() {
        saveUser("alice", "old-password", UserType.USER)
        val token = api().login("alice", "old-password")

        val response = api().patchJson(
            "/api/profile/password",
            """
                {"currentPassword":"old-password","newPassword":" "}
            """.trimIndent(),
            token,
        )

        response.statusCode().shouldBe(400)
        api().login("alice", "old-password").shouldNotBeBlank()
    }

    @Test
    fun rejectsPasswordChangeWithIncorrectCurrentPassword() {
        saveUser("alice", "old-password", UserType.USER)
        val token = api().login("alice", "old-password")

        val response = api().patchJson(
            "/api/profile/password",
            """
                {"currentPassword":"wrong-password","newPassword":"new-password"}
            """.trimIndent(),
            token,
        )

        response.statusCode().shouldBe(409)
        response.body().shouldEqualJson(
            """
                {
                  "code": "CURRENT_PASSWORD_INVALID"
                }
            """.trimIndent(),
        )
        api().login("alice", "old-password").shouldNotBeBlank()
    }

    @Test
    fun enforcesRoleChecks() {
        saveUser("alice", "user-password", UserType.USER)
        saveUser("admin", "admin-password", UserType.ADMIN)

        val userToken = api().login("alice", "user-password")
        val adminToken = api().login("admin", "admin-password")

        val trackingResponse = api().get("/api/tracking", userToken)
        trackingResponse.statusCode().shouldBe(200)
        trackingResponse.body().shouldEqualJson(
            """
                {
                  "name": "tracking"
                }
            """.trimIndent(),
        )

        api().get("/api/user-management", userToken).statusCode().shouldBe(403)
        val userManagementResponse = api().get("/api/user-management", adminToken)
        userManagementResponse.statusCode().shouldBe(200)
        userManagementResponse.body().shouldEqualJson(
            """
                {
                  "name": "user-management"
                }
            """.trimIndent(),
        )
    }

    @Test
    fun issuesTokenWithConfiguredExpiration() {
        saveUser("alice", "correct-password", UserType.USER)

        val token = api().login("alice", "correct-password")

        val payloadJson = String(Base64.getUrlDecoder().decode(token.split(".")[1]), StandardCharsets.UTF_8)
        val expiration = payloadJson.replace(Regex(".*\"exp\":([0-9]+).*"), "$1").toLong()
        expiration.shouldBe(testTimeProvider.now().plusSeconds(1800).epochSecond)
    }

    @Test
    fun issuesRememberMeCookieAndRefreshesAccessToken() {
        saveUser("alice", "correct-password", UserType.USER)

        val loginResponse = api().postJson(
            "/api/create-auth-token",
            """
                {"username":"alice","password":"correct-password","rememberMe":true,"rememberMeDevice":"Chrome on Linux"}
            """.trimIndent(),
            null,
        )

        loginResponse.statusCode().shouldBe(200)
        val rememberMeCookie = loginResponse.headers().allValues("Set-Cookie").single()
        rememberMeCookie.shouldContain("renalo.rememberMe=")
        rememberMeCookie.shouldContain("HTTPOnly")
        rememberMeCookie.shouldContain("SameSite=Lax")
        rememberMeCookie.shouldContain("Max-Age=2592000")
        val cookieValue = rememberMeCookie.substringAfter("renalo.rememberMe=").substringBefore(";")
        cookieValue.shouldNotBeBlank()
        cookieValue.split(".").size.shouldBe(1)

        val persistedToken = rememberMeTokenRepository.findAll().toList().single()
        persistedToken.userId.shouldBe(userRepository.findByUsername("alice")!!.id)
        persistedToken.tokenHash.shouldNotBe(cookieValue)
        persistedToken.device.shouldBe("Chrome on Linux")
        persistedToken.createdAt.shouldBe(testTimeProvider.now())
        persistedToken.lastUsedAt.shouldBe(testTimeProvider.now())

        val refreshResponse = api().postWithCookie("/api/refresh-access-token", rememberMeCookie.substringBefore(";"))

        refreshResponse.statusCode().shouldBe(200)
        refreshResponse.body().shouldNotBeBlank()
        val refreshedToken = api().extractToken(refreshResponse.body())
        val profileResponse = api().get("/api/profile", refreshedToken)
        profileResponse.statusCode().shouldBe(200)
        profileResponse.body().shouldEqualJson(
            """
                {
                  "username": "alice",
                  "type": "USER",
                  "passwordSignInDisabled": false,
                  "issueRefreshTokenOnPasskeyLogin": true
                }
            """.trimIndent(),
        )
        rememberMeTokenRepository.findAll().toList().single().lastUsedAt.shouldBe(testTimeProvider.now())
    }

    @Test
    fun refreshesAccessTokenWithBearerTokenWhenRememberMeCookieIsNotPresent() {
        saveUser("alice", "correct-password", UserType.USER)
        val token = api().login("alice", "correct-password")

        val refreshResponse = api().post("/api/refresh-access-token", token)

        refreshResponse.statusCode().shouldBe(200)
        val refreshedToken = api().extractToken(refreshResponse.body())
        val profileResponse = api().get("/api/profile", refreshedToken)
        profileResponse.statusCode().shouldBe(200)
        profileResponse.body().shouldEqualJson(
            """
                {
                  "username": "alice",
                  "type": "USER",
                  "passwordSignInDisabled": false,
                  "issueRefreshTokenOnPasskeyLogin": true
                }
            """.trimIndent(),
        )
    }

    @Test
    fun refreshesAccessTokenWithBearerTokenWhenRememberMeCookieIsInvalid() {
        saveUser("alice", "correct-password", UserType.USER)
        val token = api().login("alice", "correct-password")

        val refreshResponse = api().postWithCookie("/api/refresh-access-token", "renalo.rememberMe=stale", token)

        refreshResponse.statusCode().shouldBe(200)
        refreshResponse.headers().allValues("Set-Cookie").singleOrNull()
            .shouldNotBeNull()
            .shouldContain("Max-Age=0")
        val refreshedToken = api().extractToken(refreshResponse.body())
        api().get("/api/profile", refreshedToken).statusCode().shouldBe(200)
    }

    @Test
    fun rejectsBearerTokenRefreshForInactiveUser() {
        val user = saveUser("alice", "correct-password", UserType.USER)
        val token = api().login("alice", "correct-password")
        user.active = false
        userRepository.update(user)

        val refreshResponse = api().post("/api/refresh-access-token", token)

        refreshResponse.statusCode().shouldBe(200)
        refreshResponse.body().shouldEqualJson(
            """
                {
                  "token": null
                }
            """.trimIndent(),
        )
    }

    @Test
    fun doesNotIssueRememberMeCookieByDefaultAndReturnsNullWhenRefreshingWithoutValidCookie() {
        saveUser("alice", "correct-password", UserType.USER)

        val loginResponse = api().postJson(
            "/api/create-auth-token",
            """
                {"username":"alice","password":"correct-password"}
            """.trimIndent(),
            null,
        )

        loginResponse.statusCode().shouldBe(200)
        loginResponse.headers().allValues("Set-Cookie").shouldNotBeEmpty()
        loginResponse.headers().allValues("Set-Cookie").single().shouldContain("Max-Age=0")

        val missingCookieResponse = api().post("/api/refresh-access-token", null)
        missingCookieResponse.statusCode().shouldBe(200)
        missingCookieResponse.body().shouldEqualJson(
            """
                {
                  "token": null
                }
            """.trimIndent(),
        )

        val invalidCookieResponse = api().postWithCookie("/api/refresh-access-token", "renalo.rememberMe=not-a-jwt")
        invalidCookieResponse.statusCode().shouldBe(200)
        invalidCookieResponse.body().shouldEqualJson(
            """
                {
                  "token": null
                }
            """.trimIndent(),
        )
        invalidCookieResponse.headers().allValues("Set-Cookie").singleOrNull()
            .shouldNotBeNull()
            .shouldContain("Max-Age=0")
    }

    @Test
    fun rejectsRememberMeTokenForInactiveUser() {
        val user = saveUser("alice", "correct-password", UserType.USER)
        val loginResponse = api().postJson(
            "/api/create-auth-token",
            """
                {"username":"alice","password":"correct-password","rememberMe":true,"rememberMeDevice":"Chrome on Linux"}
            """.trimIndent(),
            null,
        )
        val rememberMeCookie = loginResponse.headers().allValues("Set-Cookie").single().substringBefore(";")
        user.active = false
        userRepository.update(user)

        val refreshResponse = api().postWithCookie("/api/refresh-access-token", rememberMeCookie)

        refreshResponse.statusCode().shouldBe(200)
        refreshResponse.body().shouldEqualJson(
            """
                {
                  "token": null
                }
            """.trimIndent(),
        )
        refreshResponse.headers().allValues("Set-Cookie").singleOrNull()
            .shouldNotBeNull()
            .shouldContain("Max-Age=0")
    }

    @Test
    fun requiresTokenForPasskeyProfileEndpoints() {
        api().get("/api/profile/passkeys", null).statusCode().shouldBe(401)
        api().postJson(
            "/api/profile/passkeys/registration-options",
            """
                {"device":"Chrome on Linux"}
            """.trimIndent(),
            null,
        ).statusCode().shouldBe(401)
        api().postJson(
            "/api/profile/passkeys",
            """
                {"requestId":"missing","credential":{}}
            """.trimIndent(),
            null,
        ).statusCode().shouldBe(401)
        api().delete("/api/profile/passkeys/1", null).statusCode().shouldBe(401)
    }

    @Test
    fun startsPasskeyRegistrationForCurrentUser() {
        val user = saveUser("alice", "correct-password", UserType.USER)
        val token = api().login("alice", "correct-password")

        val response = api().postJson(
            "/api/profile/passkeys/registration-options",
            """
                {"device":"Chrome on Linux"}
            """.trimIndent(),
            token,
        )

        response.statusCode().shouldBe(200)
        val responseBody = objectMapper.readValue(response.body(), Map::class.java)
        val requestId = responseBody["requestId"] as String
        requestId.shouldNotBeBlank()
        @Suppress("UNCHECKED_CAST")
        val publicKey = responseBody["publicKey"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val relyingParty = publicKey["rp"] as Map<String, Any?>
        relyingParty["name"].shouldBe("Renalo")
        relyingParty["id"].shouldBe("localhost")
        @Suppress("UNCHECKED_CAST")
        val passkeyUser = publicKey["user"] as Map<String, Any?>
        passkeyUser["name"].shouldBe("alice")
        publicKey["challenge"].shouldBe(requestId)
        @Suppress("UNCHECKED_CAST")
        val authenticatorSelection = publicKey["authenticatorSelection"] as Map<String, Any?>
        authenticatorSelection["residentKey"].shouldBe("required")

        val persistedChallenge = passkeyChallengeRepository.findByRequestId(requestId).shouldNotBeNull()
        persistedChallenge.userId.shouldBe(user.id)
        persistedChallenge.device.shouldBe("Chrome on Linux")
        persistedChallenge.requestJson.shouldNotBeBlank()
    }

    @Test
    fun rejectsInvalidPasskeyRegistrationFinishRequest() {
        saveUser("alice", "correct-password", UserType.USER)
        val token = api().login("alice", "correct-password")

        val response = api().postJson(
            "/api/profile/passkeys",
            """
                {"requestId":"missing","credential":{}}
            """.trimIndent(),
            token,
        )

        response.statusCode().shouldBe(400)
    }

    @Test
    fun startsAnonymousPasskeyAuthenticationAndRejectsUnknownChallenge() {
        val optionsResponse = api().post("/api/passkeys/authentication-options", null)

        optionsResponse.statusCode().shouldBe(200)
        val responseBody = objectMapper.readValue(optionsResponse.body(), Map::class.java)
        val requestId = responseBody["requestId"] as String
        requestId.shouldNotBeBlank()
        @Suppress("UNCHECKED_CAST")
        val publicKey = responseBody["publicKey"] as Map<String, Any?>
        publicKey["challenge"].shouldBe(requestId)

        val finishResponse = api().postJson(
            "/api/passkeys/create-auth-token",
            """
                {"requestId":"missing","credential":{}}
            """.trimIndent(),
            null,
        )
        finishResponse.statusCode().shouldBe(400)
    }

    private fun saveUser(username: String, password: String, type: UserType): User {
        return userRepository.save(User(username = username, passwordHash = passwordHasher.hash(password), type = type))
    }

    private fun savePasskey(user: User): PasskeyCredential {
        return passkeyCredentialRepository.save(
            PasskeyCredential(
                userId = user.id ?: throw IllegalStateException("Persisted user is missing id"),
                credentialId = "credential-${user.username}",
                userHandle = "user-handle-${user.username}",
                publicKeyCose = "public-key-${user.username}",
                signatureCount = 0,
                device = "Chrome on Linux",
                transports = "internal",
                backupEligible = false,
                backedUp = false,
                createdAt = testTimeProvider.now(),
            ),
        )
    }
}
