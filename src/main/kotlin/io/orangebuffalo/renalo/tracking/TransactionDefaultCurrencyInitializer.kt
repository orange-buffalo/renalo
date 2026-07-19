package io.orangebuffalo.renalo.tracking

import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.event.ApplicationStartupEvent
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Singleton

@Singleton
class TransactionDefaultCurrencyInitializer(
    private val userRepository: UserRepository,
    private val transactionDefaultCurrencyService: TransactionDefaultCurrencyService,
) : ApplicationEventListener<ApplicationStartupEvent> {
    override fun onApplicationEvent(event: ApplicationStartupEvent) {
        userRepository.findAll()
            .filter { it.type == UserType.USER }
            .forEach { transactionDefaultCurrencyService.recalculateForUser(it.id!!) }
    }
}
