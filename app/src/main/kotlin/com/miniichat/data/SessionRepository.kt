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
    val historyUri: String,
    val memoryUri: String,
    /** Non-null only for sessions created with the ID-linked sidecar format. */
    val historyMetaUri: String? = null
)

interface SessionFilesRepository {
    suspend fun ensureSession(storageTreeUri: String, sessionId: String, useSidecarForNewSession: Boolean = false): SessionFiles
    suspend fun readText(uriString: String): String
    suspend fun writeText(uriString: String, text: String)
    suspend fun backupFile(uriString: String): String
    suspend fun repairHistory(uriString: String, sessionId: String, title: String = "New chat"): String
    suspend fun repairMemory(uriString: String): String
    suspend fun createHistoryTemplate(uriString: String, sessionId: String, title: String = "New chat")

    suspend fun readHistory(files: SessionFiles, fallbackTitle: String = "New chat"): HistoryDocument {
        val markdown = readText(files.historyUri)
        val metadata = files.historyMetaUri?.let { readText(it) }
        return HistoryMd.parse(markdown, files.sessionId, fallbackTitle, metadata)
    }

    /** Validate both serialized representations before writing either file. */
    suspend fun writeHistory(files: SessionFiles, document: HistoryDocument) {
        if (files.historyMetaUri == null) {
            writeText(files.historyUri, HistoryMd.render(document))
            return
        }
        val metadata = HistoryMeta.render(document)
        HistoryMeta.parse(metadata, document.sessionId)
        val markdown = HistoryMd.render(document, sidecarFormat = true)
        HistoryMd.parse(markdown, document.sessionId, document.title, metadata)
        // SAF has no portable atomic transaction across two documents. First write a
        // union containing both old and new IDs, then commit Markdown, and only then prune
        // unused metadata. A process stop between the first two writes remains readable.
        val previousMetadata = readText(files.historyMetaUri)
        val previousMarkdown = readText(files.historyUri)
        val retainedMetadata = HistoryMeta.renderRetainingPrevious(previousMetadata, document)
        try {
            writeText(files.historyMetaUri, retainedMetadata)
            writeText(files.historyUri, markdown)
            writeText(files.historyMetaUri, metadata)
        } catch (failure: Exception) {
            // Restore in an order where the intermediate sidecar accepts either transcript.
            runCatching { writeText(files.historyMetaUri, retainedMetadata) }
                .exceptionOrNull()?.let { failure.addSuppressed(it) }
            runCatching { writeText(files.historyUri, previousMarkdown) }
                .exceptionOrNull()?.let { failure.addSuppressed(it) }
            runCatching { writeText(files.historyMetaUri, previousMetadata) }
                .exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
    }
}
