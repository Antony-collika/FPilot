package com.miniichat.data

import com.miniichat.api.ChatMessage
import com.miniichat.api.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestSnapshotMarkdownTest {
    @Test
    fun rendersMessageProvenanceFingerprintAndToolSchema() {
        val message = ChatMessage(role = "system", content = "Workspace instructions")
        val part = RequestSnapshotPart(
            index = 0,
            cardId = "workspace-system",
            cardName = "Workspace instructions",
            sourceKind = "document",
            sourceLocation = "content://workspace/system.md",
            messageId = null,
            role = "system",
            contentPreview = message.content,
            contentSha256 = "a".repeat(64)
        )
        val snapshot = RequestSnapshot(
            messages = listOf(message),
            parts = listOf(part),
            fingerprint = "b".repeat(64)
        )
        val tool = ToolDefinition(
            name = "read_file",
            description = "Read a bounded Markdown file.",
            parameters = Json.parseToJsonElement(
                """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
            ).jsonObject
        )

        val markdown = RequestSnapshotMarkdown.render(snapshot, listOf(tool), createdAt = 0L)

        assertTrue(markdown.contains("fingerprint: " + "b".repeat(64)))
        assertTrue(markdown.contains("Workspace instructions"))
        assertTrue(markdown.contains("content://workspace/system.md"))
        assertTrue(markdown.contains("a".repeat(64)))
        assertTrue(markdown.contains("read_file"))
        assertTrue(markdown.contains("\"path\""))
    }

    @Test
    fun snapshotExportDoesNotCreateOrIncludeProviderCredentials() {
        val message = ChatMessage(role = "user", content = "Summarize the selected document.")
        val snapshot = RequestSnapshot(
            messages = listOf(message),
            parts = listOf(
                RequestSnapshotPart(
                    index = 0,
                    cardId = "history",
                    cardName = "History",
                    sourceKind = "conversation_message",
                    sourceLocation = "user",
                    messageId = "m1",
                    role = "user",
                    contentPreview = message.content,
                    contentSha256 = "c".repeat(64)
                )
            ),
            fingerprint = "d".repeat(64)
        )

        val markdown = RequestSnapshotMarkdown.render(snapshot, emptyList(), createdAt = 0L)

        assertTrue(markdown.contains("Summarize the selected document."))
        assertFalse(markdown.contains("Authorization: Bearer"))
        assertFalse(markdown.contains("api_key"))
        assertFalse(markdown.contains("provider_secret"))
    }
}
