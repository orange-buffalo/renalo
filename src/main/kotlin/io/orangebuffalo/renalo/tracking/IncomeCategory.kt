package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity

@MappedEntity("income_categories")
data class IncomeCategory(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val userId: Long,
    val name: String,
)
