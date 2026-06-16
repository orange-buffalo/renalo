package io.orangebuffalo.renalo.test

import com.microsoft.playwright.Page

const val PLAYWRIGHT_TIMEOUT_MILLIS = 30_000L

fun Page.shouldEventually(assertion: Page.() -> Unit) {
    val deadline = System.nanoTime() + PLAYWRIGHT_TIMEOUT_MILLIS * 1_000_000L

    while (System.nanoTime() < deadline) {
        try {
            assertion()
            return
        } catch (error: AssertionError) {
            Thread.sleep(100)
        }
    }

    assertion()
}
