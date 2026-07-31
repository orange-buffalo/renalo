package io.orangebuffalo.renalo.ai

import jakarta.inject.Singleton

@Singleton
class AiChatService {
    fun sendMessage(content: String): AiChatMessageResponse {
        return AiChatMessageResponse(
            content = """
                ## Spending snapshot

                You asked: **$content**

                Here is an example of how an AI-generated answer could present your results:

                | Category | Amount | Share |
                | --- | ---: | ---: |
                | Groceries | ${'$'}428.30 | 42% |
                | Transport | ${'$'}186.75 | 18% |
                | Dining out | ${'$'}142.10 | 14% |

                - **Groceries** were the largest expense category.
                - Dining out was lower than groceries by `${'$'}286.20`.
                - The remaining categories accounted for 26% of the sample total.

                > This is placeholder data from the Chat preview. It is not calculated from your Renalo records yet.
            """.trimIndent(),
            toolActivities = listOf(
                AiChatToolActivity(
                    label = "Reviewed expense totals",
                    status = AiChatToolActivityStatus.COMPLETED,
                ),
            ),
        )
    }
}
