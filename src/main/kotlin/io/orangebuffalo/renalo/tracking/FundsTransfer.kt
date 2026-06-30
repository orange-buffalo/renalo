package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import java.time.LocalDate

@MappedEntity("funds_transfers")
data class FundsTransfer(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val userId: Long,
    val sourceAccountId: Long,
    val targetAccountId: Long,
    val sourceAmountMinor: Long,
    val targetAmountMinor: Long,
    val date: LocalDate,
)
