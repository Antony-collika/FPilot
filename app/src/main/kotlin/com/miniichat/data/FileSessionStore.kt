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

    override suspend fun ensureSession(storageTreeUri: String, sessionId: String, useSidecarForNewSession: Boolean): SessionFiles =
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
            if (existing != null && useSidecarForNewSession) {
                throw IOException("A session with this ID already exists.")
            }
            val isNewSessionFolder = existing == null
            val folder = if (existing != null) {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.id)
            } else {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                findOrCreateDirectory(treeUri, sessions, "${date}_$sessionId")
            }
            val folderName = queryDisplayName(folder) ?: "session_$sessionId"

            val history = findOrCreateFile(treeUri, folder, "history.md", "")
            val memory = findOrCreateFile(treeUri, folder, "memory.md", MemoryDoc.template())
            // A sidecar is the format marker. Never add one to an existing session folder:
            // sessions created before this refactor remain on their original on-disk format.
            val metadata = if (isNewSessionFolder && useSidecarForNewSession) {
                findOrCreateFile(treeUri, folder, "history.meta.json", HistoryMeta.empty(sessionId), "application/json")
            } else {
                listChildren(treeUri, folder).firstOrNull { !it.isDirectory && it.name == "history.meta.json" }
                    ?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id) }
            }
            SessionFiles(
                sessionId = sessionId,
                directoryName = folderName,
                historyUri = history.toString(),
                memoryUri = memory.toString(),
                historyMetaUri = metadata?.toString()
            )
        }

    /** Locate a session's existing files without creating missing directories or documents. */
    override suspend fun findSession(storageTreeUri: String, sessionId: String): SessionFiles? =
        withContext(Dispatchers.IO) {
            if (storageTreeUri.isBlank() || !SESSION_ID.matches(sessionId)) return@withContext null
            val treeUri = Uri.parse(storageTreeUri)
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                ?: return@withContext null
            val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
            val sessions = listChildren(treeUri, root).firstOrNull {
                it.isDirectory && it.name == "sessions"
            } ?: return@withContext null
            val folder = listChildren(treeUri, DocumentsContract.buildDocumentUriUsingTree(treeUri, sessions.id))
                .firstOrNull { it.isDirectory && it.name.endsWith("_$sessionId") }
                ?: return@withContext null
            val folderUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, folder.id)
            val children = listChildren(treeUri, folderUri).filterNot { it.isDirectory }
            val history = children.firstOrNull { it.name == "history.md" } ?: return@withContext null
            val memory = children.firstOrNull { it.name == "memory.md" } ?: return@withContext null
            val metadata = children.firstOrNull { it.name == "history.meta.json" }
            SessionFiles(
                sessionId = sessionId,
                directoryName = folder.name,
                historyUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, history.id).toString(),
                memoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, memory.id).toString(),
                historyMetaUri = metadata?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id).toString() }
            )
        }

    /** Remove a newly-created session directory if its branch operation cannot be committed. */
    override suspend fun deleteSession(files: SessionFiles) = withContext(Dispatchers.IO) {
        val historyUri = Uri.parse(files.historyUri)
        val documentId = DocumentsContract.getDocumentId(historyUri)
        val parentId = documentId.substringBeforeLast('/', "")
        if (parentId.isBlank()) throw IOException("Could not locate the session folder for cleanup.")
        val folder = DocumentsContract.buildDocumentUriUsingTree(historyUri, parentId)
        listChildren(historyUri, folder).forEach { child ->
            val childUri = DocumentsContract.buildDocumentUriUsingTree(historyUri, child.id)
            if (!DocumentsContract.deleteDocument(resolver, childUri)) {
                throw IOException("Could not remove the incomplete session file ${child.name}.")
            }
        }
        if (!DocumentsContract.deleteDocument(resolver, folder)) {
            throw IOException("Could not remove the incomplete session folder.")
        }
    }

    /** Read a session Markdown file using the persisted Storage Access Framework URI. */
    override suspend fun readText(uriString: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IOException("Could not read the session file.")
    }

    /** Replace a session Markdown file. Callers must re-read it immediately before writing. */
    override suspend fun writeText(uriString: String, text: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not write the session file.")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    /** Create a sibling backup before any repair operation. */
    override suspend fun backupFile(uriString: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val originalName = queryDisplayName(uri) ?: throw IOException("Could not determine the file name for backup.")
        val treeUri = uri
        val documentId = DocumentsContract.getDocumentId(uri)
        val parentId = documentId.substringBeforeLast('/', "")
        if (parentId.isBlank()) throw IOException("Could not determine the parent folder for backup.")
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val base = originalName.removeSuffix(".md")
        val backupName = "$base.broken-$stamp.md"
        val backup = DocumentsContract.createDocument(resolver, parent, "text/markdown", backupName)
            ?: throw IOException("Could not create the backup file.")
        val original = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not read the original file for backup.")
        resolver.openOutputStream(backup, "wt")?.use { it.write(original) }
            ?: throw IOException("Could not write the backup file.")
        backup.toString()
    }

    /** Repairs a Memory file while retaining content under recognized section headings. */
    override suspend fun repairMemory(uriString: String): String {
        val original = readText(uriString)
        val backup = backupFile(uriString)
        val repaired = runCatching { MemoryDoc.parse(original) }.getOrElse {
            val sections = MemoryDoc.HEADINGS.associateWith { mutableListOf<String>() }
            val preamble = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            var current: String? = null
            for (line in original.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
                if (line.startsWith("## ")) {
                    val name = line.removePrefix("## ").trim()
                    if (name in sections) current = name
                    else {
                        skipped += name
                        current = null
                    }
                } else if (current != null) {
                    if (line.isNotBlank()) sections.getValue(current!!).add(line.trimEnd())
                } else if (line.isNotBlank()) {
                    preamble += line
                }
            }
            preamble += skipped.map { name ->
                "<!-- Repair note: skipped unknown Memory heading: " + name.replace("--", "—") + " -->"
            }
            MemoryDoc(preamble.joinToString("\n"), sections.mapValues { it.value.toList() })
        }
        writeText(uriString, repaired.render())
        return backup
    }

    /** Repairs history only after successfully backing up the original bytes. */
    override suspend fun repairHistory(uriString: String, sessionId: String, title: String): String {
        val original = readText(uriString)
        val repaired = HistoryMd.repair(original, sessionId, title)
        val backup = backupFile(uriString)
        writeText(uriString, HistoryMd.render(repaired))
        return backup
    }

    override suspend fun createHistoryTemplate(uriString: String, sessionId: String, title: String) {
        writeText(uriString, HistoryMd.render(HistoryMd.empty(sessionId, title)))
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

    private fun findOrCreateFile(treeUri: Uri, parent: Uri, name: String, initialText: String, mimeType: String = "text/markdown"): Uri {
        val found = listChildren(treeUri, parent).firstOrNull { it.name == name }
        if (found != null) {
            if (found.isDirectory) throw IOException("\"$name\" exists but is a folder.")
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, found.id)
        }
        val uri = DocumentsContract.createDocument(resolver, parent, mimeType, name)
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
