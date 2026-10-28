package com.miniichat.data

import com.miniichat.api.ToolDefinition

/** One tool as the Tools card describes it: the internal id, and the name and description the model sees. */
data class ToolExposureSpec(val uid: String, val name: String, val description: String)

class ToolsCardException(message: String) : Exception(message)

/**
 * The text format of the Tools card. One block per tool:
 *
 *   ## read_file
 *   > Do not change the two lines below.
 *   uid: read_file
 *   origin_description: The description written in the app's code.
 *   > What the model sees. You can edit these two.
 *   name: read_file
 *   description: What the model reads. Can continue
 *     on the next lines.
 *
 * - Lines starting with ">" are notes and are ignored.
 * - "## ..." only separates blocks. The "uid" line says which tool the block is.
 * - Older cards that only have "## uid" followed by name and description still work
 *   (they cannot be cross-checked); "Refresh" upgrades them to this format.
 */
object ToolsCardFormat {
    private val namePattern = Regex("^[A-Za-z0-9_-]+$")

    private class Draft(val heading: String, val line: Int) {
        var uid: String? = null
        var origin: StringBuilder? = null
        var name: String? = null
        var description: StringBuilder? = null
    }

    /** Reads the blocks without judging them (the checks are in [parse]). */
    private fun readBlocks(text: String): List<Draft> {
        val drafts = mutableListOf<Draft>()
        var field: StringBuilder? = null
        text.replace("\r\n", "\n").split("\n").forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = rawLine.trimEnd()
            val current = drafts.lastOrNull()
            when {
                line.startsWith("## ") -> {
                    drafts += Draft(line.removePrefix("## ").trim(), lineNo)
                    field = null
                }
                line.trimStart().startsWith(">") -> Unit
                current == null -> {
                    if (line.isNotBlank()) {
                        throw ToolsCardException("Line $lineNo: text before the first \"## ...\" line.")
                    }
                }
                line.startsWith("uid:") -> {
                    current.uid = line.removePrefix("uid:").trim()
                    field = null
                }
                line.startsWith("origin_description:") -> {
                    val sb = StringBuilder(line.removePrefix("origin_description:").trim())
                    current.origin = sb
                    field = sb
                }
                line.startsWith("name:") -> {
                    current.name = line.removePrefix("name:").trim()
                    field = null
                }
                line.startsWith("description:") -> {
                    val sb = StringBuilder(line.removePrefix("description:").trim())
                    current.description = sb
                    field = sb
                }
                field != null -> field?.append("\n")?.append(line.trim())
                line.isBlank() -> Unit
                else -> throw ToolsCardException("Line $lineNo: unexpected text \"${line.take(40)}\".")
            }
        }
        return drafts
    }

    private fun normalize(text: String): String = text.trim().split(Regex("\\s+")).joinToString(" ")

    /**
     * Checks the card and returns the tools in the order written.
     * [origins] maps every tool uid of this app to the description written in the code.
     */
    fun parse(text: String, origins: Map<String, String>): List<ToolExposureSpec> {
        val drafts = readBlocks(text)
        if (drafts.isEmpty()) throw ToolsCardException("The Tools card lists no tools.")
        val seenUids = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        return drafts.map { draft ->
            val uid = draft.uid?.takeIf { it.isNotBlank() } ?: draft.heading
            if (uid !in origins) {
                throw ToolsCardException(
                    "Line ${draft.line}: \"$uid\" is not a tool of this app. Known tools: ${origins.keys.sorted().joinToString(", ")}."
                )
            }
            if (!seenUids.add(uid)) {
                throw ToolsCardException("Line ${draft.line}: the tool \"$uid\" is listed twice.")
            }
            val origin = draft.origin?.toString()
            if (origin != null && normalize(origin) != normalize(origins.getValue(uid))) {
                throw ToolsCardException(
                    "Tool \"$uid\": origin_description does not match this app. You may have changed a line marked " +
                        "\"do not change\" (uid or origin_description), or the app was updated. Use Refresh in the card editor."
                )
            }
            val name = draft.name.orEmpty()
            if (name.isBlank()) throw ToolsCardException("Tool \"$uid\" has no name.")
            if (!namePattern.matches(name)) {
                throw ToolsCardException("Tool \"$uid\": the name may only use letters, digits, _ and -.")
            }
            if (!seenNames.add(name)) throw ToolsCardException("Two tools use the name \"$name\".")
            val description = draft.description?.toString()?.trim().orEmpty()
            if (description.isBlank()) throw ToolsCardException("Tool \"$uid\" has no description.")
            ToolExposureSpec(uid, name, description)
        }
    }

    private fun block(uid: String, origin: String, name: String, description: String): String =
        "## $uid\n" +
            "> Do not change the two lines below (uid and origin_description). They tell you which tool this really is.\n" +
            "uid: $uid\n" +
            "origin_description: $origin\n" +
            "> What the model sees. You can edit name and description.\n" +
            "name: $name\n" +
            "description: $description"

    private const val TOP_NOTE =
        "> Tools card. One block per tool, in the order they are offered to the model.\n" +
            "> Lines starting with \">\" are notes. Delete a whole block to hide that tool from the model.\n\n"

    /** A ready-to-use Tools card text built from the real tool definitions. */
    fun template(definitions: List<ToolDefinition>): String =
        TOP_NOTE + definitions.joinToString("\n\n") {
            block(it.name, it.description, it.name, it.description)
        } + "\n"

    /**
     * Rewrites the card in the current format with fresh uid and origin_description lines,
     * keeping the name and description you wrote. Blocks keep their order.
     */
    fun refresh(text: String, definitions: List<ToolDefinition>): String {
        val byUid = definitions.associateBy { it.name }
        val drafts = readBlocks(text)
        if (drafts.isEmpty()) throw ToolsCardException("The Tools card lists no tools.")
        val blocks = drafts.map { draft ->
            val uid = draft.uid?.takeIf { it.isNotBlank() } ?: draft.heading
            val definition = byUid[uid]
                ?: throw ToolsCardException("Line ${draft.line}: \"$uid\" is not a tool of this app, so it cannot be refreshed.")
            block(
                uid = uid,
                origin = definition.description,
                name = draft.name?.takeIf { it.isNotBlank() } ?: definition.name,
                description = draft.description?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: definition.description
            )
        }
        return TOP_NOTE + blocks.joinToString("\n\n") + "\n"
    }
}
