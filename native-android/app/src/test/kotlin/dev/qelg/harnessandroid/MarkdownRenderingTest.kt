package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderingTest {
    @Test
    fun finalAssistantMessageUsesMarkdownRenderer() {
        assertTrue(shouldRenderMarkdown(ChatItem.Message("assistant", "**done**")))
    }

    @Test
    fun streamingAssistantMessageStaysPlainText() {
        assertFalse(
            shouldRenderMarkdown(
                ChatItem.Message("assistant", "**partial", pendingCanonical = true)
            )
        )
    }

    @Test
    fun userMessageStaysPlainText() {
        assertFalse(shouldRenderMarkdown(ChatItem.Message("user", "**literal**")))
    }

    @Test
    fun reasoningSummaryMarkdownMarkersBecomeBoldText() {
        assertEquals(
            listOf(
                ReasoningDisplayLine("Inspecting files", bold = true),
                ReasoningDisplayLine("Then run tests", bold = false),
            ),
            reasoningDisplayLines("**Inspecting files**\nThen run tests"),
        )
    }

    @Test
    fun hiddenReasoningOnlyMessagesDoNotRenderEmptyBubbles() {
        val message =
            ChatItem.Message(
                role = "assistant",
                text = "",
                reasoning = "Internal summary",
                reasoningIsSummary = true,
            )

        assertFalse(shouldDisplayMessage(message, showReasoning = false))
        assertTrue(shouldDisplayMessage(message, showReasoning = true))
    }
}
