package io.orangebuffalo.renalo.test

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

object KotestAssertions {
    @JvmStatic
    fun shouldBe(actual: Any?, expected: Any?) {
        actual shouldBe expected
    }

    @JvmStatic
    fun shouldBeNull(actual: Any?) {
        actual shouldBe null
    }

    @JvmStatic
    fun shouldBeTrue(actual: Boolean) {
        actual shouldBe true
    }

    @JvmStatic
    fun shouldNotBeBlank(actual: String) {
        actual.shouldNotBeBlank()
    }
}
