package com.miniichat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class HistoryMetaDocument(
    val version: Int = 1,
    val session: String,
    val messages: Map<String, HistoryMessageMetadata> = emptyMap()
)

@Serializable
data class HistoryMessageMetadata(
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long,
    @SerialName("extra_content") val extraContent: JsonObject? = null,
    @SerialName("tool_rounds") val toolRounds: List<ToolRound> = emptyList()
)

/** Sidecar metadata for the ID-linked, human-readable history.md format. */
object HistoryMeta {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun empty(sessionId: String): String =
        json.encodeToString(HistoryMetaDocument(session = sessionId))

    fun parse(text: String, expectedSessionId: String): HistoryMetaDocument {
        val document = json.decodeFromString<HistoryMetaDocument>(text)
        require(document.version == 1) { "Unsupported metadata version ${document.version}." }
        require(document.session == expectedSessionId) { "Metadata belongs to session '${document.session}', not '$expectedSessionId'." }
        require(expectedSessionId.matches(Regex("[0-9a-f]{8}"))) { "Invalid session ID." }
        require(document.messages.keys.all { it.isNotBlank() }) { "Metadata contains a blank message ID." }
        return document
    }

    /** Keep deleted-message entries until the Markdown transcript has committed. */
    fun renderRetainingPrevious(previousText: String, document: HistoryDocument): String {
        val previous = parse(previousText, document.sessionId)
        val current = json.decodeFromString<HistoryMetaDocument>(render(document))
        val retained = linkedMapOf<String, HistoryMessageMetadata>()
        retained.putAll(previous.messages)
        retained.putAll(current.messages)
        return json.encodeToString(current.copy(messages = retained))
    }

    fun render(document: HistoryDocument): String {
        val messages = linkedMapOf<String, HistoryMessageMetadata>()
        document.messages.forEach { message ->
            require(message.id.isNotBlank()) { "Message IDs must not be blank." }
            require(!messages.containsKey(message.id)) { "Duplicate message ID '${message.id}'." }
            messages[message.id] = HistoryMessageMetadata(
                attachments = message.attachments,
                createdAt = message.createdAt,
                extraContent = message.extraContent,
                toolRounds = message.toolRounds
            )
        }
        return json.encodeToString(
            HistoryMetaDocument(
                version = 1,
                session = document.sessionId,
                messages = messages
            )
        )
    }
}
