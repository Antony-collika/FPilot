package com.miniichat.data

import com.miniichat.api.ChatMessage
import com.miniichat.api.ChatPart
import java.security.MessageDigest

/** Thrown when the cards cannot be turned into a request. The message is shown to the user. */
class RequestBuildException(message: String) : Exception(message)

/** A single exact message in the compiled request, with a readable source trail. */
data class RequestSnapshotPart(
    val index: Int,
    val cardId: String,
    val cardName: String,
    val sourceKind: String,
    val sourceLocation: String?,
    val messageId: String?,
    val role: String,
    /** Human-readable projection. Image bytes are not copied into the inspector text. */
    val contentPreview: String,
    /** SHA-256 of the exact message payload, including attachment URLs and tool-call arguments. */
    val contentSha256: String
)

/**
 * Immutable in-memory record of the messages actually built for one request.
 * It deliberately excludes provider credentials and HTTP headers.
 */
data class RequestSnapshot(
    val messages: List<ChatMessage>,
    val parts: List<RequestSnapshotPart>,
    val fingerprint: String
)

/**
 * Turns the card list plus the conversation into the list of messages sent to the model.
 *
 * The compiler is deterministic for a fixed layout, document content, variable context, and
 * conversation. Every emitted message receives source metadata and a digest. No provider secrets
 * are accepted by this class, so they cannot accidentally enter the snapshot.
 *
 * The Tools card is not a message; callers compile and inspect the effective tool definitions
 * separately.
 */
