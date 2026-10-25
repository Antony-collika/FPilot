package com.miniichat.data

class MemoryFormatException(message: String) : Exception(message)

/**
 * The Memory file: a free introduction followed by exactly six "##" sections.
 * The model updates it with a patch block at the end of an answer.
 */
class MemoryDoc(val preamble: String, val sections: Map<String, List<String>>) {

    fun render(): String {
        val sb = StringBuilder()
        if (preamble.isNotBlank()) sb.append(preamble.trimEnd()).append("\n\n")
        for (heading in HEADINGS) {
            sb.append("## ").append(heading).append("\n")
            for (line in sections[heading].orEmpty()) sb.append(line).append("\n")
            sb.append("\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    /** Applies a patch body. Throws [MemoryFormatException] and changes nothing if any line is wrong. */
    fun applyPatch(patchBody: String): MemoryDoc {
        val work = HEADINGS.associateWith { sections[it].orEmpty().toMutableList() }
        var current: String? = null
        for (raw in patchBody.replace("\r\n", "\n").split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("## ")) {
                val name = line.removePrefix("## ").trim()
                if (name !in HEADINGS) {
                    throw MemoryFormatException("Patch: unknown section \"$name\".")
                }
                current = name
            } else if (line.startsWith("+") || line.startsWith("-")) {
                val target = current ?: throw MemoryFormatException("Patch: the line \"${line.take(40)}\" is outside any section.")
                val item = line.drop(1).removePrefix(" ").trim()
                if (item.isEmpty()) continue
                val list = work.getValue(target)
                if (line.startsWith("+")) {
                    list.add(item)
                } else {
                    val index = list.indexOfFirst { it.trim() == item }
                    if (index < 0) {
                        throw MemoryFormatException("Patch: cannot remove from $target, the line was not found: \"${item.take(60)}\".")
                    }
                    list.removeAt(index)
                }
            }
            // Any other line is "unchanged" and is ignored.
        }
        return MemoryDoc(preamble, work)
    }

    companion object {
        val HEADINGS = listOf("Goal", "Constraints", "Context", "Assumptions", "Decisions", "File-content")

        fun parse(text: String): MemoryDoc {
            if (text.isBlank()) throw MemoryFormatException("The file is empty.")
            val preamble = StringBuilder()
            val sections = linkedMapOf<String, MutableList<String>>()
            var current: MutableList<String>? = null
            for (line in text.replace("\r\n", "\n").split("\n")) {
                if (line.startsWith("## ")) {
                    val name = line.removePrefix("## ").trim()
                    if (name !in HEADINGS) {
                        throw MemoryFormatException(
                            "unknown section \"## $name\". The sections must be: ${HEADINGS.joinToString(", ")}."
                        )
                    }
                    if (sections.containsKey(name)) throw MemoryFormatException("the section \"## $name\" appears twice.")
                    current = mutableListOf()
                    sections[name] = current
                } else if (current == null) {
                    preamble.append(line).append("\n")
                } else if (line.isNotBlank()) {
                    current.add(line.trimEnd())
                }
            }
            val missing = HEADINGS.filter { it !in sections }
            if (missing.isNotEmpty()) throw MemoryFormatException("missing sections: ${missing.joinToString(", ")}.")
            return MemoryDoc(preamble.toString().trim(), sections)
        }

        /** The text of a new Memory file. */
        fun template(): String = """
This file is the long-term memory of the conversation. It is sent to you with every request. Keep it short and accurate.

To update it, write a block at the very end of your final answer of a turn, only when something changed:

    [MEMORY_PATCH]
    ## Decisions
    + a new line to add
    - an existing line to remove, copied exactly
    [/MEMORY_PATCH]

List only the sections that change. Use "+" to add a line and "-" to remove a line. Write nothing else inside the block.
Sections: Goal, Constraints, Context, Assumptions, Decisions, File-content.
File-content: one line per file you read, with the path, one or two sentences of summary, a very short quote if needed, and the turn.
If a user message starts with a turn tag such as [T7], write that tag in parentheses, for example (T7), at the end of the lines you add to Decisions, Constraints and File-content.

## Goal

## Constraints

## Context

## Assumptions

## Decisions

## File-content
""".trimIndent() + "\n"
    }
}

/** Finds and removes the patch block in a model answer. */
object MemoryPatch {
    const val START = "[MEMORY_PATCH]"
    const val END = "[/MEMORY_PATCH]"

    /** [visible] is the answer without the block. [patch] is the text inside the block, or null. */
    data class Split(val visible: String, val patch: String?, val incomplete: Boolean)

    fun split(answer: String): Split {
        val start = answer.indexOf(START)
        if (start < 0) return Split(answer, null, false)
        val end = answer.indexOf(END, start + START.length)
        if (end < 0) return Split(answer.substring(0, start).trimEnd(), null, true)
        val body = answer.substring(start + START.length, end).trim('\n', '\r')
        val visible = (answer.substring(0, start) + answer.substring(end + END.length)).trim()
        return Split(visible, body, false)
    }

    /** What to show while the answer is still arriving: everything except the block. */
    fun hideForDisplay(text: String): String {
        val start = text.indexOf(START)
        if (start < 0) return text
        val end = text.indexOf(END, start + START.length)
        return if (end < 0) {
            text.substring(0, start).trimEnd()
        } else {
            (text.substring(0, start) + text.substring(end + END.length)).trim()
        }
    }
}
