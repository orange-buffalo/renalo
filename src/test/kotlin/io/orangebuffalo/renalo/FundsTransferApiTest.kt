package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.FundsTransfer
import io.orangebuffalo.renalo.tracking.FundsTransferRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.LocalDate

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class FundsTransferApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var fundsTransferRepository: FundsTransferRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun requiresRegularUserForFundsTransfers() {
        val alice = saveUser("alice", UserType.USER)
        saveAccount(alice, "Main", "AUD", isDefault = true)
        saveUser("admin", UserType.ADMIN)
        val userToken = api().login("alice", "password")
        val adminToken = api().login("admin", "password")

        api().get("/api/tracking/funds-transfers", null).statusCode().shouldBe(401)
        api().get("/api/tracking/funds-transfers", adminToken).statusCode().shouldBe(403)
        api().postJson("/api/tracking/funds-transfers", "{}", null).statusCode().shouldBe(401)
        api().postJson("/api/tracking/funds-transfers", "{}", adminToken).statusCode().shouldBe(403)
        api().get("/api/tracking/funds-transfers", userToken).statusCode().shouldBe(200)
    }

    @Test
    fun createsUpdatesListsAndDeletesFundsTransfers() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val savings = saveAccount(alice, "Savings", "AUD")
        val travel = saveAccount(alice, "Travel", "EUR")
        val token = api().login("alice", "password")

        val createResponse = api().postJson(
            "/api/tracking/funds-transfers",
            """
                {"sourceAccountId":${main.id},"targetAccountId":${savings.id},"sourceAmountMinor":12345,"date":"2026-06-10"}
            """.trimIndent(),
            token,
        )

        createResponse.statusCode().shouldBe(201)
        val createdTransfer = fundsTransferRepository.findByUserIdOrderByDateDesc(alice.id!!).single()
        createdTransfer.targetAmountMinor.shouldBe(12345)
        createResponse.body().shouldEqualJson(
            """
                {
                  "id": ${createdTransfer.id},
                  "sourceAccount": {"id": ${main.id}, "name": "Main", "currency": "AUD"},
                  "targetAccount": {"id": ${savings.id}, "name": "Savings", "currency": "AUD"},
                  "sourceAmountMinor": 12345,
                  "targetAmountMinor": 12345,
                  "date": "2026-06-10"
                }
            """.trimIndent(),
        )

        val updateResponse = api().patchJson(
            "/api/tracking/funds-transfers/${createdTransfer.id}",
            """
                {"sourceAccountId":${savings.id},"targetAccountId":${travel.id},"sourceAmountMinor":9900,"targetAmountMinor":6000,"date":"2026-06-12"}
            """.trimIndent(),
            token,
        )

        updateResponse.statusCode().shouldBe(200)
        updateResponse.body().shouldEqualJson(
            """
                {
                  "id": ${createdTransfer.id},
                  "sourceAccount": {"id": ${savings.id}, "name": "Savings", "currency": "AUD"},
                  "targetAccount": {"id": ${travel.id}, "name": "Travel", "currency": "EUR"},
                  "sourceAmountMinor": 9900,
                  "targetAmountMinor": 6000,
                  "date": "2026-06-12"
                }
            """.trimIndent(),
        )

        val listResponse = api().get("/api/tracking/funds-transfers", token)
        listResponse.statusCode().shouldBe(200)
        listResponse.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${createdTransfer.id},
                    "sourceAccount": {"id": ${savings.id}, "name": "Savings", "currency": "AUD"},
                    "targetAccount": {"id": ${travel.id}, "name": "Travel", "currency": "EUR"},
                    "sourceAmountMinor": 9900,
                    "targetAmountMinor": 6000,
                    "date": "2026-06-12"
                  }
                ]
            """.trimIndent(),
        )

        api().delete("/api/tracking/funds-transfers/${createdTransfer.id}", token).statusCode().shouldBe(204)
        fundsTransferRepository.findById(createdTransfer.id!!).isPresent.shouldBe(false)
    }

    @Test
    fun rejectsInvalidFundsTransferReferencesAndAmounts() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val travel = saveAccount(alice, "Travel", "EUR")
        val bobAccount = saveAccount(bob, "Bob", "AUD", isDefault = true)
        val token = api().login("alice", "password")

        api().postJson(
            "/api/tracking/funds-transfers",
            """
                {"sourceAccountId":${main.id},"targetAccountId":${main.id},"sourceAmountMinor":100,"date":"2026-06-10"}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/funds-transfers",
            """
                {"sourceAccountId":${main.id},"targetAccountId":${bobAccount.id},"sourceAmountMinor":100,"date":"2026-06-10"}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/funds-transfers",
            """
                {"sourceAccountId":${main.id},"targetAccountId":${travel.id},"sourceAmountMinor":100,"date":"2026-06-10"}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/funds-transfers",
            """
                {"sourceAccountId":${main.id},"targetAccountId":${travel.id},"sourceAmountMinor":100,"targetAmountMinor":0,"date":"2026-06-10"}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(400)
    }

    @Test
    fun onlyAllowsCurrentUsersFundsTransfers() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val aliceMain = saveAccount(alice, "Main", "AUD", isDefault = true)
        val aliceSavings = saveAccount(alice, "Savings", "AUD")
        val bobMain = saveAccount(bob, "Bob Main", "AUD", isDefault = true)
        val bobSavings = saveAccount(bob, "Bob Savings", "AUD")
        val bobTransfer = saveTransfer(bob, bobMain, bobSavings, 1000, 1000)
        val token = api().login("alice", "password")

        api().get("/api/tracking/funds-transfers/${bobTransfer.id}", token).statusCode().shouldBe(404)
        api().patchJson(
            "/api/tracking/funds-transfers/${bobTransfer.id}",
            """
                {"sourceAccountId":${aliceMain.id},"targetAccountId":${aliceSavings.id},"sourceAmountMinor":100,"date":"2026-06-10"}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(404)
        api().delete("/api/tracking/funds-transfers/${bobTransfer.id}", token).statusCode().shouldBe(404)
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
        isDefault: Boolean = false,
    ): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = currency,
            initialBalanceMinor = 0,
            isDefault = isDefault,
        ),
    )

    private fun saveTransfer(
        user: User,
        sourceAccount: TrackingAccount,
        targetAccount: TrackingAccount,
        sourceAmountMinor: Long,
        targetAmountMinor: Long,
    ): FundsTransfer = fundsTransferRepository.save(
        FundsTransfer(
            userId = user.id!!,
            sourceAccountId = sourceAccount.id!!,
            targetAccountId = targetAccount.id!!,
            sourceAmountMinor = sourceAmountMinor,
            targetAmountMinor = targetAmountMinor,
            date = LocalDate.parse("2026-06-10"),
        ),
    )
}
