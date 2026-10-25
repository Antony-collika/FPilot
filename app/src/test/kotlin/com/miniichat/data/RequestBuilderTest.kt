package com.miniichat.data

import com.miniichat.api.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RequestBuilderTest {

    private fun builder(tree: String? = "TREE") = RequestBuilder(
        readFile = { uri, _ -> "file:$uri" },
        directoryTree = { tree },
        renderVars = { it.replace("{date}", "2026-01-01") },
        mapMessage = { ChatMessage(it.role, it.content) }
    )

    private fun layout(vararg cards: RequestCard) = RequestLayout(cards.toList())

    private val metadata = RequestCard(id = "m", role = CardRole.METADATA)
    private val history = RequestCard(id = "h", role = CardRole.HISTORY)
    private val lastTurn = RequestCard(id = "l", role = CardRole.LAST_TURN)

    private val conversation = listOf(
        Message(id = "1", role = "user", content = "first"),
        Message(id = "2", role = "assistant", content = "answer"),
        Message(id = "3", role = "user", content = "second"),
        Message(id = "4", role = "assistant", content = "")
    )

    @Test
    fun cardsAreSentInTheOrderOfTheList() = runBlocking {
        val cards = layout(
            metadata,
            RequestCard(id = "s", role = CardRole.SYSTEM, text = "Today is {date}"),
            history,
            RequestCard(id = "t", role = CardRole.DIRECTORY_TREE),
            lastTurn
        )
        val result = builder().build(cards, conversation)
        assertEquals(
            listOf("Today is 2026-01-01", "first", "answer", "TREE", "second"),
            result.map { it.content }
        )
        assertEquals(listOf("system", "user", "assistant", "system", "user"), result.map { it.role })
    }

    @Test
    fun skillsCardIsWrappedInItsTag() = runBlocking {
        val cards = layout(
            metadata,
            RequestCard(id = "k", role = CardRole.SYSTEM, text = "do things", wrapTag = "SKILLS"),
            history,
            lastTurn
        )
        val result = builder().build(cards, conversation)
        assertEquals("[SKILLS]\ndo things\n[/SKILLS]", result.first().content)
    }

    @Test
    fun switchedOffCardIsSkipped() = runBlocking {
        val cards = layout(
            metadata,
            RequestCard(id = "s", role = CardRole.SYSTEM, text = "hidden", enabled = false),
            history,
            lastTurn
        )
        val result = builder().build(cards, conversation)
        assertTrue(result.none { it.content == "hidden" })
    }

    @Test
    fun emptyCardStopsTheRequest() = runBlocking {
        val cards = layout(metadata, RequestCard(id = "s", role = CardRole.SYSTEM, text = "  "), history, lastTurn)
        try {
            builder().build(cards, conversation)
            fail("Expected an error for the empty card")
        } catch (e: RequestBuildException) {
            assertTrue(e.message!!.contains("empty"))
        }
    }

    @Test
    fun treeCardWithoutWorkspaceStopsTheRequest() = runBlocking {
        val cards = layout(metadata, history, RequestCard(id = "t", role = CardRole.DIRECTORY_TREE), lastTurn)
        try {
            builder(tree = null).build(cards, conversation)
            fail("Expected an error because there is no workspace")
        } catch (e: RequestBuildException) {
            assertTrue(e.message!!.contains("workspace"))
        }
    }

    @Test
    fun fileCardReadsTheFile() = runBlocking {
        val cards = layout(
            metadata,
            RequestCard(id = "s", role = CardRole.SYSTEM, source = "file", fileUri = "content://x"),
            history,
            lastTurn
        )
        assertEquals("file:content://x", builder().build(cards, conversation).first().content)
    }

    @Test
    fun finishedTurnsKeepTheirToolRounds() = runBlocking {
        val rounds = listOf(
            ToolRound(
                toolCalls = listOf(StoredToolCall("c1", "read_file", "{}")),
                results = listOf(StoredToolResult("c1", "read_file", "full text"))
            )
        )
        val messages = listOf(
            Message(id = "1", role = "user", content = "q1"),
            Message(id = "2", role = "assistant", content = "a1", toolRounds = rounds),
            Message(id = "3", role = "user", content = "q2")
        )
        val result = builder().build(layout(metadata, history, lastTurn), messages)
        assertEquals(listOf("user", "assistant", "tool", "assistant", "user"), result.map { it.role })
    }

    @Test
    fun historyMustStayAboveLastTurn() {
        val bad = layout(metadata, lastTurn, history)
        assertNotNull(bad.validationError())
        val good = layout(metadata, history, lastTurn)
        assertNull(good.validationError())
        assertNull(good.moved("l", -1))
        assertNull(good.moved("h", -1))
    }

    @Test
    fun layoutFromSettingsKeepsTheOldOrder() {
        val settings = AppSettings(
            systemPrompt = "sys",
            workspaceTreeUri = "content://tree",
            directoryTreeEnabled = true,
            directoryTreeDepth = 3,
            skillsEnabled = true,
            skillsText = "skills"
        )
        val roles = layoutFromSettings(settings).cards.map { it.role }
        assertEquals(
            listOf(
                CardRole.METADATA, CardRole.SYSTEM, CardRole.DIRECTORY_TREE,
                CardRole.SYSTEM, CardRole.HISTORY, CardRole.LAST_TURN
            ),
            roles
        )
        assertNull(layoutFromSettings(settings).validationError())
    }
}
