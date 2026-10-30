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
    fun sidecarFormatRecoversMessageIdsWhenMetadataIsMissingEntries() {
        val source = HistoryDocument(
            sessionId = "a3f9c1d2",
            title = "Orphan",
            createdAt = 1_761_300_000_000L,
            nextTurn = 4,
            messages = listOf(Message("a1", "assistant", "answer", createdAt = 12))
        )
        val markdown = HistoryMd.render(source, sidecarFormat = true)
        val sidecar = HistoryMeta.render(source)
        assertEquals(
            listOf("a1"),
            HistoryMd.parse(markdown, source.sessionId, source.title, sidecar).messages.map { it.id }
        )

        // Missing JSON is recoverable from the ID and message body in the editable Markdown.
        val emptySidecar = HistoryMeta.empty(source.sessionId)
        val repaired = HistoryMd.parse(markdown, source.sessionId, source.title, emptySidecar)
        assertEquals(listOf("a1"), repaired.messages.map { it.id })
        assertEquals("answer", repaired.messages.single().content)
        assertEquals("a1", HistoryMd.parse(markdown, source.sessionId, source.title).messages.single().id)
    }

    @Test
    fun oneSidecarTracksBranchMembershipAndGlobalActiveBranch() {
        val source = HistoryDocument(
            sessionId = "a3f9c1d2",
            title = "Branched",
            createdAt = 1_761_300_000_000L,
            nextTurn = 2,
            branchId = "branch_02",
            messages = listOf(
                Message("u1", "user", "question", createdAt = 1, turnNo = 1, branchId = "branch_01"),
                Message("a2", "assistant", "alternate answer", createdAt = 2, branchId = "branch_02")
            )
        )
        val branches = listOf(
            HistoryBranchMetadata("branch_01", "history_branch_01.md", null, 1, listOf("u1")),
            HistoryBranchMetadata("branch_02", "history_branch_02.md", "branch_01", 2)
        )
        val sidecar = HistoryMeta.renderForBranch("", source, "branch_02", branches, 3)
        val parsed = HistoryMeta.parse(sidecar, source.sessionId)

        assertEquals(2, parsed.version)
        assertEquals("branch_02", parsed.activeBranchId)
        assertEquals(listOf("u1", "a2"), parsed.branches.first { it.id == "branch_02" }.messageIds)
        assertEquals("branch_01", parsed.messages["u1"]!!.branchId)
        assertEquals("branch_02", parsed.messages["a2"]!!.branchId)

        // Editing branch 01 from disk updates its inventory but must not switch active branch 02.
        val branchOne = source.copy(
            branchId = "branch_01",
            messages = listOf(source.messages.first(), Message("a1", "assistant", "original", createdAt = 3, branchId = "branch_01"))
        )
        val synced = HistoryMeta.syncBranchDocument(
            sidecar, source.sessionId, branchOne, "branch_01", parsed.branches, parsed.nextBranchNumber
        )
        val after = HistoryMeta.parse(synced, source.sessionId)
        assertEquals("branch_02", after.activeBranchId)
        assertEquals(listOf("u1", "a1"), after.branches.first { it.id == "branch_01" }.messageIds)
        assertEquals(listOf("u1", "a2"), after.branches.first { it.id == "branch_02" }.messageIds)

        // Scanning the active file afterwards must not restore stale branch-01 membership.
        val resynced = HistoryMeta.syncBranchDocument(
            synced, source.sessionId, source, "branch_02", after.branches, after.nextBranchNumber
        )
        val final = HistoryMeta.parse(resynced, source.sessionId)
        assertEquals("branch_02", final.activeBranchId)
        assertEquals(listOf("u1", "a1"), final.branches.first { it.id == "branch_01" }.messageIds)
        assertEquals(listOf("u1", "a2"), final.branches.first { it.id == "branch_02" }.messageIds)
    }

    @Test
    fun staleParentReferenceDoesNotInvalidateSurvivingBranchMetadata() {
        val document = HistoryDocument(
            sessionId = "a3f9c1d2",
            title = "Surviving branch",
            createdAt = 1_761_300_000_000L,
            nextTurn = 1,
            branchId = "branch_02",
            messages = emptyList()
        )
        val metadata = HistoryMeta.renderForBranch(
            previousText = "",
            document = document,
            activeBranchId = "branch_02",
            branches = listOf(
                HistoryBranchMetadata(
                    id = "branch_02",
                    file = "history_branch_02.md",
                    parentBranchId = "branch_01",
                    createdAt = 2
                )
            ),
            nextBranchNumber = 3
        )

        val parsed = HistoryMeta.parse(metadata, document.sessionId)
        assertEquals("branch_02", parsed.activeBranchId)
        assertNull(parsed.branches.single().parentBranchId)
    }

    @Test
    fun metadataRepairKeepsBranchInventoryWhenMarkdownIsCorrupt() {
        val source = HistoryDocument(
            sessionId = "a3f9c1d2",
            title = "Repair inventory",
            createdAt = 1_761_300_000_000L,
            nextTurn = 2,
            branchId = "branch_02",
            messages = listOf(Message("u1", "user", "question", createdAt = 1, turnNo = 1, branchId = "branch_01"))
        )
        val branches = listOf(
            HistoryBranchMetadata("branch_01", "history_branch_01.md", null, 1, listOf("u1")),
            HistoryBranchMetadata("branch_02", "history_branch_02.md", "branch_01", 2, listOf("u1"))
        )
        val sidecar = HistoryMeta.renderForBranch("", source, "branch_02", branches, 3)
        val parsed = HistoryMeta.parse(sidecar, source.sessionId)
        val reconciled = HistoryMeta.reconcileBranches(
            sidecar, source.sessionId, "branch_02", parsed.branches, parsed.nextBranchNumber
        )
        val after = HistoryMeta.parse(reconciled, source.sessionId)
        assertEquals(listOf("u1"), after.branches.first { it.id == "branch_02" }.messageIds)
        assertEquals("branch_02", after.activeBranchId)
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
}
