package com.miniichat.data

import com.miniichat.api.ToolDefinition

/** One tool as the Tools card describes it: the internal id, and the name and description the model sees. */
data class ToolExposureSpec(val uid: String, val name: String, val description: String)

class ToolsCardException(message: String) : Exception(message)

/**
 * The text format of the Tools card:
 *
 *   ## read_file
 *   name: read_file
 *   description: Read a section of a file.
 *     More lines of the description can follow.
 *
 * The word after "##" is the internal tool id (never sent to the model).
 */
object ToolsCardFormat {
    private val namePattern = Regex("^[A-Za-z0-9_-]+$")

    fun parse(text: String, knownUids: Set<String>): List<ToolExposureSpec> {
        class Draft(val uid: String, val line: Int) {
            var name: String? = null
            var description: StringBuilder? = null
        }

        val drafts = mutableListOf<Draft>()
        var inDescription = false
        text.replace("\r\n", "\n").split("\n").forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = rawLine.trimEnd()
            val current = drafts.lastOrNull()
            when {
                line.startsWith("## ") -> {
                    drafts += Draft(line.removePrefix("## ").trim(), lineNo)
                    inDescription = false
                }
                current == null -> {
                    if (line.isNotBlank()) {
                        throw ToolsCardException("Line $lineNo: text before the first \"## tool_id\" line.")
                    }
                }
                line.startsWith("name:") -> {
                    current.name = line.removePrefix("name:").trim()
                    inDescription = false
                }
                line.startsWith("description:") -> {
                    current.description = StringBuilder(line.removePrefix("description:").trim())
                    inDescription = true
                }
                inDescription -> current.description?.append("\n")?.append(line.trim())
                line.isBlank() -> Unit
                else -> throw ToolsCardException("Line $lineNo: unexpected text \"${line.take(40)}\".")
            }
        }

        if (drafts.isEmpty()) throw ToolsCardException("The Tools card lists no tools.")
        val seenUids = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        return drafts.map { draft ->
            if (draft.uid !in knownUids) {
                throw ToolsCardException(
                    "Line ${draft.line}: \"${draft.uid}\" is not a tool of this app. Known tools: ${knownUids.sorted().joinToString(", ")}."
                )
            }
            if (!seenUids.add(draft.uid)) {
                throw ToolsCardException("Line ${draft.line}: the tool \"${draft.uid}\" is listed twice.")
            }
            val name = draft.name.orEmpty()
            if (name.isBlank()) throw ToolsCardException("Tool \"${draft.uid}\" has no name.")
            if (!namePattern.matches(name)) {
                throw ToolsCardException("Tool \"${draft.uid}\": the name may only use letters, digits, _ and -.")
            }
            if (!seenNames.add(name)) throw ToolsCardException("Two tools use the name \"$name\".")
            val description = draft.description?.toString()?.trim().orEmpty()
            if (description.isBlank()) throw ToolsCardException("Tool \"${draft.uid}\" has no description.")
            ToolExposureSpec(draft.uid, name, description)
        }
    }

    /** A ready-to-use Tools card text built from the real tool definitions. */
    fun template(definitions: List<ToolDefinition>): String =
        definitions.joinToString("\n\n") { definition ->
            "## ${definition.name}\nname: ${definition.name}\ndescription: ${definition.description}"
        } + "\n"
}
