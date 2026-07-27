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
}
