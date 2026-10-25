package com.miniichat.data

import com.miniichat.api.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolsCardTest {
    private val known = setOf("list_files", "read_file")

    @Test
    fun parsesNamesAndMultiLineDescriptions() {
        val specs = ToolsCardFormat.parse(
            "## read_file\nname: open\ndescription: Read it.\n  More text.\n\n## list_files\nname: ls\ndescription: List.",
            known
        )
        assertEquals(listOf("open", "ls"), specs.map { it.name })
        assertEquals(listOf("read_file", "list_files"), specs.map { it.uid })
        assertEquals("Read it.\nMore text.", specs[0].description)
    }

    @Test
    fun rejectsUnknownToolsDuplicatesAndBadNames() {
        val bad = listOf(
            "## nope\nname: a\ndescription: b",
            "## read_file\nname: a\ndescription: b\n\n## read_file\nname: c\ndescription: d",
            "## read_file\nname: same\ndescription: b\n\n## list_files\nname: same\ndescription: d",
            "## read_file\nname: has space\ndescription: b",
            "## read_file\nname: a\n",
            ""
        )
        for (text in bad) {
            try {
                ToolsCardFormat.parse(text, known)
                fail("Expected an error for: $text")
            } catch (e: ToolsCardException) {
                assertNotNull(e.message)
            }
        }
    }
}

class MemoryDocTest {
    @Test
    fun theTemplateIsValid() {
        val doc = MemoryDoc.parse(MemoryDoc.template())
        assertEquals(MemoryDoc.HEADINGS, doc.sections.keys.toList())
    }

    @Test
    fun patchAddsAndRemovesLines() {
        val doc = MemoryDoc.parse(MemoryDoc.template())
        val added = doc.applyPatch("## Decisions\n+ Use Vietnamese (T7)\n## Goal\n+ Ship it")
        assertTrue(added.render().contains("Use Vietnamese (T7)"))
        val removed = added.applyPatch("## Decisions\n- Use Vietnamese (T7)")
        assertFalse(removed.render().contains("Use Vietnamese"))
        // The result is still a valid Memory file.
        MemoryDoc.parse(removed.render())
    }

    @Test
    fun aWrongPatchIsRejected() {
        val doc = MemoryDoc.parse(MemoryDoc.template())
        try {
            doc.applyPatch("## Decisions\n- a line that is not there")
            fail("Expected an error")
        } catch (e: MemoryFormatException) {
            assertTrue(e.message!!.contains("not found"))
        }
    }

    @Test
    fun unknownSectionsAreRejected() {
        try {
            MemoryDoc.parse("## Foo\nbar")
            fail("Expected an error")
        } catch (e: MemoryFormatException) {
            assertTrue(e.message!!.contains("Foo"))
        }
    }

    @Test
    fun theRestOfTheAnswerIsKeptWhenThePatchIsRemoved() {
        val split = MemoryPatch.split("Answer.\n[MEMORY_PATCH]\n## Goal\n+ x\n[/MEMORY_PATCH]")
        assertEquals("Answer.", split.visible)
        assertEquals("## Goal\n+ x", split.patch)
        assertEquals("Hi", MemoryPatch.hideForDisplay("Hi\n[MEMORY_PATCH]\n## Go"))
    }
}

class RequestCardsExtraTest {
    private val metadata = RequestCard(id = "m", role = CardRole.METADATA)
    private val history = RequestCard(id = "h", role = CardRole.HISTORY)
    private val lastTurn = RequestCard(id = "l", role = CardRole.LAST_TURN)

    private fun builder(file: String = MemoryDoc.template()) = RequestBuilder(
        readFile = { _, _ -> file },
        directoryTree = { "TREE" },
        renderVars = { it },
        mapMessage = { ChatMessage(it.role, it.content) }
    )

    private val chat = listOf(
        Message(id = "1", role = "user", content = "q1", turnNo = 1),
        Message(id = "2", role = "assistant", content = "a1"),
        Message(id = "3", role = "user", content = "q2", turnNo = 2)
    )

    @Test
    fun turnTagsGoOnUserMessagesOnly() = runBlocking {
        val layout = RequestLayout(listOf(metadata, history.copy(turnIdEnabled = true), lastTurn))
        assertEquals(listOf("[T1]\nq1", "a1", "[T2]\nq2"), builder().build(layout, chat).map { it.content })
        val plain = RequestLayout(listOf(metadata, history, lastTurn))
        assertEquals(listOf("q1", "a1", "q2"), builder().build(plain, chat).map { it.content })
    }

    @Test
    fun theTreeUsesItsOwnRoleName() = runBlocking {
        val layout = RequestLayout(
            listOf(metadata, history, RequestCard(id = "t", role = CardRole.DIRECTORY_TREE, roleLabel = "Directory tree"), lastTurn)
        )
        val roles = builder().build(layout, chat).map { it.role }
        assertTrue(roles.contains("Directory tree"))
    }

    @Test
    fun theMemoryFileIsSentWrappedAndCanBeAUserMessage() = runBlocking {
        val asSystem = RequestLayout(listOf(metadata, history, RequestCard(id = "x", role = CardRole.MEMORY, fileUri = "u"), lastTurn))
        val system = builder().build(asSystem, chat).first { it.content.startsWith("[MEMORY]") }
        assertEquals("system", system.role)
        val asUser = RequestLayout(
            listOf(metadata, history, RequestCard(id = "x", role = CardRole.MEMORY, fileUri = "u", roleLabel = "user"), lastTurn)
        )
        assertEquals("user", builder().build(asUser, chat).first { it.content.startsWith("[MEMORY]") }.role)
    }

    @Test
    fun aBrokenMemoryFileStopsTheRequest() = runBlocking {
        val layout = RequestLayout(listOf(metadata, history, RequestCard(id = "x", role = CardRole.MEMORY, fileUri = "u"), lastTurn))
        try {
            builder("## Foo\nbar").build(layout, chat)
            fail("Expected an error")
        } catch (e: RequestBuildException) {
            assertTrue(e.message!!.contains("Memory"))
        }
    }

    @Test
    fun theToolsCardIsNotAMessageAndOnlyOneIsAllowed() = runBlocking {
        val tools = RequestCard(id = "tc", role = CardRole.TOOLS, text = "x")
        val layout = RequestLayout(listOf(metadata, tools, history, lastTurn))
        assertEquals(3, builder().build(layout, chat).size)
        val two = RequestLayout(listOf(metadata, tools, tools.copy(id = "tc2"), history, lastTurn))
        assertNotNull(two.validationError())
    }
}
