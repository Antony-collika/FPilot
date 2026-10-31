package com.miniichat.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePathTest {
    @Test
    fun normalizesWindowsSeparatorsWithoutLeavingWorkspaceRoot() {
        assertEquals("notes/plan.md", WorkspacePath.normalize("notes\\plan.md"))
        assertEquals("notes/plan.md", WorkspacePath.normalize("  notes/plan.md  "))
        assertEquals("", WorkspacePath.normalize(""))
    }

    @Test
    fun rejectsAbsoluteTraversalAndUriLikePaths() {
        val invalid = listOf(
            "/etc/passwd",
            "../outside.md",
            "notes/../../outside.md",
            "notes/./plan.md",
            "notes//plan.md",
            "C:\\private\\file.md",
            "content://provider/document/1"
        )
        invalid.forEach { path ->
            val result = runCatching { WorkspacePath.normalize(path) }
            assertTrue("Expected unsafe path to be rejected: $path", result.isFailure)
        }
    }
}
