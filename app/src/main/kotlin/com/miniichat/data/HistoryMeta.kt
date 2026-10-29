package com.miniichat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class HistoryBranchMetadata(
    val id: String,
    val file: String,
    val parentBranchId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class HistoryMetaDocument(
    val version: Int = 2,
    val session: String,
    val messages: Map<String, HistoryMessageMetadata> = emptyMap(),
    val activeBranchId: String = "branch_01",
    val branches: List<HistoryBranchMetadata> = emptyList(),
    val nextBranchNumber: Int = 2
)

@Serializable
data class HistoryMessageMetadata(
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    @SerialName("extra_content") val extraContent: JsonObject? = null,
    @SerialName("tool_rounds") val toolRounds: List<ToolRound> = emptyList(),
    /** The branch where the message was first created; shared ancestors keep this ID. */
    val branchId: String? = null
)

/** The JSON sidecar is app-managed. Markdown remains the editable source for message text. */
object HistoryMeta {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val branchPattern = Regex("branch_[0-9]{2,}")
    private val filePattern = Regex("history_branch_[0-9]{2,}\\.md")

    fun empty(sessionId: String): String =
        renderForBranch(
            previousText = "",
            document = HistoryDocument(sessionId, "New chat", System.currentTimeMillis(), 1, emptyList()),
            activeBranchId = "branch_01",
            branches = listOf(HistoryBranchMetadata("branch_01", "history_branch_01.md", null, System.currentTimeMillis())),
            nextBranchNumber = 2
        )

    /** Accepts the existing v1 sidecar and normalizes it in memory to v2. */
    fun parse(text: String, expectedSessionId: String): HistoryMetaDocument {
        val document = json.decodeFromString<HistoryMetaDocument>(text)
        require(document.version == 1 || document.version == 2) {
            "Unsupported metadata version ${document.version}."
        }
        require(document.session == expectedSessionId) {
            "Metadata belongs to session '${document.session}', not '$expectedSessionId'."
        }
        require(expectedSessionId.matches(Regex("[0-9a-f]{8}"))) { "Invalid session ID." }
        require(document.messages.keys.all { it.isNotBlank() }) { "Metadata contains a blank message ID." }

        val normalizedBranches = (document.branches.ifEmpty {
            listOf(HistoryBranchMetadata("branch_01", "history_branch_01.md", null, 0L))
        }).distinctBy { it.id }
        require(normalizedBranches.all {
            branchPattern.matches(it.id) && filePattern.matches(it.file) &&
                it.file == "history_${it.id}.md"
        }) { "Metadata contains an invalid branch ID or file name." }
        val ids = normalizedBranches.map { it.id }.toSet()
        require(normalizedBranches.all { it.parentBranchId == null || it.parentBranchId in ids }) {
            "Metadata references a missing parent branch."
        }
        val active = document.activeBranchId.takeIf { it in ids } ?: normalizedBranches.first().id
        val maxNumber = normalizedBranches.mapNotNull { it.id.removePrefix("branch_").toIntOrNull() }.maxOrNull() ?: 1
        return document.copy(
            version = 2,
            activeBranchId = active,
            branches = normalizedBranches.sortedBy { it.id.removePrefix("branch_").toIntOrNull() ?: Int.MAX_VALUE },
            nextBranchNumber = maxOf(document.nextBranchNumber, maxNumber + 1, 2)
        )
    }

    /** Keep old IDs until the Markdown write has committed; old branches retain their metadata. */
    fun renderRetainingPrevious(previousText: String, document: HistoryDocument): String {
        val previous = parse(previousText, document.sessionId)
        return renderForBranch(
            previousText,
            document,
            previous.activeBranchId,
            previous.branches,
            previous.nextBranchNumber
        )
    }

    /** Render a single branch update while preserving metadata for every other branch. */
    fun renderForBranch(
        previousText: String,
        document: HistoryDocument,
        activeBranchId: String,
        branches: List<HistoryBranchMetadata>,
        nextBranchNumber: Int
    ): String {
        val previous = if (previousText.isBlank()) {
            HistoryMetaDocument(session = document.sessionId)
        } else {
            parse(previousText, document.sessionId)
        }
        val mergedMessages = linkedMapOf<String, HistoryMessageMetadata>()
        mergedMessages.putAll(previous.messages)
        document.messages.forEach { message ->
            require(message.id.isNotBlank()) { "Message IDs must not be blank." }
            val prior = mergedMessages[message.id]
            mergedMessages[message.id] = HistoryMessageMetadata(
                attachments = message.attachments,
                createdAt = message.createdAt,
                extraContent = message.extraContent,
                toolRounds = message.toolRounds,
                branchId = message.branchId ?: prior?.branchId ?: document.branchId
            )
        }
        val branchMap = linkedMapOf<String, HistoryBranchMetadata>()
        previous.branches.forEach { branchMap[it.id] = it }
        branches.forEach { branchMap[it.id] = it }
        if (activeBranchId !in branchMap) {
            branchMap[activeBranchId] = HistoryBranchMetadata(
                id = activeBranchId,
                file = "history_${activeBranchId}.md",
                parentBranchId = null,
                createdAt = System.currentTimeMillis()
            )
        }
        val next = maxOf(
            nextBranchNumber,
            (branchMap.keys.mapNotNull { it.removePrefix("branch_").toIntOrNull() }.maxOrNull() ?: 1) + 1,
            2
        )
        return json.encodeToString(
            HistoryMetaDocument(
                version = 2,
                session = document.sessionId,
                messages = mergedMessages,
                activeBranchId = activeBranchId,
                branches = branchMap.values.sortedBy { it.id.removePrefix("branch_").toIntOrNull() ?: Int.MAX_VALUE },
                nextBranchNumber = next
            )
        )
    }

    fun setActiveBranch(previousText: String, expectedSessionId: String, branchId: String): String {
        val document = parse(previousText, expectedSessionId)
        require(document.branches.any { it.id == branchId }) { "Unknown history branch '$branchId'." }
        return json.encodeToString(document.copy(version = 2, activeBranchId = branchId))
    }

    fun render(document: HistoryDocument): String {
        val branchId = document.branchId.ifBlank { "branch_01" }
        val number = branchId.removePrefix("branch_").toIntOrNull() ?: 1
        val branch = HistoryBranchMetadata(
            id = branchId,
            file = "history_${branchId}.md",
            parentBranchId = null,
            createdAt = document.createdAt
        )
        return renderForBranch(
            previousText = "",
            document = document,
            activeBranchId = branchId,
            branches = listOf(branch),
            nextBranchNumber = number + 1
        )
    }
}
