package io.orangebuffalo.renalo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.orangebuffalo.renalo.tracking.FinancialMath
import org.junit.jupiter.api.Test

class FinancialMathTest {
    @Test
    fun addsAndSubtractsWithoutLosingBoundaryValues() {
        FinancialMath.add(Long.MAX_VALUE - 1, 1).shouldBe(Long.MAX_VALUE)
        FinancialMath.add(Long.MIN_VALUE + 1, -1).shouldBe(Long.MIN_VALUE)
        FinancialMath.subtract(Long.MIN_VALUE + 1, 1).shouldBe(Long.MIN_VALUE)
        FinancialMath.subtract(Long.MAX_VALUE - 1, -1).shouldBe(Long.MAX_VALUE)
    }

    @Test
    fun rejectsOverflowInsteadOfWrappingMoneyValues() {
        shouldThrow<ArithmeticException> { FinancialMath.add(Long.MAX_VALUE, 1) }
        shouldThrow<ArithmeticException> { FinancialMath.add(Long.MIN_VALUE, -1) }
        shouldThrow<ArithmeticException> { FinancialMath.subtract(Long.MIN_VALUE, 1) }
        shouldThrow<ArithmeticException> { FinancialMath.subtract(Long.MAX_VALUE, -1) }
    }
}
