package dev.qelg.harnessandroid

import dev.qelg.harnessandroid.data.ChatItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessHistoryTest {
    @Test
    fun structuredCodexAnswerRendersText() {
        val row =
            Json.parseToJsonElement(
                    """{"id":5,"role":"assistant","content":[{"type":"message","content":[{"type":"output_text","text":"Success"}]}]}"""
                )
                .jsonObject
        val message = messagesFromHistoryRow(row).single() as ChatItem.Message
        assertEquals("Success", message.text)
    }

    @Test
    fun codexReasoningSummaryIsKeptSeparateFromTheAnswer() {
        val row =
            Json.parseToJsonElement(
                    """{"id":5,"role":"assistant","content":[{"type":"reasoning","summary":[{"type":"summary_text","text":"Checked the implementation."}]},{"type":"message","content":[{"type":"output_text","text":"Success"}]}]}"""
                )
                .jsonObject

        val message = messagesFromHistoryRow(row).single() as ChatItem.Message

        assertEquals("Success", message.text)
        assertEquals("Checked the implementation.", message.reasoning)
        assertEquals(true, message.reasoningIsSummary)
    }

    @Test
    fun openRouterReasoningIsKeptSeparateFromTheAnswer() {
        val row =
            Json.parseToJsonElement(
                    """{"id":6,"role":"assistant","content":[{"role":"assistant","reasoning":"Inspect the code.","content":"Done"}]}"""
                )
                .jsonObject

        val message = messagesFromHistoryRow(row).single() as ChatItem.Message

        assertEquals("Done", message.text)
        assertEquals("Inspect the code.", message.reasoning)
        assertEquals(false, message.reasoningIsSummary)
    }

    @Test
    fun toolRequestAndResultKeepNameAndParameters() {
        val rows =
            listOf(
                Json.parseToJsonElement(
                        """{"id":3,"role":"tool_request","event_name":"tool.call.requested","tool":"podman-shell","run_id":"call_1","content":{"cmd":"pwd"}}"""
                    )
                    .jsonObject,
                Json.parseToJsonElement(
                        """{"id":4,"role":"tool","event_name":"chat.message.tool.created","tool":"podman-shell","run_id":"call_1","content":"/work"}"""
                    )
                    .jsonObject,
            )
        val tool = messagesFromHistoryRows(rows).single() as ChatItem.Tool
        assertEquals("podman-shell", tool.name)
        assertEquals("{\"cmd\":\"pwd\"}", tool.arguments)
        assertEquals("/work", tool.result)
    }

    @Test
    fun queuedCommandsAreNotRenderedAsDeliveredUserMessages() {
        val row =
            Json.parseToJsonElement(
                    """{"id":7,"role":"user","event_name":"queued.message","queue_mode":"after_response","content":"wait for this"}"""
                )
                .jsonObject

        assertEquals(emptyList<ChatItem>(), messagesFromHistoryRow(row))
    }
}