class RequestBuilder(
    private val readFile: suspend (uri: String, label: String) -> String,
    private val directoryTree: suspend (depth: Int) -> String?,
    private val renderVars: (String) -> String,
    private val mapMessage: (Message) -> ChatMessage
) {
    suspend fun build(layout: RequestLayout, messages: List<Message>): List<ChatMessage> =
        compile(layout, messages).messages

    suspend fun compile(layout: RequestLayout, messages: List<Message>): RequestSnapshot {
        layout.validationError()?.let { throw RequestBuildException(it) }

        val historyCard = layout.cards.first { it.role == CardRole.HISTORY }
        val usable = messages.filterNot { it.role == "assistant" && it.content.isEmpty() }
        // The last user message starts the running turn. Everything before it is History.
        val lastUserIndex = usable.indexOfLast { it.role == "user" }
        val historyMessages = if (lastUserIndex >= 0) usable.subList(0, lastUserIndex) else usable
        val lastTurnMessages = if (lastUserIndex >= 0) usable.subList(lastUserIndex, usable.size) else emptyList()

        val output = mutableListOf<ChatMessage>()
        val provenance = mutableListOf<RequestSnapshotPart>()

        fun append(
            card: RequestCard,
            sourceKind: String,
            sourceLocation: String?,
            messageId: String?,
            message: ChatMessage
        ) {
            val index = output.size
            output += message
            provenance += RequestSnapshotPart(
                index = index,
                cardId = card.id,
                cardName = card.displayName(),
                sourceKind = sourceKind,
                sourceLocation = sourceLocation,
                messageId = messageId,
                role = message.role,
                contentPreview = snapshotPreview(message),
                contentSha256 = sha256(exactPayload(message))
            )
        }

        for (card in layout.cards) {
            if (card.role == CardRole.METADATA || card.role == CardRole.TOOLS) continue
            if (!card.enabled && card.role !in CardRole.FIXED) continue
            when (card.role) {
                CardRole.SYSTEM -> {
                    val text = cardText(card)
                    append(
                        card,
                        if (card.source == "file") "document" else "inline_card",
                        if (card.source == "file") card.fileUri else null,
                        null,
                        ChatMessage("system", text)
                    )
                }
                CardRole.FREE -> {
                    val label = card.roleLabel.trim()
                    if (label.isEmpty()) {
                        throw RequestBuildException("The \"${card.displayName()}\" card has no role name.")
                    }
                    val text = cardText(card)
                    append(
                        card,
                        if (card.source == "file") "document" else "inline_card",
                        if (card.source == "file") card.fileUri else null,
                        null,
                        ChatMessage(label, text)
                    )
                }
                CardRole.DIRECTORY_TREE -> {
                    val tree = directoryTree(card.treeDepth.coerceIn(1, 8))
                        ?: throw RequestBuildException(
                            "The \"${card.displayName()}\" card needs a workspace folder. " +
                                "Choose one in Settings, or switch this card off."
                        )
                    append(
                        card,
                        "workspace_scan",
                        "selected workspace root",
                        null,
                        ChatMessage(card.roleLabel.trim().ifEmpty { DIRECTORY_TREE_ROLE }, tree)
                    )
                }
                CardRole.MEMORY -> {
                    val raw = readFile(card.fileUri, card.displayName())
                    try {
                        MemoryDoc.parse(raw)
                    } catch (e: MemoryFormatException) {
                        throw RequestBuildException("The Memory file has a problem: ${e.message}")
                    }
                    val role = if (card.roleLabel.trim().equals("system", ignoreCase = true)) "system" else "user"
                    append(
                        card,
                        "memory_document",
                        card.fileUri,
                        null,
                        ChatMessage(
                            role,
                            "[MEMORY: notes written by the assistant in earlier turns. Added by the app, not typed by the user.]\n" +
                                "${raw.trim()}\n[/MEMORY]"
                        )
                    )
                }
                CardRole.HISTORY -> {
                    appendExpanded(card, historyMessages, historyCard, ::append)
                }
                CardRole.LAST_TURN -> {
                    appendExpanded(card, lastTurnMessages, historyCard, ::append)
                }
                else -> throw RequestBuildException("Unknown card type: ${card.role}")
            }
        }

        val immutableMessages = output.toList()
        val fingerprint = sha256(provenance.joinToString("\n") { "${it.role}: ${it.contentSha256}" })
        return RequestSnapshot(immutableMessages, provenance.toList(), fingerprint)
    }

    private fun appendExpanded(
        sourceCard: RequestCard,
        messages: List<Message>,
        options: RequestCard,
        append: (RequestCard, String, String?, String?, ChatMessage) -> Unit
    ) {
        for (message in messages) {
            if (message.role == "assistant" && message.toolRounds.isNotEmpty()) {
                val toolMessages = toolRoundsToChatMessages(message.toolRounds, options.historyCompact)
                toolMessages.forEachIndexed { index, toolMessage ->
                    append(
                        sourceCard,
                        "tool_round",
                        "message ${message.id}, tool result ${index + 1}",
                        message.id,
                        toolMessage
                    )
                }
            }
            val mapped = mapMessage(message)
            val withTag = if (options.turnIdEnabled) withTurnTag(message, mapped) else mapped
            append(
                sourceCard,
                "conversation_message",
                message.role,
                message.id,
                withTag
            )
        }
    }

    /** Puts a tag such as [T7] at the start of a user message. Assistant messages never get one. */
    private fun withTurnTag(message: Message, mapped: ChatMessage): ChatMessage {
        val number = message.turnNo ?: return mapped
        if (message.role != "user") return mapped
        val tag = "[T$number]"
        val parts = mapped.parts
        if (!parts.isNullOrEmpty()) {
            val first = parts.first()
            val newParts = if (first.type == "text") {
                listOf(ChatPart(type = "text", text = tag + "\n" + (first.text ?: ""))) + parts.drop(1)
            } else {
                listOf(ChatPart(type = "text", text = tag)) + parts
            }
            return mapped.copy(parts = newParts)
        }
        return mapped.copy(content = tag + "\n" + mapped.content)
    }

    private suspend fun cardText(card: RequestCard): String {
        val raw = if (card.source == "file") {
            readFile(card.fileUri, card.displayName())
        } else {
            card.text
        }
        if (raw.isBlank()) {
            throw RequestBuildException("The \"${card.displayName()}\" card is empty.")
        }
        val rendered = renderVars(raw)
        return if (card.wrapTag.isNotBlank()) "[${card.wrapTag}]\n$rendered\n[/${card.wrapTag}]" else rendered
    }

    private fun snapshotPreview(message: ChatMessage): String {
        val parts = message.parts
        if (parts.isNullOrEmpty()) return message.content
        return parts.joinToString("\n") { part ->
            when (part.type) {
                "text" -> part.text.orEmpty()
                "image_url" -> "[image attachment]"
                else -> "[${part.type} attachment]"
            }
        }
    }

    private fun exactPayload(message: ChatMessage): String = buildString {
        append(message.role).append('\u0000')
        append(message.content).append('\u0000')
        message.parts.orEmpty().forEach { part ->
            append(part.type).append('\u0000')
            append(part.text.orEmpty()).append('\u0000')
            append(part.imageUrl?.url.orEmpty()).append('\u0000')
            append(part.imageUrl?.detail.orEmpty()).append('\u0000')
        }
        append(message.toolCallId.orEmpty()).append('\u0000')
        append(message.name.orEmpty()).append('\u0000')
        message.toolCalls.orEmpty().forEach { call ->
            append(call.id).append('\u0000')
            append(call.name).append('\u0000')
            append(call.arguments).append('\u0000')
            append(call.extraContent?.toString().orEmpty()).append('\u0000')
        }
        append(message.extraContent?.toString().orEmpty())
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
