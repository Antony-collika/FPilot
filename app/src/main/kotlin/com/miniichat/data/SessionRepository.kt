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
    val memoryUri: String
)

interface SessionFilesRepository {
    suspend fun ensureSession(storageTreeUri: String, sessionId: String): SessionFiles
}
