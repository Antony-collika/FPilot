package com.miniichat.files

import com.miniichat.agent.AgentTool
import com.miniichat.agent.AgentToolRegistry
import com.miniichat.api.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Initial filesystem capabilities exposed to the agent. All paths are relative
 * to the SAF tree URI held by SafFileRepository.
 */
object MarkdownFileTools {
    fun registry(repository: SafFileRepository, allowWrites: Boolean = false): AgentToolRegistry = AgentToolRegistry(
        listOf(
            ListFilesTool(repository),
            SearchFilesTool(repository),
            SearchContentTool(repository),
            ReadFileTool(repository),
            GetFileInfoTool(repository)
        ) + if (allowWrites) listOf(CreateFileTool(repository), EditFileTool(repository)) else emptyList()
    )

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

    private class ReadFileTool(private val files: SafFileRepository) : AgentTool {
        override val definition = ToolDefinition(
            name = "read_file",
            description = "Read a UTF-8 text or Markdown file. Large results are truncated; use start_line and end_line for a section.",
            parameters = schema("""{"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer","minimum":1},"end_line":{"type":"integer","minimum":1}},"required":["path"],"additionalProperties":false}""")
        )

        override suspend fun execute(arguments: JsonObject): String {
            val path = arg(arguments, "path")
            val content = files.readText(path)
            val start = intArg(arguments, "start_line", 1).coerceAtLeast(1)
            val end = intArg(arguments, "end_line", Int.MAX_VALUE).coerceAtLeast(start)
            val selected = content.lineSequence().drop(start - 1).take((end - start + 1).coerceAtMost(2000)).toList()
            val joined = selected.joinToString("\n")
            return if (joined.length <= MAX_READ_CHARS) joined else joined.take(MAX_READ_CHARS) + "\n[truncated]"
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

    private class CreateFileTool(private val files: SafFileRepository) : AgentTool {
        override val definition = ToolDefinition(
            name = "create_file",
            description = "Create a new Markdown file. Refuses to overwrite an existing path.",
            parameters = schema("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"],"additionalProperties":false}""")
        )

        override suspend fun execute(arguments: JsonObject): String {
            val path = arg(arguments, "path")
            val content = arg(arguments, "content")
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

    private class EditFileTool(private val files: SafFileRepository) : AgentTool {
        override val definition = ToolDefinition(
            name = "edit_file",
            description = "Replace one exact text occurrence in a Markdown file. Stops if the target is missing or ambiguous.",
            parameters = schema("""{"type":"object","properties":{"path":{"type":"string"},"target":{"type":"string"},"replacement":{"type":"string"}},"required":["path","target","replacement"],"additionalProperties":false}""")
        )

        override suspend fun execute(arguments: JsonObject): String {
            val path = arg(arguments, "path")
            val target = arg(arguments, "target")
            val replacement = arg(arguments, "replacement")
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

    companion object {
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
        private fun isMarkdown(name: String): Boolean =
            name.endsWith(".md", true) || name.endsWith(".markdown", true)
    }
}
