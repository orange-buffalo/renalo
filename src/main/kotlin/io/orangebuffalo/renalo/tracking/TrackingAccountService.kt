package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.util.Currency

@Singleton
open class TrackingAccountService(
    private val trackingAccountRepository: TrackingAccountRepository,
) {
    @Transactional
    open fun createDefaultAccountForUser(userId: Long): TrackingAccount {
        if (trackingAccountRepository.countByUserId(userId) > 0) {
            return trackingAccountRepository.findByUserIdOrderByName(userId).first()
        }

        return trackingAccountRepository.save(
            TrackingAccount(
                userId = userId,
                name = "Main",
                currency = "AUD",
                initialBalanceMinor = 0,
                isDefault = true,
            ),
        )
    }

    fun listAccounts(userId: Long): List<TrackingAccount> =
        trackingAccountRepository.findByUserIdOrderByName(userId)

    fun findAccount(userId: Long, accountId: Long): TrackingAccount? =
        trackingAccountRepository.findByIdAndUserId(accountId, userId)

    @Transactional
    open fun createAccount(userId: Long, request: SaveTrackingAccountRequest): TrackingAccount? {
        val name = request.name.trim()
        val currency = request.currency.trim().uppercase()
        if (name.isBlank() || !isValidCurrency(currency)) {
            return null
        }

        val shouldBeDefault = request.isDefault || trackingAccountRepository.countByUserId(userId) == 0L
        if (shouldBeDefault) {
            trackingAccountRepository.clearDefaultForUser(userId)
        }

        return trackingAccountRepository.save(
            TrackingAccount(
                userId = userId,
                name = name,
                currency = currency,
                initialBalanceMinor = request.initialBalanceMinor,
                isDefault = shouldBeDefault,
            ),
        )
    }

    @Transactional
    open fun updateAccount(userId: Long, accountId: Long, request: SaveTrackingAccountRequest): TrackingAccount? {
        val account = trackingAccountRepository.findByIdAndUserId(accountId, userId)
            ?: return null
        val name = request.name.trim()
        val currency = request.currency.trim().uppercase()
        if (name.isBlank() || !isValidCurrency(currency)) {
            return null
        }

        val shouldBeDefault = account.isDefault || request.isDefault
        if (request.isDefault && !account.isDefault) {
            trackingAccountRepository.clearDefaultForUser(userId)
        }

        return trackingAccountRepository.update(
            account.copy(
                name = name,
                currency = currency,
                initialBalanceMinor = request.initialBalanceMinor,
                isDefault = shouldBeDefault,
            ),
        )
    }

    private fun isValidCurrency(currency: String): Boolean = try {
        Currency.getInstance(currency)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

data class SaveTrackingAccountRequest(
    val name: String,
    val currency: String,
    val initialBalanceMinor: Long = 0,
    val isDefault: Boolean = false,
)
