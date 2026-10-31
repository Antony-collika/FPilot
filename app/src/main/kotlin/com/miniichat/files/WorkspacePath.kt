package com.miniichat.files

/**
 * Pure validation for paths relative to the SAF tree. This deliberately does not canonicalize
 * dot segments: ambiguous or escaping paths are rejected rather than silently rewritten.
 */
internal object WorkspacePath {
    fun normalize(path: String): String {
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
