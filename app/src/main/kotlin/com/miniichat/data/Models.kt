package com.miniichat.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

enum class Role { user, assistant, system }

@Serializable
data class Attachment(
    val type: String,        // "image" | "file"
    val uri: String,         // content:// or file path
    val mimeType: String,
    val name: String,
    val sizeBytes: Long = 0
)

/** One tool call the model made, saved exactly as the model sent it. */
@Serializable
data class StoredToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    @SerialName("extra_content") val extraContent: JsonObject? = null
)

/**
 * The result of one tool call.
 * [content] is the full text (cut at a safety limit when very long).
 * [compact] is the short one-line replacement sent for old turns. It is created
 * once when the turn finishes and never recomputed, so the request stays stable.
 * It is null when the result is short enough to be sent as it is.
 */
@Serializable
data class StoredToolResult(
    @SerialName("tool_call_id") val toolCallId: String,
    val name: String,
    val content: String,
    val compact: String? = null
)

/**
 * One round of the agent: the assistant asks for tools, and the results come back.
 * Calls and results always live in the same object, so they cannot get separated.
 */
@Serializable
data class ToolRound(
    val content: String = "",
    @SerialName("tool_calls") val toolCalls: List<StoredToolCall> = emptyList(),
    val results: List<StoredToolResult> = emptyList(),
    @SerialName("extra_content") val extraContent: JsonObject? = null,
    /** Workspace tree URI in which these tool calls were executed; required before replaying reads. */
    @SerialName("workspace_uri") val workspaceUri: String? = null
)

@Serializable
data class Message(
    val id: String,
    val role: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    @SerialName("extra_content") val extraContent: JsonObject? = null,
    /** Turn number (1, 2, 3...). Only set on user messages. Never reused. */
    @SerialName("turn_no") val turnNo: Int? = null,
    /** Tool rounds of the turn. Only set on the final assistant message of a turn. */
    @SerialName("tool_rounds") val toolRounds: List<ToolRound> = emptyList(),
    /** Branch where this message was originally created; inherited messages keep their origin. */
    @SerialName("branch_id") val branchId: String? = null
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** The number the next turn will get. It only goes up, deleting a turn never lowers it. */
    @SerialName("next_turn_no") val nextTurnNo: Int = 1,
    /** Random eight-hex-character identifier used for a file-backed session folder. */
    @SerialName("session_id") val sessionId: String? = null,
    /** Persisted Storage tree URI; changing Settings does not redirect an existing session. */
    @SerialName("session_storage_uri") val sessionStorageUri: String? = null,
    /** Active branch is mirrored from history.meta.json for UI state. */
    @SerialName("active_branch_id") val activeBranchId: String? = null,
    /** Available file-backed branches, mirrored from history.meta.json. */
    @SerialName("history_branches") val historyBranches: List<HistoryBranchMetadata> = emptyList()
)
