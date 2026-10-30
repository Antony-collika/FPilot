package com.miniichat.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException

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
        private const val MAX_READ_BYTES = 2_000_000
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
        return "[DIRECTORY_TREE verified=$status: scanned by the app just now. Not typed by the user.]\n" +
            lines.joinToString("\n") + "\n[/DIRECTORY_TREE]"
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

    /** Finds a 1-based line's zero-based character offset without buffering the file. */
    suspend fun lineStartOffset(path: String, startLine: Int): Int? {
        require(startLine >= 1) { "startLine must be positive." }
        val uri = resolve(path)
        require(!isDirectory(uri)) { "Cannot read a directory as a file." }
        if (startLine == 1) return 0
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("The document provider could not open this file.")
        return input.bufferedReader(Charsets.UTF_8).use { reader ->
            var line = 1
            var offset = 0
            while (line < startLine) {
                val next = reader.read()
                if (next < 0) return@use null
                offset++
                when (next.toChar()) {
                    '\n' -> line++
                    '\r' -> {
                        reader.mark(1)
                        val following = reader.read()
                        if (following == '\n'.code) {
                            offset++
                        } else if (following >= 0) {
                            reader.reset()
                        }
                        line++
                    }
                }
                if (offset == Int.MAX_VALUE) {
                    throw IOException("File offset exceeds the supported character range.")
                }
            }
            offset
        }
    }

    /**
     * Reads text up to maxBytes of encoded content from a zero-based UTF-16 character offset.
     * Continuation offsets remain character-based for compatibility with line-range reads,
     * while the maximum chunk size is enforced in bytes.
     */
    suspend fun readTextChunk(path: String, startOffset: Int = 0, maxBytes: Int, charset: Charset = Charsets.UTF_8): TextChunk {
        require(startOffset >= 0) { "startOffset must not be negative." }
        require(maxBytes > 0) { "maxBytes must be positive." }
        val uri = resolve(path)
        require(!isDirectory(uri)) { "Cannot read a directory as a file." }
        val metadataSize = try { fileEntry(path)?.sizeBytes } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        val input = contentResolver.openInputStream(uri) ?: throw IOException("The document provider could not open this file.")
        return input.bufferedReader(charset).use { reader ->
            val skipBuffer = CharArray(8_192)
            var remainingToSkip = startOffset
            while (remainingToSkip > 0) {
                val count = reader.read(skipBuffer, 0, minOf(skipBuffer.size, remainingToSkip))
                if (count < 0) return@use TextChunk(path, startOffset, "", startOffset, false, metadataSize)
                remainingToSkip -= count
            }
            val result = StringBuilder()
            var consumedBytes = 0
            var hasMore = false
            while (true) {
                reader.mark(2)
                val first = reader.read()
                if (first < 0) break
                val text = if (first.toChar().isHighSurrogate()) {
                    val second = reader.read()
                    if (second >= 0 && second.toChar().isLowSurrogate()) "${first.toChar()}${second.toChar()}"
                    else { reader.reset(); reader.read(); first.toChar().toString() }
                } else first.toChar().toString()
                val encodedBytes = text.toByteArray(charset).size
                if (consumedBytes + encodedBytes > maxBytes) { reader.reset(); hasMore = true; break }
                result.append(text)
                consumedBytes += encodedBytes
            }
            TextChunk(path, startOffset, result.toString(), startOffset + result.length, hasMore, metadataSize)
        }
    }

    suspend fun readText(
        path: String,
        charset: Charset = Charsets.UTF_8,
        maxBytes: Int = MAX_READ_BYTES
    ): String {
        val chunk = readTextChunk(path, startOffset = 0, maxBytes = maxBytes, charset = charset)
        if (chunk.hasMore) {
            throw IOException("File exceeds the $maxBytes byte read limit.")
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

    /** Returns a content URI only when the relative path resolves to an existing file. */
    suspend fun existingFileUri(path: String): String? = runCatching {
        val uri = resolve(path)
        if (isDirectory(uri)) null else uri.toString()
    }.getOrNull()

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
