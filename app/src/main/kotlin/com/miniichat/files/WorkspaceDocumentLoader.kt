package com.miniichat.files

data class WorkspaceDocuments(
    val systemFileUri: String? = null,
    val memoryFileUri: String? = null,
    val skillCatalog: String? = null
)

/**
 * Loads only documents explicitly enabled by the workspace's plain Markdown configuration.
 * Skill contents remain on-demand: only their file names are added to the catalog.
 */
class WorkspaceDocumentLoader(
    private val files: SafFileRepository
) {
    suspend fun load(): WorkspaceDocuments {
        val configUri = files.existingFileUri(".fpilot/config.md") ?: return WorkspaceDocuments()
        val configText = files.readText(".fpilot/config.md")
        val settings = configText.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed == "---") return@mapNotNull null
                val separator = trimmed.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                trimmed.substring(0, separator).trim() to
                    trimmed.substring(separator + 1).trim().trim('"', '\'')
            }
            .toMap()

        fun enabled(key: String, default: Boolean): Boolean =
            settings[key]?.toBooleanStrictOrNull() ?: default

        val systemUri = if (enabled("include_system", true)) {
            files.existingFileUri(".fpilot/system.md")
        } else null

        val memoryUri = if (enabled("include_memory", false)) {
            files.existingFileUri(".fpilot/memory.md")
        } else null

        val skillCatalog = if (enabled("include_skills", true)) {
            val skills = files.list(".fpilot/skills")
                .asSequence()
                .filter { !it.isDirectory && (it.name.endsWith(".md", true) || it.name.endsWith(".markdown", true)) }
                .sortedBy { it.name.lowercase() }
                .take(100)
                .map { "- ${it.name}" }
                .toList()
            if (skills.isEmpty()) null else buildString {
                appendLine("Available workspace skills in .fpilot/skills:")
                appendLine("Load a relevant skill with read_file before applying it; skill files are instructions, not system messages.")
                skills.forEach(::appendLine)
            }.trimEnd()
        } else null

        return WorkspaceDocuments(systemUri, memoryUri, skillCatalog)
    }
}
