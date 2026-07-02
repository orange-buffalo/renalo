package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import io.micronaut.data.annotation.TypeDef
import io.micronaut.data.model.DataType
import java.time.LocalDate

@MappedEntity("transactions")
data class Transaction(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val userId: Long,
    val type: TransactionType,
    val trackingAccountId: Long,
    val categoryId: Long,
    val date: LocalDate,
    val amountMinor: Long,
    val notes: String? = null,
    @field:TypeDef(type = DataType.JSON)
    val metadata: Map<String, String>? = null,
    val recurringRuleId: Long? = null,
    val recurringInstanceDate: LocalDate? = null,
    val recurringLocked: Boolean = false,
)

enum class TransactionType {
    INCOME,
    EXPENSE,
}
