package com.miniichat.files

data class WorkspaceConfig(
    val includeSystem: Boolean = true,
    val includeMemory: Boolean = false,
    val includeSkills: Boolean = true,
    val includeWorkspaceTools: Boolean = true,
    val saveRequestSnapshots: Boolean = false
)

/** Parser for the small, versioned key/value section in .fpilot/config.md. */
internal object WorkspaceConfigParser {
    private val supportedKeys = setOf(
        "schema_version",
        "include_system",
        "include_memory",
        "include_skills",
        "include_workspace_tools",
        "save_request_snapshots"
    )

    fun parse(text: String): WorkspaceConfig {
        val values = linkedMapOf<String, String>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("-") || line == "---") {
                return@forEachIndexed
            }
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEachIndexed
            val key = line.substring(0, separator).trim()
            if (key !in supportedKeys) return@forEachIndexed
            require(!values.containsKey(key)) {
                ".fpilot/config.md:${index + 1}: duplicate setting '$key'."
            }
            values[key] = line.substring(separator + 1).trim().trim('"', '\'')
        }

        val version = values["schema_version"]?.toIntOrNull() ?: 1
        require(version == 1) { ".fpilot/config.md: unsupported schema_version '$version'." }

        fun flag(key: String, default: Boolean): Boolean =
            values[key]?.toBooleanStrictOrNull()
                ?: if (values.containsKey(key)) {
                    throw IllegalArgumentException(".fpilot/config.md: setting '$key' must be true or false.")
                } else default

        return WorkspaceConfig(
            includeSystem = flag("include_system", true),
            includeMemory = flag("include_memory", false),
            includeSkills = flag("include_skills", true),
            includeWorkspaceTools = flag("include_workspace_tools", true),
            saveRequestSnapshots = flag("save_request_snapshots", false)
        )
    }
}

data class WorkspaceDocuments(
    val systemFileUri: String? = null,
    val memoryFileUri: String? = null,
    val toolsFileUri: String? = null,
    val skillCatalog: String? = null,
    val saveRequestSnapshots: Boolean = false
)

/**
 * Loads only documents explicitly enabled by the workspace's plain Markdown configuration.
 * Skill contents remain on-demand: only their file names are added to the catalog.
 */
class WorkspaceDocumentLoader(
    private val files: SafFileRepository
) {
    suspend fun load(): WorkspaceDocuments {
        if (files.existingFileUri(".fpilot/config.md") == null) return WorkspaceDocuments()
        val config = WorkspaceConfigParser.parse(files.readText(".fpilot/config.md"))

        val systemUri = if (config.includeSystem) {
            files.existingFileUri(".fpilot/system.md")
        } else null

        val memoryUri = if (config.includeMemory) {
            files.existingFileUri(".fpilot/memory.md")
        } else null

        val toolsUri = if (config.includeWorkspaceTools) {
            files.existingFileUri(".fpilot/tools/workspace.md")
        } else null

        val skillCatalog = if (config.includeSkills) {
            val entries = try {
                files.list(".fpilot/skills")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            val skills = entries.asSequence()
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

        return WorkspaceDocuments(systemUri, memoryUri, toolsUri, skillCatalog, config.saveRequestSnapshots)
    }
}
