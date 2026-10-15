package com.miniichat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val activeProviderId: String = "",
    val activeModel: String = "",
    val activeAssistantId: String = "default",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f,
    val stream: Boolean = true,
    val language: String = "system",          // system | en | zh
    val dynamicColor: Boolean = true,
    val themeMode: String = "system",         // system | light | dark
    val workspaceTreeUri: String = "",
    val allowWorkspaceWrites: Boolean = false,
    val confirmWorkspaceWrites: Boolean = true,
    val allowWorkspaceCreate: Boolean = true,
    val allowWorkspaceEdit: Boolean = true,
    val agentMaxToolRounds: Int = 8,
    val agentMaxToolResultChars: Int = 24_000,
    val agentMaxWriteChars: Int = 100_000
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val PROVIDER = stringPreferencesKey("active_provider_id")
        val MODEL = stringPreferencesKey("active_model")
        val ASSISTANT = stringPreferencesKey("active_assistant_id")
        val SYSTEM = stringPreferencesKey("system_prompt")
        val TEMP = floatPreferencesKey("temperature")
        val STREAM = booleanPreferencesKey("stream")
        val LANG = stringPreferencesKey("language")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val THEME = stringPreferencesKey("theme_mode")
        val WORKSPACE_TREE_URI = stringPreferencesKey("workspace_tree_uri")
        val ALLOW_WORKSPACE_WRITES = booleanPreferencesKey("allow_workspace_writes")
        val CONFIRM_WORKSPACE_WRITES = booleanPreferencesKey("confirm_workspace_writes")
        val ALLOW_WORKSPACE_CREATE = booleanPreferencesKey("allow_workspace_create")
        val ALLOW_WORKSPACE_EDIT = booleanPreferencesKey("allow_workspace_edit")
        val AGENT_MAX_TOOL_ROUNDS = intPreferencesKey("agent_max_tool_rounds")
        val AGENT_MAX_TOOL_RESULT_CHARS = intPreferencesKey("agent_max_tool_result_chars")
        val AGENT_MAX_WRITE_CHARS = intPreferencesKey("agent_max_write_chars")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p -> read(p) }

    private fun read(p: Preferences) = AppSettings(
        activeProviderId = p[Keys.PROVIDER] ?: "",
        activeModel = p[Keys.MODEL] ?: "",
        activeAssistantId = p[Keys.ASSISTANT] ?: "default",
        systemPrompt = p[Keys.SYSTEM] ?: "You are a helpful assistant.",
        temperature = p[Keys.TEMP] ?: 0.7f,
        stream = p[Keys.STREAM] ?: true,
        language = p[Keys.LANG] ?: "system",
        dynamicColor = p[Keys.DYNAMIC] ?: true,
        themeMode = p[Keys.THEME] ?: "system",
        workspaceTreeUri = p[Keys.WORKSPACE_TREE_URI] ?: "",
        allowWorkspaceWrites = p[Keys.ALLOW_WORKSPACE_WRITES] ?: false,
        confirmWorkspaceWrites = p[Keys.CONFIRM_WORKSPACE_WRITES] ?: true,
        allowWorkspaceCreate = p[Keys.ALLOW_WORKSPACE_CREATE] ?: true,
        allowWorkspaceEdit = p[Keys.ALLOW_WORKSPACE_EDIT] ?: true,
        agentMaxToolRounds = (p[Keys.AGENT_MAX_TOOL_ROUNDS] ?: 8).coerceIn(1, 20),
        agentMaxToolResultChars = (p[Keys.AGENT_MAX_TOOL_RESULT_CHARS] ?: 24_000).coerceIn(4_000, 64_000),
        agentMaxWriteChars = (p[Keys.AGENT_MAX_WRITE_CHARS] ?: 100_000).coerceIn(1_000, 100_000)
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { p ->
            val next = transform(read(p))
            p[Keys.PROVIDER] = next.activeProviderId
            p[Keys.MODEL] = next.activeModel
            p[Keys.ASSISTANT] = next.activeAssistantId
            p[Keys.SYSTEM] = next.systemPrompt
            p[Keys.TEMP] = next.temperature
            p[Keys.STREAM] = next.stream
            p[Keys.LANG] = next.language
            p[Keys.DYNAMIC] = next.dynamicColor
            p[Keys.THEME] = next.themeMode
            p[Keys.WORKSPACE_TREE_URI] = next.workspaceTreeUri
            p[Keys.ALLOW_WORKSPACE_WRITES] = next.allowWorkspaceWrites
            p[Keys.CONFIRM_WORKSPACE_WRITES] = next.confirmWorkspaceWrites
            p[Keys.ALLOW_WORKSPACE_CREATE] = next.allowWorkspaceCreate
            p[Keys.ALLOW_WORKSPACE_EDIT] = next.allowWorkspaceEdit
            p[Keys.AGENT_MAX_TOOL_ROUNDS] = next.agentMaxToolRounds.coerceIn(1, 20)
            p[Keys.AGENT_MAX_TOOL_RESULT_CHARS] = next.agentMaxToolResultChars.coerceIn(4_000, 64_000)
            p[Keys.AGENT_MAX_WRITE_CHARS] = next.agentMaxWriteChars.coerceIn(1_000, 100_000)
        }
    }
}
