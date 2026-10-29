package com.miniichat.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMdTest {
    @Test
    fun roundTripsUserAssistantToolRoundsAndEscapedHeadings() {
        val round = ToolRound(
            content = "checking",
            toolCalls = listOf(StoredToolCall("call_1", "read_file", """{"path":"notes.md"}""")),
            results = listOf(StoredToolResult("call_1", "read_file", "short result"))
        )
        val source = HistoryDocument(
            sessionId = "a3f9c1d2",
            title = "A title: with punctuation",
            createdAt = 1_761_300_000_000L,
            nextTurn = 2,
            messages = listOf(
                Message("u1", "user", "hello\n## T9 assistant", turnNo = 1),
                Message("a1", "assistant", "answer", toolRounds = listOf(round))
            )
        )
        val text = HistoryMd.render(source)
        assertTrue(text.contains("### call call_1 read_file"))
        assertTrue(text.contains("### result call_1"))
        val parsed = HistoryMd.parse(text, "a3f9c1d2")
        assertEquals(source.title, parsed.title)
        assertEquals(2, parsed.nextTurn)
        assertEquals("hello\n## T9 assistant", parsed.messages[0].content)
        assertEquals("answer", parsed.messages[1].content)
        assertEquals(round.toolCalls, parsed.messages[1].toolRounds.single().toolCalls)
        assertEquals("short result", parsed.messages[1].toolRounds.single().results.single().content)
    }

    @Test
    fun rejectsMalformedHeadingsWithLineNumber() {
        val text = """---
session: a3f9c1d2
title: "Example"
created: 2026-10-28T00:00:00Z
next_turn: 2
---

## T1 user
hello

## T1 asistant
bad
"""
        try {
            HistoryMd.parse(text, "a3f9c1d2")
            throw AssertionError("Expected HistoryFormatException")
        } catch (error: HistoryFormatException) {
            assertTrue(error.lineNumber > 0)
            assertTrue(error.message!!.contains("Malformed turn heading"))
        }
    }

    @Test
    fun completedMessagesDoesNotSeedAnUnfinishedLastTurn() {
        val messages = listOf(
            Message("u1", "user", "finished", turnNo = 1),
            Message("a1", "assistant", "answer"),
            Message("u2", "user", "unfinished", turnNo = 2),
            Message("a2", "assistant", "")
        )
        assertEquals(listOf("finished", "answer"), HistoryMd.completedMessages(messages).map { it.content })
    }

    @Test
    fun keepsNextTurnHigherThanExistingTurnNumbers() {
        val text = """---
session: a3f9c1d2
title: "Example"
created: 2026-10-28T00:00:00Z
next_turn: 1
---

## T7 user
hello
```json-meta
{"id":"u7","attachments":[],"createdAt":1}
```

## T7 assistant
world
```json-meta
{"id":"a7","attachments":[],"createdAt":2}
```
"""
        assertEquals(8, HistoryMd.parse(text, "a3f9c1d2").nextTurn)
    }

    @Test
    fun repairRetainsCompleteTurnsAndRenderProducesValidFrontMatter() {
        val text = """---
session: a3f9c1d2
title: "Example"
created: 2026-10-28T00:00:00Z
next_turn: 2
---

## T1 user
hello

## T1 assistant
world

## T2 asistant
broken
"""
        val repaired = HistoryMd.repair(text, "a3f9c1d2", "Example")
        assertEquals(listOf("user", "assistant"), repaired.messages.map { it.role })
        assertEquals(2, repaired.nextTurn)
        assertTrue(repaired.repairNotes.isNotEmpty())
        val rendered = HistoryMd.render(repaired)
        assertTrue(rendered.startsWith("---\nsession: a3f9c1d2"))
    }
}
