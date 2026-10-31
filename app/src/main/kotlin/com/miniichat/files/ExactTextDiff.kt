package com.miniichat.files

/**
 * Small bounded line diff for human review of exact-match Markdown edits.
 * It is a preview only; the write path still validates the original content independently.
 */
object ExactTextDiff {
    private const val MAX_LINES = 200
    private const val MAX_CELLS = 40_000

    fun render(
        before: String,
        after: String,
        maxOutputChars: Int = 6_000
    ): String {
        require(maxOutputChars > 0) { "Diff output limit must be positive." }
        if (before == after) return "(No changes.)"

        val oldLines = before.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val newLines = after.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if (oldLines.size > MAX_LINES || newLines.size > MAX_LINES ||
            (oldLines.size + 1) * (newLines.size + 1) > MAX_CELLS
        ) {
            return buildString {
                appendLine("--- original: ${oldLines.size} lines, ${before.length} characters")
                appendLine("+++ proposed: ${newLines.size} lines, ${after.length} characters")
                appendLine("[Detailed diff omitted because this edit exceeds the safe preview budget.]")
                appendLine()
                appendLine("Original preview:")
                appendLine(before.take(maxOutputChars / 4))
                appendLine()
                appendLine("Proposed preview:")
                append(after.take(maxOutputChars / 4))
            }.take(maxOutputChars)
        }

        val columns = newLines.size + 1
        val lcs = IntArray((oldLines.size + 1) * columns)
        for (i in oldLines.indices.reversed()) {
            for (j in newLines.indices.reversed()) {
                val cell = i * columns + j
                lcs[cell] = if (oldLines[i] == newLines[j]) {
                    1 + lcs[(i + 1) * columns + j + 1]
                } else {
                    maxOf(lcs[(i + 1) * columns + j], lcs[i * columns + j + 1])
                }
            }
        }

        val output = StringBuilder()
        output.appendLine("--- original")
        output.appendLine("+++ proposed")
        output.appendLine("@@")
        var i = 0
        var j = 0
        while (i < oldLines.size || j < newLines.size) {
            when {
                i < oldLines.size && j < newLines.size && oldLines[i] == newLines[j] -> {
                    output.append(' ').appendLine(oldLines[i])
                    i++
                    j++
                }
                j < newLines.size &&
                    (i == oldLines.size || lcs[i * columns + j + 1] >= lcs[(i + 1) * columns + j]) -> {
                    output.append('+').appendLine(newLines[j])
                    j++
                }
                i < oldLines.size -> {
                    output.append('-').appendLine(oldLines[i])
                    i++
                }
            }
            if (output.length > maxOutputChars) {
                output.setLength(maxOutputChars.coerceAtLeast(0))
                output.append("\n[Diff preview truncated.]")
                break
            }
        }
        return output.toString().trimEnd()
    }
}
