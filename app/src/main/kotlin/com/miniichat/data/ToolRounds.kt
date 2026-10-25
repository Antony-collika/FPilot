package com.miniichat.data

import com.miniichat.api.ChatMessage
import com.miniichat.api.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Settings for saving and sending tool rounds. They are constants for now;
 * later they will move onto the History card of the Request Control Panel.
 */
object ToolRoundSettings {
    /** true  = old turns send a short one-line note instead of the full tool result.
     *  false = old turns send the full tool result. */
    const val COMPACT_HISTORY: Boolean = true

    /** Results shorter than this are always sent as they are. */
    const val COMPACT_MIN_CHARS: Int = 500

    /** Safety limit for the full text kept in storage (the whole chat history is one JSON string). */
    const val MAX_STORED_RESULT_CHARS: Int = 30_000
}

private const val MISSING_RESULT_TEXT = "No result was recorded for this tool call."

private class RoundDraft(
    val content: String,
    val extraContent: JsonObject?,
    val calls: List<StoredToolCall>
) {
    val results = mutableListOf<StoredToolResult>()
}

/**
 * Turns the messages the agent produced during one turn (assistant tool calls and
 * tool results, without the first request messages and without the final answer)
 * into storable rounds.
 */
fun buildToolRounds(generated: List<ChatMessage>, turnNo: Int?): List<ToolRound> {
    val drafts = mutableListOf<RoundDraft>()
    for (message in generated) {
        val calls = message.toolCalls
        if (message.role == "assistant" && !calls.isNullOrEmpty()) {
            drafts += RoundDraft(
                content = message.content,
                extraContent = message.extraContent,
                calls = calls.map { StoredToolCall(it.id, it.name, it.arguments, it.extraContent) }
            )
        } else if (message.role == "tool") {
            val draft = drafts.lastOrNull()
            if (draft != null) {
                val call = draft.calls.firstOrNull { it.id == message.toolCallId }
                draft.results += makeStoredResult(message, call, turnNo)
            }
        }
    }
    return drafts.map { draft ->
        // Every call must have exactly one result, in the same order as the calls.
        val ordered = draft.calls.map { call ->
            draft.results.firstOrNull { it.toolCallId == call.id }
                ?: StoredToolResult(toolCallId = call.id, name = call.name, content = MISSING_RESULT_TEXT)
        }
        ToolRound(
            content = draft.content,
            toolCalls = draft.calls,
            results = ordered,
            extraContent = draft.extraContent
        )
    }
}

private fun makeStoredResult(message: ChatMessage, call: StoredToolCall?, turnNo: Int?): StoredToolResult {
    val full = message.content
    val name = message.name?.takeIf { it.isNotBlank() } ?: call?.name ?: "tool"
    val limit = ToolRoundSettings.MAX_STORED_RESULT_CHARS
    val stored = if (full.length > limit) {
        full.take(limit) + "\n[...cut when saving: ${full.length - limit} more characters]"
    } else {
        full
    }
    val compact = if (full.length >= ToolRoundSettings.COMPACT_MIN_CHARS) {
        compactStub(name, call?.arguments, full.length, turnNo)
    } else {
        null
    }
    return StoredToolResult(
        toolCallId = message.toolCallId.orEmpty(),
        name = name,
        content = stored,
        compact = compact
    )
}

/** The one-line note that replaces a long tool result in old turns. */
internal fun compactStub(toolName: String, arguments: String?, length: Int, turnNo: Int?): String {
    val target = extractTarget(arguments)
    val targetPart = if (target.isNullOrBlank()) "" else " \"$target\""
    val turnPart = if (turnNo != null) ", turn T$turnNo" else ""
    return "[Kết quả đã rút gọn: $toolName$targetPart, $length ký tự$turnPart. Gọi lại tool nếu cần nội dung.]"
}

private fun extractTarget(arguments: String?): String? {
    if (arguments.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(arguments).jsonObject
        (obj["path"] ?: obj["query"])?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}

/**
 * Turns stored rounds back into the messages of a request:
 * assistant (with tool calls), then one tool message per result.
 */
fun toolRoundsToChatMessages(rounds: List<ToolRound>, compact: Boolean): List<ChatMessage> {
    val out = mutableListOf<ChatMessage>()
    for (round in rounds) {
        if (round.toolCalls.isEmpty()) continue
        out += ChatMessage(
            role = "assistant",
            content = round.content,
            toolCalls = round.toolCalls.map { ToolCall(it.id, it.name, it.arguments, it.extraContent) },
            extraContent = round.extraContent
        )
        for (result in round.results) {
            val text = if (compact && result.compact != null) result.compact else result.content
            out += ChatMessage(
                role = "tool",
                content = text,
                toolCallId = result.toolCallId,
                name = result.name
            )
        }
    }
    return out
}

/**
 * Gives a number to every user message that has none (old chats), in order,
 * and makes sure [Conversation.nextTurnNo] is higher than every number in use.
 */
fun Conversation.withTurnNumbers(): Conversation {
    val highest = messages.mapNotNull { it.turnNo }.maxOrNull() ?: 0
    var next = maxOf(nextTurnNo, highest + 1)
    if (messages.none { it.role == "user" && it.turnNo == null }) {
        return if (next == nextTurnNo) this else copy(nextTurnNo = next)
    }
    val numbered = messages.map { message ->
        if (message.role == "user" && message.turnNo == null) {
            val assigned = next
            next += 1
            message.copy(turnNo = assigned)
        } else {
            message
        }
    }
    return copy(messages = numbered, nextTurnNo = next)
}

/**
 * Returns this result with a new text. The short note is made again from the new text
 * (used only when the user edits a tool result by hand).
 */
fun StoredToolResult.withContent(newContent: String, arguments: String?, turnNo: Int?): StoredToolResult {
    val limit = ToolRoundSettings.MAX_STORED_RESULT_CHARS
    val stored = if (newContent.length > limit) {
        newContent.take(limit) + "\n[...cut when saving: ${newContent.length - limit} more characters]"
    } else {
        newContent
    }
    val compact = if (newContent.length >= ToolRoundSettings.COMPACT_MIN_CHARS) {
        compactStub(name, arguments, newContent.length, turnNo)
    } else {
        null
    }
    return copy(content = stored, compact = compact)
}
