package com.miniichat.data

import com.miniichat.api.ChatMessage

/** Thrown when the cards cannot be turned into a request. The message is shown to the user. */
class RequestBuildException(message: String) : Exception(message)

/**
 * Turns the card list plus the conversation into the list of messages sent to the model.
 * It walks the cards from top to bottom; the position of a card is its position in the request.
 *
 * It does not touch the phone itself: everything that needs the phone is passed in.
 * - [readFile]: reads a file chosen for a card (uri, card name) and returns its text.
 * - [directoryTree]: returns the tree text for a depth, or null when no workspace is selected.
 * - [renderVars]: replaces variables such as {date} in card text.
 * - [mapMessage]: converts one stored chat message (with attachments) to a request message.
 */
class RequestBuilder(
    private val readFile: suspend (uri: String, label: String) -> String,
    private val directoryTree: suspend (depth: Int) -> String?,
    private val renderVars: (String) -> String,
    private val mapMessage: (Message) -> ChatMessage
) {
    suspend fun build(layout: RequestLayout, messages: List<Message>): List<ChatMessage> {
        layout.validationError()?.let { throw RequestBuildException(it) }

        val usable = messages.filter { !(it.role == "assistant" && it.content.isEmpty()) }
        // The last user message starts the running turn. Everything before it is History.
        val lastUserIndex = usable.indexOfLast { it.role == "user" }
        val historyMessages = if (lastUserIndex >= 0) usable.subList(0, lastUserIndex) else usable
        val lastTurnMessages = if (lastUserIndex >= 0) usable.subList(lastUserIndex, usable.size) else emptyList()

        val out = mutableListOf<ChatMessage>()
        for (card in layout.cards) {
            if (card.role == CardRole.METADATA) continue
            if (!card.enabled && card.role !in CardRole.FIXED) continue
            when (card.role) {
                CardRole.SYSTEM -> out += ChatMessage("system", cardText(card))
                CardRole.FREE -> {
                    val label = card.roleLabel.trim()
                    if (label.isEmpty()) {
                        throw RequestBuildException("The \"${card.displayName()}\" card has no role name.")
                    }
                    out += ChatMessage(label, cardText(card))
                }
                CardRole.DIRECTORY_TREE -> {
                    val tree = directoryTree(card.treeDepth.coerceIn(1, 8))
                        ?: throw RequestBuildException(
                            "The \"${card.displayName()}\" card needs a workspace folder. " +
                                "Choose one in Settings, or switch this card off."
                        )
                    out += ChatMessage("system", tree)
                }
                CardRole.HISTORY -> out += expand(historyMessages)
                CardRole.LAST_TURN -> out += expand(lastTurnMessages)
                else -> throw RequestBuildException("Unknown card type: ${card.role}")
            }
        }
        return out
    }

    /** Messages of finished turns include their tool rounds right before the final answer. */
    private fun expand(messages: List<Message>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        for (message in messages) {
            if (message.role == "assistant" && message.toolRounds.isNotEmpty()) {
                out += toolRoundsToChatMessages(message.toolRounds, ToolRoundSettings.COMPACT_HISTORY)
            }
            out += mapMessage(message)
        }
        return out
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
}
