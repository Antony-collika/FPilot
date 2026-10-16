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
        val rootUri = rootDocumentUri()
        val rootName = contentResolver.query(
            rootUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
        if (rootName == null) {
            return "[TREE depth=$depthLimit, verified=false]\n[tree unavailable: could not determine the selected folder name]\n[/TREE]"
        }

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
        val lines = mutableListOf("$rootName/")
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
        return "[WORKSPACE PATH RULE]\n" +
            "The selected workspace root is $rootName. Its name is shown in the tree for display only. " +
            "All paths passed to file tools must be relative to this root. Do NOT prepend $rootName/ to a tool path. " +
            "For example, use \"Character/A2.md\" rather than \"$rootName/Character/A2.md\". " +
            "Use an empty path for the workspace root directory.\n" +
            "[/WORKSPACE PATH RULE]\n" +
            "[TREE depth=$depthLimit, verified=$status]\n" +
            lines.joinToString("\n") + "\n[/TREE]"
    }

    suspend fun readText(
        path: String,
        charset: Charset = Charsets.UTF_8,
        maxChars: Int = MAX_READ_CHARS
    ): String {
        require(maxChars > 0) { "maxChars must be positive." }
        val uri = resolve(path)
        require(!isDirectory(uri)) { "Cannot read a directory as a file." }
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("The document provider could not open this file.")
        return input.bufferedReader(charset).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(8_192)
            while (true) {
                val remaining = maxChars + 1 - result.length
                if (remaining <= 0) break
                val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                result.append(buffer, 0, count)
            }
            if (result.length > maxChars) {
                throw IOException("File exceeds the $maxChars character read limit.")
            }
            result.toString()
        }
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

    private fun selectedRootName(): String? = runCatching {
        contentResolver.query(
            rootDocumentUri(),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

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

        // Models sometimes mistake the display-only tree root label for a path segment.
        // Give them a safe correction hint instead of silently rewriting the requested path.
        val rootName = selectedRootName()
        if (rootName != null && segments.first() == rootName &&
            findChild(rootDocumentUri(), rootName) == null
        ) {
            val suggested = segments.drop(1).joinToString("/")
            val example = if (suggested.isBlank()) "an empty path for the workspace root" else "\"$suggested\""
            throw IOException(
                "Path includes the selected workspace root name '$rootName'. Tool paths are relative to the workspace root; " +
                    "do not prepend '$rootName/'. Try $example instead."
            )
        }
        return segments.joinToString("/")
    }
}
