package com.miniichat.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceConfigParserTest {
    @Test
    fun defaultsAreLeastSurpriseAndDoNotPersistRequestSnapshots() {
        val config = WorkspaceConfigParser.parse(
            """
            ---
            schema_version: 1
            ---
            # Workspace
            """.trimIndent()
        )

        assertTrue(config.includeSystem)
        assertFalse(config.includeMemory)
        assertTrue(config.includeSkills)
        assertTrue(config.includeWorkspaceTools)
        assertFalse(config.saveRequestSnapshots)
    }

    @Test
    fun parsesExplicitDocumentAndPrivacySwitches() {
        val config = WorkspaceConfigParser.parse(
            """
            ---
            schema_version: 1
            ---
            include_system: false
            include_memory: true
            include_skills: false
            include_workspace_tools: false
            save_request_snapshots: true
            """.trimIndent()
        )

        assertFalse(config.includeSystem)
        assertTrue(config.includeMemory)
        assertFalse(config.includeSkills)
        assertFalse(config.includeWorkspaceTools)
        assertTrue(config.saveRequestSnapshots)
    }

    @Test
    fun rejectsDuplicateAndInvalidBooleanSettings() {
        val duplicate = runCatching {
            WorkspaceConfigParser.parse(
                """
                include_memory: false
                include_memory: true
                """.trimIndent()
            )
        }.exceptionOrNull()
        assertTrue(duplicate is IllegalArgumentException)
        assertTrue(duplicate!!.message!!.contains("duplicate setting"))

        val invalid = runCatching {
            WorkspaceConfigParser.parse("include_memory: sometimes")
        }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
        assertTrue(invalid!!.message!!.contains("must be true or false"))
    }

    @Test
    fun rejectsUnsupportedSchemaVersionsAndIgnoresUnknownKeys() {
        val unsupported = runCatching {
            WorkspaceConfigParser.parse("schema_version: 9")
        }.exceptionOrNull()
        assertTrue(unsupported is IllegalArgumentException)

        val config = WorkspaceConfigParser.parse(
            """
            schema_version: 1
            future_setting: ignored
            include_memory: true
            """.trimIndent()
        )
        assertEquals(true, config.includeMemory)
    }
}
