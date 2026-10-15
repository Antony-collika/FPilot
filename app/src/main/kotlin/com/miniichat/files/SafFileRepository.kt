package com.miniichat.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.nio.charset.Charset

/**
 * Small SAF adapter scoped to a user-selected tree URI.
 *
 * Paths accepted by this class are relative paths (for example "notes/PLAN.md"),
 * never filesystem paths. Each segment is resolved through DocumentsContract.
 */
class SafFileRepository(
    private val contentResolver: ContentResolver,
    private val treeUri: Uri
) {
    data class Entry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val mimeType: String?,
        val sizeBytes: Long?
    )

    suspend fun list(path: String = ""): List<Entry> {
        val parent = resolve(path)
        require(isDirectory(parent)) { "The requested path is not a directory." }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parent)
        )
        val result = mutableListOf<Entry>()
        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null,
            null,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn) ?: continue
                val mime = cursor.getString(mimeColumn)
                val relative = listOf(path.trim('/'), name).filter { it.isNotBlank() }.joinToString("/")
                result += Entry(
                    name = name,
                    relativePath = relative,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    mimeType = mime,
                    sizeBytes = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else null
                )
            }
        } ?: throw IOException("The document provider could not list this directory.")
        return result
    }

    suspend fun readText(path: String, charset: Charset = Charsets.UTF_8): String {
        val uri = resolve(path)
        require(!isDirectory(uri)) { "Cannot read a directory as a file." }
        return contentResolver.openInputStream(uri)?.bufferedReader(charset)?.use { it.readText() }
            ?: throw IOException("The document provider could not open this file.")
    }

    suspend fun writeText(path: String, content: String, mimeType: String = "text/markdown"): Uri {
        val normalized = normalizeRelativePath(path)
        require(normalized.isNotBlank()) { "A file path is required." }
        val segments = normalized.split('/')
        var parent = rootDocumentUri()
        for (segment in segments.dropLast(1)) {
            val child = findChild(parent, segment)
                ?: throw IOException("Parent directory does not exist: $segment")
            require(isDirectory(child)) { "A parent path segment is not a directory: $segment" }
            parent = child
        }

        val fileName = segments.last()
        val existing = findChild(parent, fileName)
        val target = if (existing != null) {
            require(!isDirectory(existing)) { "A directory already exists at the target path." }
            existing
        } else {
            DocumentsContract.createDocument(
                contentResolver,
                parent,
                mimeType,
                fileName
            ) ?: throw IOException("The document provider could not create the file.")
        }

        contentResolver.openOutputStream(target, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(content)
        } ?: throw IOException("The document provider could not write this file.")
        return target
    }

    suspend fun exists(path: String): Boolean = runCatching { resolve(path) }.isSuccess

    private fun rootDocumentUri(): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )

    private fun resolve(path: String): Uri {
        val normalized = normalizeRelativePath(path)
        var current = rootDocumentUri()
        if (normalized.isBlank()) return current

        for (segment in normalized.split('/')) {
            current = findChild(current, segment)
                ?: throw IOException("Path not found: $normalized")
        }
        return current
    }

    private fun findChild(parent: Uri, name: String): Uri? {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                }
            }
        }
        return null
    }

    private fun isDirectory(uri: Uri): Boolean {
        val mime = contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun normalizeRelativePath(path: String): String {
        val normalizedSeparators = path.trim().replace('\\', '/')
        require(!normalizedSeparators.startsWith('/')) {
            "Absolute paths are not allowed."
        }
        val value = normalizedSeparators.trim('/')
        if (value.isBlank()) return ""
        val segments = value.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Path must stay inside the selected folder."
        }
        require(!value.startsWith('/') && !value.contains(':')) {
            "Absolute paths and URI-like paths are not allowed."
        }
        return segments.joinToString("/")
    }
}
