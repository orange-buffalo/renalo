package io.orangebuffalo.renalo.auth

import io.micronaut.context.annotation.Requires
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Removes remember-me tokens that are past their server-side expiration. Expired tokens are already
 * rejected on use; this only stops abandoned devices from leaving rows behind forever.
 */
@Singleton
@Requires(property = "renalo.auth.remember-me-cleanup-job.enabled", value = "true", defaultValue = "true")
open class ExpiredRememberMeTokenCleanupJob(
    private val rememberMeService: RememberMeService,
) {
    private val logger = LoggerFactory.getLogger(ExpiredRememberMeTokenCleanupJob::class.java)

    @Scheduled(fixedDelay = "1h")
    open fun deleteExpiredTokens() {
        try {
            val deletedTokens = rememberMeService.deleteExpiredTokens()
            if (deletedTokens > 0) {
                logger.info("Deleted {} expired remember-me tokens", deletedTokens)
            }
        } catch (ex: Exception) {
            logger.error("Failed to delete expired remember-me tokens", ex)
        }
    }
}
