package com.miniichat.files

import com.miniichat.agent.AgentTool
import com.miniichat.agent.AgentToolRegistry
import com.miniichat.agent.AgentLoopUserCancelledException
import com.miniichat.api.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Initial filesystem capabilities exposed to the agent. All paths are relative
 * to the SAF tree URI held by SafFileRepository.
 */
object MarkdownFileTools {
    fun registry(
        repository: SafFileRepository,
        allowCreate: Boolean = true,
        allowEdit: Boolean = true,
        maxWriteChars: Int = 100_000,
        allowedWriteSubpath: String = "",
        enabledTools: Set<String> = ALL_TOOL_NAMES,
        maxReadChars: Int = 16_000,
        confirmLargeRead: suspend (path: String, sizeBytes: Long?, maxReadChars: Int) -> Boolean = { _, _, _ -> false }
    ): AgentToolRegistry {
        val tools = mutableListOf<AgentTool>(
            ListFilesTool(repository), SearchFilesTool(repository), SearchContentTool(repository),
            ReadFileTool(repository, maxReadChars, confirmLargeRead), GetFileInfoTool(repository)
        )
        if (allowCreate) tools += CreateFileTool(repository, maxWriteChars, allowedWriteSubpath)
        if (allowEdit) tools += EditFileTool(repository, maxWriteChars, allowedWriteSubpath)
        return AgentToolRegistry(tools.filter { it.definition.name in enabledTools })
    }

    private class ListFilesTool(private val files: SafFileRepository) : AgentTool {
        override val definition = ToolDefinition(
            name = "list_files",
            description = "List files and subdirectories in a path relative to the selected workspace.",
            parameters = schema("""{"type":"object","properties":{"path":{"type":"string","description":"Relative directory path; empty means workspace root."}},"required":["path"],"additionalProperties":false}""")
        )

        override suspend fun execute(arguments: JsonObject): String {
            val path = arg(arguments, "path")
            val entries = files.list(path)
            val visible = entries.take(200)
            val listing = visible.joinToString("\n") {
                "${if (it.isDirectory) "DIR " else "FILE"} ${it.relativePath}" +
                    (it.sizeBytes?.let { size -> " (${size} bytes)" } ?: "")
            }
            return when {
                entries.isEmpty() -> "Directory is empty."
                entries.size > visible.size -> listing + "\n[truncated: showing 200 of ${entries.size} entries]"
                else -> listing
            }
        }
    }

    private class SearchFilesTool(private val files: SafFileRepository) : AgentTool {
        override val definition = ToolDefinition(
            name = "search_files",
            description = "Find files by case-insensitive name substring within the selected workspace.",
            parameters = schema("""{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string","description":"Optional starting directory."},"max_results":{"type":"integer","minimum":1,"maximum":100}},"required":["query"],"additionalProperties":false}""")
        )

        override suspend fun execute(arguments: JsonObject): String {
            val query = arg(arguments, "query").trim()
            require(query.isNotEmpty()) { "query must not be empty." }
            val start = arg(arguments, "path")
            val max = intArg(arguments, "max_results", 50).coerceIn(1, 100)
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.add(start to 0)
            val matches = mutableListOf<String>()
            var visited = 0
            while (queue.isNotEmpty() && matches.size < max && visited < MAX_VISITED) {
                val (path, depth) = queue.removeFirst()
                val entries = files.list(path)
                visited += entries.size
                for (entry in entries) {
                    if (entry.name.contains(query, ignoreCase = true)) {
                        matches += "${if (entry.isDirectory) "DIR " else "FILE "}${entry.relativePath}"
                        if (matches.size >= max) break
                    }
                    if (entry.isDirectory && depth < MAX_DEPTH) queue.add(entry.relativePath to depth + 1)
                }
            }
            return matches.joinToString("\n").ifBlank { "No matching files found." }
        }
    }

