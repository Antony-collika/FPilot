package com.miniichat.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWriteDefaultsTest {
    @Test
    fun modelDefaultsKeepAgentWritesDisabledUntilUserEnablesThem() {
        val settings = AppSettings()

        assertFalse(settings.allowWorkspaceCreate)
        assertFalse(settings.allowWorkspaceEdit)
        assertFalse(settings.toolCreateFileEnabled)
        assertFalse(settings.toolEditFileEnabled)
        assertTrue(settings.confirmWorkspaceWrites)
    }
}
