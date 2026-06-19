package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class TrackingAccountApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun requiresRegularUserForTrackingAccounts() {
        val alice = saveUser("alice", UserType.USER)
        val admin = saveUser("admin", UserType.ADMIN)
        saveAccount(alice, "Main", "AUD", 0, isDefault = true)

        val userToken = api().login("alice", "password")
        val adminToken = api().login("admin", "password")
        val body = """
            {"name":"Savings","currency":"EUR","initialBalanceMinor":1000,"isDefault":false}
        """.trimIndent()

        api().get("/api/tracking/accounts", null).statusCode().shouldBe(401)
        api().get("/api/tracking/accounts", adminToken).statusCode().shouldBe(403)
        api().postJson("/api/tracking/accounts", body, null).statusCode().shouldBe(401)
        api().postJson("/api/tracking/accounts", body, adminToken).statusCode().shouldBe(403)
        api().get("/api/tracking/accounts", userToken).statusCode().shouldBe(200)
    }

    @Test
    fun listsOnlyCurrentUsersAccounts() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val aliceMain = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        val aliceSavings = saveAccount(alice, "Savings", "EUR", 12345, isDefault = false)
        saveAccount(bob, "Bob account", "USD", 999, isDefault = true)

        val response = api().get("/api/tracking/accounts", api().login("alice", "password"))

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${aliceMain.id},
                    "name": "Main",
                    "currency": "AUD",
                    "initialBalanceMinor": 0,
                    "isDefault": true
                  },
                  {
                    "id": ${aliceSavings.id},
                    "name": "Savings",
                    "currency": "EUR",
                    "initialBalanceMinor": 12345,
                    "isDefault": false
                  }
                ]
            """.trimIndent(),
        )
    }

    @Test
    fun createsAccountForCurrentUser() {
        val alice = saveUser("alice", UserType.USER)
        saveAccount(alice, "Main", "AUD", 0, isDefault = true)

        val response = api().postJson(
            "/api/tracking/accounts",
            """
                {"name":" Savings ","currency":"eur","initialBalanceMinor":12345,"isDefault":false}
            """.trimIndent(),
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(201)
        val savings = trackingAccountRepository.findByUserIdOrderByName(alice.id!!).first { it.name == "Savings" }
        response.body().shouldEqualJson(
            """
                {
                  "id": ${savings.id},
                  "name": "Savings",
                  "currency": "EUR",
                  "initialBalanceMinor": 12345,
                  "isDefault": false
                }
            """.trimIndent(),
        )
    }

    @Test
    fun updatesAccountAndNominatesNewDefault() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        val savings = saveAccount(alice, "Savings", "EUR", 12345, isDefault = false)

        val response = api().patchJson(
            "/api/tracking/accounts/${savings.id}",
            """
                {"name":"Everyday","currency":"USD","initialBalanceMinor":550,"isDefault":true}
            """.trimIndent(),
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "id": ${savings.id},
                  "name": "Everyday",
                  "currency": "USD",
                  "initialBalanceMinor": 550,
                  "isDefault": true
                }
            """.trimIndent(),
        )
        trackingAccountRepository.findById(main.id!!).get().isDefault.shouldBe(false)
        trackingAccountRepository.findById(savings.id!!).get().isDefault.shouldBe(true)
    }

    @Test
    fun doesNotUncheckCurrentDefaultAccount() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)

        val response = api().patchJson(
            "/api/tracking/accounts/${main.id}",
            """
                {"name":"Main","currency":"AUD","initialBalanceMinor":0,"isDefault":false}
            """.trimIndent(),
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "id": ${main.id},
                  "name": "Main",
                  "currency": "AUD",
                  "initialBalanceMinor": 0,
                  "isDefault": true
                }
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsInvalidAccountsAndOtherUsersAccounts() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val bobAccount = saveAccount(bob, "Bob account", "USD", 0, isDefault = true)
        val token = api().login("alice", "password")

        api().postJson(
            "/api/tracking/accounts",
            """
                {"name":"","currency":"EUR","initialBalanceMinor":0,"isDefault":false}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/accounts",
            """
                {"name":"Savings","currency":"NOPE","initialBalanceMinor":0,"isDefault":false}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(400)
        api().get("/api/tracking/accounts/${bobAccount.id}", token).statusCode().shouldBe(404)
        api().patchJson(
            "/api/tracking/accounts/${bobAccount.id}",
            """
                {"name":"Hacked","currency":"EUR","initialBalanceMinor":0,"isDefault":true}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(404)
    }

    @Test
    fun createsDefaultAccountForNewRegularUser() {
        saveUser("admin", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        val response = api().postJson(
            "/api/users",
            """
                {"username":"alice","type":"USER"}
            """.trimIndent(),
            adminToken,
        )

        response.statusCode().shouldBe(201)
        val alice = userRepository.findByUsername("alice")!!
        val accounts = trackingAccountRepository.findByUserIdOrderByName(alice.id!!)
        accounts.size.shouldBe(1)
        accounts.single().name.shouldBe("Main")
        accounts.single().currency.shouldBe("AUD")
        accounts.single().initialBalanceMinor.shouldBe(0)
        accounts.single().isDefault.shouldBe(true)
    }

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
        ),
    )

    private fun saveAccount(
        user: User,
        name: String,
        currency: String,
        initialBalanceMinor: Long,
        isDefault: Boolean,
    ): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = currency,
            initialBalanceMinor = initialBalanceMinor,
            isDefault = isDefault,
        ),
    )
}