    private class SearchContentTool(private val files: SafFileRepository) : AgentTool {
        override val definition = ToolDefinition(
            name = "search_content",
            description = "Search text in Markdown files and return matching lines with relative file paths.",
            parameters = schema("""{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string","description":"Optional starting directory."},"max_results":{"type":"integer","minimum":1,"maximum":100}},"required":["query"],"additionalProperties":false}""")
        )

        override suspend fun execute(arguments: JsonObject): String {
            val query = arg(arguments, "query")
            require(query.isNotBlank()) { "query must not be empty." }
            val start = arg(arguments, "path")
            val max = intArg(arguments, "max_results", 30).coerceIn(1, 100)
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.add(start to 0)
            val matches = mutableListOf<String>()
            var visited = 0
            while (queue.isNotEmpty() && matches.size < max && visited < MAX_VISITED) {
                val (path, depth) = queue.removeFirst()
                for (entry in files.list(path)) {
                    visited++
                    if (entry.isDirectory) {
                        if (depth < MAX_DEPTH) queue.add(entry.relativePath to depth + 1)
                    } else if (isMarkdown(entry.name) && (entry.sizeBytes == null || entry.sizeBytes <= MAX_FILE_BYTES)) {
                        val text = runCatching { files.readText(entry.relativePath) }.getOrNull() ?: continue
                        text.lineSequence().forEachIndexed { index, line ->
                            if (line.contains(query, ignoreCase = true) && matches.size < max) {
                                matches += "${entry.relativePath}:${index + 1}: ${line.take(MAX_LINE_CHARS)}"
                            }
                        }
                    }
                    if (matches.size >= max || visited >= MAX_VISITED) break
                }
            }
            return matches.joinToString("\n").ifBlank { "No matching content found in Markdown files." }
        }
    }

    private class ReadFileTool(
        private val files: SafFileRepository,
        private val maxReadChars: Int,
        private val confirmLargeRead: suspend (path: String, sizeBytes: Long?, maxReadChars: Int) -> Boolean
    ) : AgentTool {
        override val definition = ToolDefinition(
            name = "read_file",
            description = "Read a bounded section of a UTF-8 text or Markdown file. Large files require user approval. Results explicitly report complete/partial status and a zero-based start_offset for continuing. Use start_line/end_line for line ranges.",
            parameters = schema("""{"type":"object","properties":{"path":{"type":"string"},"start_offset":{"type":"integer","minimum":0,"description":"Zero-based character offset for continuing a partial read; takes precedence over line arguments."},"start_line":{"type":"integer","minimum":1},"end_line":{"type":"integer","minimum":1}},"required":["path"],"additionalProperties":false}""")
        )

        override suspend fun execute(arguments: JsonObject): String {
            val path = arg(arguments, "path")
            val hasExplicitOffset = arguments.containsKey("start_offset")
            val startLine = intArg(arguments, "start_line", 1).coerceAtLeast(1)
            val endLine = intArg(arguments, "end_line", Int.MAX_VALUE).coerceAtLeast(startLine)
            val hasLineRange = !hasExplicitOffset &&
                (arguments.containsKey("start_line") || arguments.containsKey("end_line"))
            val metadata = files.fileEntry(path)
            if (metadata?.isDirectory == true) {
                throw IllegalArgumentException("Cannot read a directory as a file: $path")
            }

            val knownOversized = metadata?.sizeBytes?.let { it > maxReadChars.toLong() } == true
            var approved = false
            if (knownOversized) {
                approved = confirmLargeRead(path, metadata?.sizeBytes, maxReadChars)
                if (!approved) {
                    throw AgentLoopUserCancelledException(
                        "The user cancelled reading '$path' after the application reported size_bytes=${metadata?.sizeBytes}, above the $maxReadChars-character read limit."
                    )
                }
            }

            val startOffset = if (hasExplicitOffset) {
                intArg(arguments, "start_offset", 0).coerceAtLeast(0)
            } else if (startLine > 1) {
                files.lineStartOffset(path, startLine) ?: return
                    "[FILE_READ status=complete path=\"$path\" requested_start_line=$startLine has_more=false]\nNo content exists at the requested start line."
            } else {
                0
            }

            val chunk = files.readTextChunk(path, startOffset = startOffset, maxChars = maxReadChars)
            if (chunk.hasMore && !approved) {
                approved = confirmLargeRead(path, chunk.sizeBytes, maxReadChars)
                if (!approved) {
                    throw AgentLoopUserCancelledException(
                        "The user cancelled reading '$path' after the application detected more than $maxReadChars characters."
                    )
                }
            }

            val requestedLineCount = if (endLine == Int.MAX_VALUE) {
                Int.MAX_VALUE
            } else {
                (endLine - startLine + 1).coerceAtLeast(1)
            }
            val selectedLines = if (hasLineRange) {
                chunk.content.lineSequence().take(requestedLineCount).toList()
            } else {
                emptyList()
            }
            val selected = if (hasLineRange) selectedLines.joinToString("\n") else chunk.content
            val lineRangeComplete = hasLineRange &&
                endLine != Int.MAX_VALUE &&
                selectedLines.size >= requestedLineCount &&
                (!chunk.hasMore || countLineBreaks(chunk.content) >= requestedLineCount)
            val hasMore = chunk.hasMore && !lineRangeComplete
            val status = if (hasMore) "partial" else "complete"
            val size = chunk.sizeBytes?.toString() ?: "unknown"
            val nextOffset = if (hasMore) chunk.nextOffset.toString() else "none"
            return buildString {
                appendLine("[FILE_READ status=$status path=\"$path\" size_bytes=$size start_offset=${chunk.startOffset} returned_chars=${selected.length} next_offset=$nextOffset has_more=$hasMore range_complete=$lineRangeComplete]")
                appendLine("[CONTENT]")
                appendLine(selected)
                append("[/CONTENT]")
                if (hasMore) {
                    appendLine()
                    append("[CONTINUE with read_file(path=\"$path\", start_offset=${chunk.nextOffset}) to read the next section. This result is partial.]")
                } else {
                    appendLine()
                    append("[FILE_READ_COMPLETE]")
                }
            }
        }
    }

