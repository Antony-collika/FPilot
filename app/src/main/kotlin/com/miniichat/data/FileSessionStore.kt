package com.miniichat.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Creates a private session directory under a user-selected Storage tree using Android's
 * Storage Access Framework. Existing memory.md files are never rewritten, so hand edits persist.
 */
class FileSessionStore(
    private val resolver: ContentResolver
) : SessionFilesRepository {

    private data class Child(val id: String, val name: String, val mimeType: String) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    override suspend fun ensureSession(storageTreeUri: String, sessionId: String): SessionFiles =
        withContext(Dispatchers.IO) {
            require(storageTreeUri.isNotBlank()) { "Choose a Storage folder first." }
            require(SESSION_ID.matches(sessionId)) { "The session ID is invalid." }

            val treeUri = Uri.parse(storageTreeUri)
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                ?: throw IOException("Could not open the selected Storage folder.")
            val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
            val sessions = findOrCreateDirectory(treeUri, root, "sessions")
            val existing = listChildren(treeUri, sessions)
                .firstOrNull { it.isDirectory && it.name.endsWith("_$sessionId") }
            val folder = if (existing != null) {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.id)
            } else {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                findOrCreateDirectory(treeUri, sessions, "${date}_$sessionId")
            }
            val folderName = queryDisplayName(folder) ?: "session_$sessionId"

            val history = findOrCreateFile(treeUri, folder, "history.md", "")
            val memory = findOrCreateFile(treeUri, folder, "memory.md", MemoryDoc.template())
            SessionFiles(
                sessionId = sessionId,
                directoryName = folderName,
                historyUri = history.toString(),
                memoryUri = memory.toString()
            )
        }

    private fun findOrCreateDirectory(treeUri: Uri, parent: Uri, name: String): Uri {
        val found = listChildren(treeUri, parent).firstOrNull { it.name == name }
        if (found != null) {
            if (!found.isDirectory) throw IOException("\"$name\" exists but is not a folder.")
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, found.id)
        }
        return DocumentsContract.createDocument(
            resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw IOException("Could not create the folder \"$name\".")
    }

    private fun findOrCreateFile(treeUri: Uri, parent: Uri, name: String, initialText: String): Uri {
        val found = listChildren(treeUri, parent).firstOrNull { it.name == name }
        if (found != null) {
            if (found.isDirectory) throw IOException("\"$name\" exists but is a folder.")
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, found.id)
        }
        val uri = DocumentsContract.createDocument(resolver, parent, "text/markdown", name)
            ?: throw IOException("Could not create the file \"$name\".")
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not write the file \"$name\".")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(initialText) }
        return uri
    }

    private fun listChildren(treeUri: Uri, parent: Uri): List<Child> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val result = mutableListOf<Child>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                result += Child(
                    id = cursor.getString(idIndex),
                    name = cursor.getString(nameIndex).orEmpty(),
                    mimeType = cursor.getString(mimeIndex).orEmpty()
                )
            }
        } ?: throw IOException("Could not list the selected Storage folder.")
        return result
    }

    private fun queryDisplayName(uri: Uri): String? =
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    companion object {
        private val SESSION_ID = Regex("[0-9a-f]{8}")

        /** Eight lowercase hex characters, generated independently of chat message IDs. */
        fun newSessionId(): String =
            UUID.randomUUID().toString().replace("-", "").take(8).lowercase(Locale.ROOT)
    }
}
