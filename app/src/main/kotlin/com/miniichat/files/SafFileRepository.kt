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
    companion object {
        private const val MAX_READ_CHARS = 2_000_000
    }
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
                val name = cursor.getString(nameColumn) ?: throw IOException("The document provider returned an entry without a name.")
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


    /**
     * Builds a bounded, timestamp-free directory tree for prompt context.
     * Empty markers are emitted only after a successful listing confirms emptiness.
     */
    suspend fun directoryTree(maxDepth: Int, maxEntries: Int = 1_000): String {
        val depthLimit = maxDepth.coerceIn(1, 8)
        data class Node(
            val name: String,
            val directory: Boolean,
            var children: List<Node>? = null,
            var empty: Boolean = false,
            var error: Boolean = false
        )

        var visited = 0
        var complete = true
        suspend fun scan(path: String, depth: Int): List<Node> {
            val entries = try {
                list(path)
            } catch (_: Exception) {
                complete = false
                return listOf(Node("[scan error: listing unavailable]", false, error = true))
            }
            val nodes = mutableListOf<Node>()
            for (entry in entries) {
                if (visited >= maxEntries) {
                    complete = false
                    nodes += Node("[tree truncated at $maxEntries entries]", false)
                    break
                }
                visited++
                val node = Node(entry.name, entry.isDirectory)
                if (entry.isDirectory && depth + 1 < depthLimit) {
                    val children = scan(entry.relativePath, depth + 1)
                    node.children = children
                    node.empty = children.isEmpty() && complete
                }
                nodes += node
            }
            return nodes
        }

        val nodes = scan("", 0)
        val lines = mutableListOf<String>()
        if (nodes.isEmpty()) lines += "└── (empty)"
        fun render(items: List<Node>, prefix: String) {
            items.forEachIndexed { index, node ->
                val last = index == items.lastIndex
                val branch = if (last) "└── " else "├── "
                val suffix = if (node.directory) "/" else ""
                lines += prefix + branch + node.name + suffix
                val nextPrefix = prefix + if (last) "    " else "│   "
                if (node.error) return@forEachIndexed
                if (node.empty) {
                    lines += nextPrefix + "└── (empty)"
                } else if (node.children != null && node.children!!.isNotEmpty()) {
                    render(node.children!!, nextPrefix)
                }
            }
        }
        render(nodes, "")
        val status = if (complete) "true" else "false"
        return "[TREE depth=$depthLimit, verified=$status]\n" +
            lines.joinToString("\n") + "\n[/TREE]"
    }

    data class TextChunk(
        val path: String,
        val startOffset: Int,
        val content: String,
        val nextOffset: Int,
        val hasMore: Boolean,
        val sizeBytes: Long?
    )

    /** Returns metadata without opening or reading the file contents. */
    suspend fun fileEntry(path: String): Entry? {
        val normalized = normalizeRelativePath(path)
        require(normalized.isNotBlank()) { "A file path is required." }
        val parent = normalized.substringBeforeLast('/', "")
        val name = normalized.substringAfterLast('/')
        return list(parent).firstOrNull { it.name == name }
    }

    /**
     * Reads at most maxChars UTF-16 characters starting at a zero-based character offset.
     * The extra character is only used to determine hasMore and is never returned.
     */
    suspend fun readTextChunk(
        path: String,
        startOffset: Int = 0,
        maxChars: Int,
        charset: Charset = Charsets.UTF_8
    ): TextChunk {
        require(startOffset >= 0) { "startOffset must not be negative." }
        require(maxChars > 0) { "maxChars must be positive." }
        val uri = resolve(path)
        require(!isDirectory(uri)) { "Cannot read a directory as a file." }
        val metadataSize = runCatching { fileEntry(path)?.sizeBytes }.getOrNull()
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("The document provider could not open this file.")
        return input.bufferedReader(charset).use { reader ->
            val skipBuffer = CharArray(8_192)
            var remainingToSkip = startOffset
            while (remainingToSkip > 0) {
                val count = reader.read(skipBuffer, 0, minOf(skipBuffer.size, remainingToSkip))
                if (count < 0) {
                    return@use TextChunk(path, startOffset, "", startOffset, false, metadataSize)
                }
                remainingToSkip -= count
            }

            val result = StringBuilder()
            val buffer = CharArray(8_192)
            while (result.length <= maxChars) {
                val remaining = maxChars + 1 - result.length
                if (remaining <= 0) break
                val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                result.append(buffer, 0, count)
            }
            val hasMore = result.length > maxChars
            val content = if (hasMore) result.substring(0, maxChars) else result.toString()
            TextChunk(
                path = path,
                startOffset = startOffset,
                content = content,
                nextOffset = startOffset + content.length,
                hasMore = hasMore,
                sizeBytes = metadataSize
            )
        }
    }

    suspend fun readText(
        path: String,
        charset: Charset = Charsets.UTF_8,
        maxChars: Int = MAX_READ_CHARS
    ): String {
        val chunk = readTextChunk(path, startOffset = 0, maxChars = maxChars, charset = charset)
        if (chunk.hasMore) {
            throw IOException("File exceeds the $maxChars character read limit.")
        }
        return chunk.content
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