    private class GetFileInfoTool(private val files: SafFileRepository) : AgentTool {
        override val definition = ToolDefinition(
            name = "get_file_info",
            description = "Get basic metadata for a file or directory in the selected workspace.",
            parameters = schema("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}""")
        )

        override suspend fun execute(arguments: JsonObject): String {
            val path = arg(arguments, "path")
            if (path.isBlank()) return "Path is the workspace root directory."
            val parent = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val entry = files.list(parent).firstOrNull { it.name == name }
                ?: return "Path not found: $path"
            return buildString {
                appendLine("path: ${entry.relativePath}")
                appendLine("type: ${if (entry.isDirectory) "directory" else "file"}")
                entry.mimeType?.let { appendLine("mime_type: $it") }
                entry.sizeBytes?.let { appendLine("size_bytes: $it") }
            }.trim()
        }
    }

    private class CreateFileTool(private val files: SafFileRepository, private val maxWriteChars: Int, private val allowedWriteSubpath: String) : AgentTool {
        override val definition = ToolDefinition(
            name = "create_file",
            description = "Create a new Markdown file. Refuses to overwrite an existing path.",
            parameters = schema("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"],"additionalProperties":false}""")
        )

        override suspend fun execute(arguments: JsonObject): String {
            val path = arg(arguments, "path")
            val content = arg(arguments, "content")
            require(isAllowedWritePath(path, allowedWriteSubpath)) { "Write denied: path is outside the configured allowed subfolder." }
            require(content.length <= maxWriteChars) { "Content exceeds the configured $maxWriteChars character write limit." }
            require(path.endsWith(".md", ignoreCase = true) || path.endsWith(".markdown", ignoreCase = true)) {
                "Only Markdown files can be created by this tool."
            }
            require(!files.exists(path)) { "File already exists; refusing to overwrite it." }
            files.writeText(path, content)
            val saved = files.readText(path)
            check(saved == content) { "Verification failed: saved content differs from requested content." }
            return "Created and verified $path (${saved.length} characters)."
        }
    }

    private class EditFileTool(private val files: SafFileRepository, private val maxWriteChars: Int, private val allowedWriteSubpath: String) : AgentTool {
        override val definition = ToolDefinition(
            name = "edit_file",
            description = "Replace one exact text occurrence in a Markdown file. Stops if the target is missing or ambiguous.",
            parameters = schema("""{"type":"object","properties":{"path":{"type":"string"},"target":{"type":"string"},"replacement":{"type":"string"}},"required":["path","target","replacement"],"additionalProperties":false}""")
        )

        override suspend fun execute(arguments: JsonObject): String {
            val path = arg(arguments, "path")
            val target = arg(arguments, "target")
            val replacement = arg(arguments, "replacement")
            require(isAllowedWritePath(path, allowedWriteSubpath)) { "Write denied: path is outside the configured allowed subfolder." }
            require(path.endsWith(".md", ignoreCase = true) || path.endsWith(".markdown", ignoreCase = true)) {
                "Only Markdown files can be edited by this tool."
            }
            require(target.isNotEmpty()) { "target must not be empty." }
            val original = files.readText(path)
            val occurrences = original.windowed(target.length).count { it == target }
            require(occurrences == 1) {
                if (occurrences == 0) "Target text was not found; no changes were made."
                else "Target text occurs $occurrences times; edit is ambiguous and was not applied."
            }
            val changed = original.replaceFirst(target, replacement)
            require(changed.length <= maxWriteChars) { "Edited content exceeds the configured $maxWriteChars character write limit." }
            try {
                files.writeText(path, changed)
                val saved = files.readText(path)
                check(saved == changed) { "Verification failed: saved content differs from requested content." }
            } catch (error: Exception) {
                val restored = runCatching {
                    files.writeText(path, original)
                    files.readText(path) == original
                }.getOrDefault(false)
                val recovery = if (restored) " Original content was restored." else
                    " Automatic restoration could not be confirmed; inspect the file before retrying."
                throw IllegalStateException(
                    "Edit failed for $path. $recovery",
                    error
                )
            }
            return "Edited and verified $path."
        }
    }

    val ALL_TOOL_NAMES = setOf("list_files", "search_files", "search_content", "read_file", "get_file_info", "create_file", "edit_file")

    private fun countLineBreaks(content: String): Int {
        var count = 0
        var index = 0
        while (index < content.length) {
            when (content[index]) {
                '\r' -> {
                    count++
                    if (index + 1 < content.length && content[index + 1] == '\n') index++
                }
                '\n' -> count++
            }
            index++
        }
        return count
    }

    private const val MAX_DEPTH = 8
    private const val MAX_VISITED = 2_000
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_LINE_CHARS = 500
    private const val MAX_READ_CHARS = 30_000

    private fun schema(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun arg(args: JsonObject, name: String): String =
        args[name]?.jsonPrimitive?.contentOrNull ?: ""
    private fun intArg(args: JsonObject, name: String, default: Int): Int =
        args[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: default
    private fun isAllowedWritePath(path: String, allowedSubpath: String): Boolean {
        val normalizedPath = path.trim().replace(92.toChar(), '/').trim('/')
        val pathSegments = normalizedPath.split('/')
        if (normalizedPath.isBlank() || pathSegments.any { it.isBlank() || it == "." || it == ".." } || normalizedPath.contains(":")) return false
        val scope = allowedSubpath.trim().replace(92.toChar(), '/').trim('/')
        if (scope.isBlank()) return true
        val scopeSegments = scope.split('/')
        if (scopeSegments.any { it.isBlank() || it == "." || it == ".." } || scope.contains(":")) return false
        return normalizedPath.startsWith("$scope/")
    }
    private fun isMarkdown(name: String): Boolean =
        name.endsWith(".md", true) || name.endsWith(".markdown", true)
}
