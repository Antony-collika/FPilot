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

    @Test
    fun sidecarFormatKeepsReadableMarkdownAndFullMetadataByMessageId() {
        val round = ToolRound(
            content = "tool round",
            toolCalls = listOf(StoredToolCall("call_1", "read_file", """{"path":"a.md"}""")),
            results = listOf(StoredToolResult("call_1", "read_file", "result")),
            workspaceUri = "workspace://tree"
        )
        val source = HistoryDocument(
            sessionId = "a3f9c1d2",
            title = "Sidecar session",
            createdAt = 1_761_300_000_000L,
            nextTurn = 2,
            messages = listOf(
                Message("u1", "user", "hello", attachments = listOf(Attachment("file", "content://x", "text/plain", "x.txt")), createdAt = 11, turnNo = 1),
                Message("a1", "assistant", "answer", createdAt = 12, extraContent = buildJsonObject { put("trace", "yes") }, toolRounds = listOf(round))
            )
        )

        val markdown = HistoryMd.render(source, sidecarFormat = true)
        val sidecar = HistoryMeta.render(source)
        val parsed = HistoryMd.parse(markdown, source.sessionId, source.title, sidecar)

        assertTrue(markdown.contains("""{"id":"u1"}"""))
        assertTrue(markdown.contains("""{"id":"a1"}"""))
        assertFalse(markdown.contains("json-meta"))
        assertFalse(markdown.contains("json-round"))
        assertEquals(source.messages.map { it.id }, parsed.messages.map { it.id })
        assertEquals(source.messages[0].attachments, parsed.messages[0].attachments)
        assertEquals(source.messages[0].createdAt, parsed.messages[0].createdAt)
        assertEquals(source.messages[1].extraContent, parsed.messages[1].extraContent)
        assertEquals(round, parsed.messages[1].toolRounds.single())
        assertEquals(source.messages.map { it.content }, parsed.messages.map { it.content })
    }

    @Test
    fun sidecarFormatSupportsAnOrphanAssistantMessageAndRejectsMissingMetadata() {
        val source = HistoryDocument(
            sessionId = "a3f9c1d2",
            title = "Orphan",
            createdAt = 1_761_300_000_000L,
            nextTurn = 4,
            messages = listOf(Message("a1", "assistant", "answer", createdAt = 12))
        )
        val markdown = HistoryMd.render(source, sidecarFormat = true)
        val sidecar = HistoryMeta.render(source)
        assertEquals(listOf("a1"), HistoryMd.parse(markdown, source.sessionId, source.title, sidecar).messages.map { it.id })

        try {
            HistoryMd.parse(markdown, source.sessionId, source.title, HistoryMeta.empty(source.sessionId))
            throw AssertionError("Expected HistoryFormatException")
        } catch (error: HistoryFormatException) {
            assertTrue(error.message!!.contains("missing from history.meta.json"))
        }
    }

    @Test
    fun retainedSidecarKeepsDeletedMessageMetadataUntilMarkdownCommit() {
        val original = HistoryDocument(
            sessionId = "a3f9c1d2",
            title = "Before",
            createdAt = 100,
            nextTurn = 2,
            messages = listOf(
                Message("u1", "user", "question", createdAt = 1, turnNo = 1),
                Message("a1", "assistant", "answer", createdAt = 2)
            )
        )
        val edited = original.copy(messages = listOf(original.messages.last()))

        val retained = HistoryMeta.parse(
            HistoryMeta.renderRetainingPrevious(HistoryMeta.render(original), edited),
            original.sessionId
        )
        val committed = HistoryMeta.parse(HistoryMeta.render(edited), original.sessionId)

        assertTrue(retained.messages.containsKey("u1"))
        assertTrue(retained.messages.containsKey("a1"))
        assertFalse(committed.messages.containsKey("u1"))
    }

    @Test
    fun sidecarRepairRetainsValidBlocksWhenAnotherHeadingIsMalformed() {
        val source = HistoryDocument(
            sessionId = "a3f9c1d2",
            title = "Repair sidecar",
            createdAt = 1_761_300_000_000L,
            nextTurn = 2,
            messages = listOf(
                Message("u1", "user", "question", createdAt = 1, turnNo = 1),
                Message("a1", "assistant", "answer", createdAt = 2)
            )
        )
        val markdown = HistoryMd.render(source, sidecarFormat = true)
            .replace("## T1 assistant", "## T1 asistant")
        val repaired = HistoryMd.repair(markdown, source.sessionId, source.title, HistoryMeta.render(source))

        assertEquals(listOf("u1"), repaired.messages.map { it.id })
        assertTrue(repaired.repairNotes.isNotEmpty())
    }
    @Test
    fun branchSidecarUsesNewSessionIdAndPreservesSelectedMessageMetadata() {
        val round = ToolRound(
            content = "tool output",
            toolCalls = listOf(StoredToolCall("call_1", "read_file", """{"path":"notes.md"}""")),
            results = listOf(StoredToolResult("call_1", "read_file", "result"))
        )
        val original = HistoryDocument(
            sessionId = "a3f9c1d2", title = "Original", createdAt = 100, nextTurn = 3,
            messages = listOf(
                Message("u1", "user", "question", attachments = listOf(Attachment("file", "content://x", "text/plain", "x.txt")), createdAt = 11, turnNo = 1),
                Message("a1", "assistant", "answer", createdAt = 12, extraContent = buildJsonObject { put("trace", "yes") }, toolRounds = listOf(round))
            )
        )
        val branched = original.copy(sessionId = "b4e2d8a1")
        val markdown = HistoryMd.render(branched, sidecarFormat = true)
        val metadata = HistoryMeta.render(branched)
        val parsed = HistoryMd.parse(markdown, branched.sessionId, branched.title, metadata)
        val sidecar = HistoryMeta.parse(metadata, branched.sessionId)

        assertEquals(original.messages, parsed.messages)
        assertEquals(original.messages.map { it.id }.toSet(), sidecar.messages.keys)
        assertEquals(original.messages[0].attachments, sidecar.messages["u1"]?.attachments)
        assertEquals(round, sidecar.messages["a1"]?.toolRounds?.single())
        assertEquals("b4e2d8a1", sidecar.session)
        assertFalse(metadata.contains(original.sessionId))
    }
}
