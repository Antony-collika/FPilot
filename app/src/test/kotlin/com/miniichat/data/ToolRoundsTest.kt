package com.miniichat.data

import com.miniichat.api.ChatMessage
import com.miniichat.api.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRoundsTest {

    private fun generatedTurn(resultText: String): List<ChatMessage> = listOf(
        ChatMessage(
            role = "assistant",
            content = "",
            toolCalls = listOf(ToolCall("call-1", "read_file", """{"path":"docs/spec.md"}"""))
        ),
        ChatMessage(role = "tool", content = resultText, toolCallId = "call-1", name = "read_file")
    )

    @Test
    fun longResultGetsAFixedCompactNote() {
        val rounds = buildToolRounds(generatedTurn("x".repeat(1200)), turnNo = 7)
        assertEquals(1, rounds.size)
        val result = rounds[0].results.single()
        assertEquals(1200, result.content.length)
        assertEquals(
            "[Kết quả đã rút gọn: read_file \"docs/spec.md\", 1200 ký tự, turn T7. Gọi lại tool nếu cần nội dung.]",
            result.compact
        )
    }

    @Test
    fun shortResultIsKeptAsItIs() {
        val rounds = buildToolRounds(generatedTurn("Created the file."), turnNo = 3)
        assertNull(rounds[0].results.single().compact)
    }

    @Test
    fun compactModeSendsTheNoteAndFullModeSendsTheText() {
        val rounds = buildToolRounds(generatedTurn("y".repeat(800)), turnNo = 2)

        val compact = toolRoundsToChatMessages(rounds, compact = true)
        assertEquals(listOf("assistant", "tool"), compact.map { it.role })
        assertTrue(compact[1].content.startsWith("[Kết quả đã rút gọn"))
        assertEquals("call-1", compact[1].toolCallId)
        assertEquals("read_file", compact[1].name)

        val full = toolRoundsToChatMessages(rounds, compact = false)
        assertEquals("y".repeat(800), full[1].content)
    }

    @Test
    fun everyCallAlwaysHasOneResult() {
        val generated = listOf(
            ChatMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(
                    ToolCall("a", "list_files", "{}"),
                    ToolCall("b", "read_file", """{"path":"x.md"}""")
                )
            ),
            ChatMessage(role = "tool", content = "one", toolCallId = "a", name = "list_files")
        )
        val round = buildToolRounds(generated, turnNo = 1).single()
        assertEquals(listOf("a", "b"), round.results.map { it.toolCallId })
    }

    @Test
    fun oldChatsGetTurnNumbersAndTheCounterNeverGoesDown() {
        val conversation = Conversation(
            id = "c",
            title = "t",
            messages = listOf(
                Message(id = "1", role = "user", content = "a"),
                Message(id = "2", role = "assistant", content = "b"),
                Message(id = "3", role = "user", content = "c"),
                Message(id = "4", role = "assistant", content = "d")
            )
        )
        val numbered = conversation.withTurnNumbers()
        assertEquals(listOf(1, 2), numbered.messages.filter { it.role == "user" }.map { it.turnNo })
        assertEquals(3, numbered.nextTurnNo)

        // Delete turn 1: the counter must stay at 3, so numbers are never reused.
        val afterDelete = numbered.copy(messages = numbered.messages.drop(2)).withTurnNumbers()
        assertEquals(3, afterDelete.nextTurnNo)
    }
}
