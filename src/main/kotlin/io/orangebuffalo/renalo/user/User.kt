package io.orangebuffalo.renalo.user

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity

@MappedEntity("users")
data class User(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    var username: String,
    var passwordHash: String,
    var type: UserType,
)
