package com.miniichat.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Conversation persistence boundary. The DataStore implementation deliberately preserves
 * existing chats; FileSessionStore owns the external session directory and its Markdown files.
 */
interface SessionRepository {
    val conversationsFlow: Flow<List<Conversation>>
    suspend fun snapshot(): List<Conversation>
    suspend fun upsert(conv: Conversation)
    suspend fun delete(id: String)
    suspend fun rename(id: String, newTitle: String)
}

/** Compatibility backend for conversations created before Markdown history is enabled. */
class DataStoreSessionRepository(context: Context) : SessionRepository {
    private val delegate = ConversationStore(context)

    override val conversationsFlow: Flow<List<Conversation>> = delegate.conversationsFlow
    override suspend fun snapshot(): List<Conversation> = delegate.snapshot()
    override suspend fun upsert(conv: Conversation) = delegate.upsert(conv)
    override suspend fun delete(id: String) = delegate.delete(id)
    override suspend fun rename(id: String, newTitle: String) = delegate.rename(id, newTitle)
}

/** SAF-backed files for one session. */
data class SessionFiles(
    val sessionId: String,
    val directoryName: String,
    /** URI of the currently active history_branch_NN.md. */
    val historyUri: String,
    val memoryUri: String,
    val historyMetaUri: String? = null,
    val directoryUri: String = "",
    val storageTreeUri: String = "",
    val activeBranchId: String = "branch_01",
    val branches: List<HistoryBranchMetadata> = emptyList(),
    val nextBranchNumber: Int = 2
)

interface SessionFilesRepository {
    suspend fun ensureSession(storageTreeUri: String, sessionId: String, useSidecarForNewSession: Boolean = false): SessionFiles
    suspend fun readText(uriString: String): String
    suspend fun writeText(uriString: String, text: String)
    suspend fun backupFile(uriString: String): String
    suspend fun repairHistory(uriString: String, sessionId: String, title: String = "New chat"): String
    suspend fun repairMemory(uriString: String): String
    suspend fun createHistoryTemplate(uriString: String, sessionId: String, title: String = "New chat")
    suspend fun switchHistoryBranch(files: SessionFiles, branchId: String): SessionFiles
    suspend fun createHistoryBranch(files: SessionFiles, document: HistoryDocument, parentBranchId: String?): SessionFiles
    suspend fun syncHistoryMetadata(files: SessionFiles, document: HistoryDocument)

    suspend fun readHistory(files: SessionFiles, fallbackTitle: String = "New chat"): HistoryDocument {
        val markdown = readText(files.historyUri)
        val metadata = files.historyMetaUri?.let { readText(it) }
        return HistoryMd.parse(markdown, files.sessionId, fallbackTitle, metadata, files.activeBranchId)
    }

    /** Validate both serialized representations before writing either file. */
    suspend fun writeHistory(files: SessionFiles, document: HistoryDocument) {
        if (files.historyMetaUri == null) {
            writeText(files.historyUri, HistoryMd.render(document))
            return
        }
        val previousMetadata = readText(files.historyMetaUri)
        val previousMarkdown = readText(files.historyUri)
        val branchDocument = document.copy(branchId = files.activeBranchId)
        val markdown = HistoryMd.render(branchDocument, sidecarFormat = true)
        val metadata = HistoryMeta.renderForBranch(
            previousText = previousMetadata,
            document = branchDocument,
            activeBranchId = files.activeBranchId,
            branches = files.branches,
            nextBranchNumber = files.nextBranchNumber
        )
        HistoryMeta.parse(metadata, document.sessionId)
        HistoryMd.parse(markdown, document.sessionId, document.title, metadata, files.activeBranchId)
        // Preserve all old IDs in the sidecar until the currently selected Markdown has
        // committed. The metadata is app-managed; normal editing only requires editing MD.
        try {
            writeText(files.historyMetaUri, metadata)
            writeText(files.historyUri, markdown)
        } catch (failure: Exception) {
            runCatching { writeText(files.historyMetaUri, HistoryMeta.renderRetainingPrevious(previousMetadata, branchDocument)) }
                .exceptionOrNull()?.let { failure.addSuppressed(it) }
            runCatching { writeText(files.historyUri, previousMarkdown) }
                .exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
    }
}
