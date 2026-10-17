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
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f,
    val stream: Boolean = true,
    val language: String = "system",          // system | en | zh
    val dynamicColor: Boolean = true,
    val themeMode: String = "system",         // system | light | dark
    val workspaceTreeUri: String = "",
    val workspaceWriteSubpath: String = "",
    val confirmWorkspaceWrites: Boolean = true,
    val allowWorkspaceCreate: Boolean = true,
    val allowWorkspaceEdit: Boolean = true,
    val agentMaxToolRounds: Int = 8,
    val agentMaxToolResultChars: Int = 24_000,
    val agentMaxWriteChars: Int = 100_000,
    val toolPermissionMode: String = "always_ask", // always_ask | allow_once | always_allow
    val toolListFilesEnabled: Boolean = true,
    val toolSearchFilesEnabled: Boolean = true,
    val toolSearchContentEnabled: Boolean = true,
    val toolReadFileEnabled: Boolean = true,
    val toolGetFileInfoEnabled: Boolean = true,
    val toolCreateFileEnabled: Boolean = true,
    val toolEditFileEnabled: Boolean = true,
    val directoryTreeEnabled: Boolean = false,
    val directoryTreeDepth: Int = 2,
    val systemPromptSource: String = "text", // text | file
    val systemPromptFileUri: String = "",
    val systemPromptFileName: String = "",
    val skillsEnabled: Boolean = false,
    val skillsSource: String = "text", // text | file
    val skillsText: String = "",
    val skillsFileUri: String = "",
    val skillsFileName: String = ""
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val PROVIDER = stringPreferencesKey("active_provider_id")
        val MODEL = stringPreferencesKey("active_model")
        val SYSTEM = stringPreferencesKey("system_prompt")
        val TEMP = floatPreferencesKey("temperature")
        val STREAM = booleanPreferencesKey("stream")
        val LANG = stringPreferencesKey("language")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val THEME = stringPreferencesKey("theme_mode")
        val WORKSPACE_TREE_URI = stringPreferencesKey("workspace_tree_uri")
        val WORKSPACE_WRITE_SUBPATH = stringPreferencesKey("workspace_write_subpath")
        val CONFIRM_WORKSPACE_WRITES = booleanPreferencesKey("confirm_workspace_writes")
        val ALLOW_WORKSPACE_CREATE = booleanPreferencesKey("allow_workspace_create")
        val ALLOW_WORKSPACE_EDIT = booleanPreferencesKey("allow_workspace_edit")
        val AGENT_MAX_TOOL_ROUNDS = intPreferencesKey("agent_max_tool_rounds")
        val AGENT_MAX_TOOL_RESULT_CHARS = intPreferencesKey("agent_max_tool_result_chars")
        val AGENT_MAX_WRITE_CHARS = intPreferencesKey("agent_max_write_chars")
        val TOOL_PERMISSION_MODE = stringPreferencesKey("tool_permission_mode")
        val TOOL_LIST_FILES = booleanPreferencesKey("tool_list_files_enabled")
        val TOOL_SEARCH_FILES = booleanPreferencesKey("tool_search_files_enabled")
        val TOOL_SEARCH_CONTENT = booleanPreferencesKey("tool_search_content_enabled")
        val TOOL_READ_FILE = booleanPreferencesKey("tool_read_file_enabled")
        val TOOL_GET_FILE_INFO = booleanPreferencesKey("tool_get_file_info_enabled")
        val TOOL_CREATE_FILE = booleanPreferencesKey("tool_create_file_enabled")
        val TOOL_EDIT_FILE = booleanPreferencesKey("tool_edit_file_enabled")
        val DIRECTORY_TREE_ENABLED = booleanPreferencesKey("directory_tree_enabled")
        val DIRECTORY_TREE_DEPTH = intPreferencesKey("directory_tree_depth")
        val SYSTEM_PROMPT_SOURCE = stringPreferencesKey("system_prompt_source")
        val SYSTEM_PROMPT_FILE_URI = stringPreferencesKey("system_prompt_file_uri")
        val SYSTEM_PROMPT_FILE_NAME = stringPreferencesKey("system_prompt_file_name")
        val SKILLS_ENABLED = booleanPreferencesKey("skills_enabled")
        val SKILLS_SOURCE = stringPreferencesKey("skills_source")
        val SKILLS_TEXT = stringPreferencesKey("skills_text")
        val SKILLS_FILE_URI = stringPreferencesKey("skills_file_uri")
        val SKILLS_FILE_NAME = stringPreferencesKey("skills_file_name")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p -> read(p) }

    private fun read(p: Preferences) = AppSettings(
        activeProviderId = p[Keys.PROVIDER] ?: "",
        activeModel = p[Keys.MODEL] ?: "",
        systemPrompt = p[Keys.SYSTEM] ?: "You are a helpful assistant.",
        temperature = p[Keys.TEMP] ?: 0.7f,
        stream = p[Keys.STREAM] ?: true,
        language = p[Keys.LANG] ?: "system",
        dynamicColor = p[Keys.DYNAMIC] ?: true,
        themeMode = p[Keys.THEME] ?: "system",
        workspaceTreeUri = p[Keys.WORKSPACE_TREE_URI] ?: "",
        workspaceWriteSubpath = p[Keys.WORKSPACE_WRITE_SUBPATH] ?: "",
        confirmWorkspaceWrites = p[Keys.CONFIRM_WORKSPACE_WRITES] ?: true,
        allowWorkspaceCreate = p[Keys.ALLOW_WORKSPACE_CREATE] ?: true,
        allowWorkspaceEdit = p[Keys.ALLOW_WORKSPACE_EDIT] ?: true,
        agentMaxToolRounds = (p[Keys.AGENT_MAX_TOOL_ROUNDS] ?: 8).coerceIn(1, 20),
        agentMaxToolResultChars = (p[Keys.AGENT_MAX_TOOL_RESULT_CHARS] ?: 24_000).coerceIn(4_000, 64_000),
        agentMaxWriteChars = (p[Keys.AGENT_MAX_WRITE_CHARS] ?: 100_000).coerceIn(10_000, 100_000),
        toolPermissionMode = (p[Keys.TOOL_PERMISSION_MODE] ?: "always_ask").takeIf {
            it in setOf("always_ask", "allow_once", "always_allow")
        } ?: "always_ask",
        toolListFilesEnabled = p[Keys.TOOL_LIST_FILES] ?: true,
        toolSearchFilesEnabled = p[Keys.TOOL_SEARCH_FILES] ?: true,
        toolSearchContentEnabled = p[Keys.TOOL_SEARCH_CONTENT] ?: true,
        toolReadFileEnabled = p[Keys.TOOL_READ_FILE] ?: true,
        toolGetFileInfoEnabled = p[Keys.TOOL_GET_FILE_INFO] ?: true,
        toolCreateFileEnabled = p[Keys.TOOL_CREATE_FILE] ?: true,
        toolEditFileEnabled = p[Keys.TOOL_EDIT_FILE] ?: true,
        directoryTreeEnabled = p[Keys.DIRECTORY_TREE_ENABLED] ?: false,
        directoryTreeDepth = (p[Keys.DIRECTORY_TREE_DEPTH] ?: 2).coerceIn(1, 8),
        systemPromptSource = (p[Keys.SYSTEM_PROMPT_SOURCE] ?: "text").takeIf { it in setOf("text", "file") } ?: "text",
        systemPromptFileUri = p[Keys.SYSTEM_PROMPT_FILE_URI] ?: "",
        systemPromptFileName = p[Keys.SYSTEM_PROMPT_FILE_NAME] ?: "",
        skillsEnabled = p[Keys.SKILLS_ENABLED] ?: false,
        skillsSource = (p[Keys.SKILLS_SOURCE] ?: "text").takeIf { it in setOf("text", "file") } ?: "text",
        skillsText = p[Keys.SKILLS_TEXT] ?: "",
        skillsFileUri = p[Keys.SKILLS_FILE_URI] ?: "",
        skillsFileName = p[Keys.SKILLS_FILE_NAME] ?: ""
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { p ->
            val next = transform(read(p))
            p[Keys.PROVIDER] = next.activeProviderId
            p[Keys.MODEL] = next.activeModel
            p[Keys.SYSTEM] = next.systemPrompt
            p[Keys.TEMP] = next.temperature
            p[Keys.STREAM] = next.stream
            p[Keys.LANG] = next.language
            p[Keys.DYNAMIC] = next.dynamicColor
            p[Keys.THEME] = next.themeMode
            p[Keys.WORKSPACE_TREE_URI] = next.workspaceTreeUri
            p[Keys.WORKSPACE_WRITE_SUBPATH] = next.workspaceWriteSubpath.trim().replace(92.toChar(), '/').trim('/')
            p[Keys.CONFIRM_WORKSPACE_WRITES] = next.confirmWorkspaceWrites
            p[Keys.ALLOW_WORKSPACE_CREATE] = next.allowWorkspaceCreate
            p[Keys.ALLOW_WORKSPACE_EDIT] = next.allowWorkspaceEdit
            p[Keys.AGENT_MAX_TOOL_ROUNDS] = next.agentMaxToolRounds.coerceIn(1, 20)
            p[Keys.AGENT_MAX_TOOL_RESULT_CHARS] = next.agentMaxToolResultChars.coerceIn(4_000, 64_000)
            p[Keys.AGENT_MAX_WRITE_CHARS] = next.agentMaxWriteChars.coerceIn(10_000, 100_000)
            p[Keys.TOOL_PERMISSION_MODE] = next.toolPermissionMode.takeIf {
                it in setOf("always_ask", "allow_once", "always_allow")
            } ?: "always_ask"
            p[Keys.TOOL_LIST_FILES] = next.toolListFilesEnabled
            p[Keys.TOOL_SEARCH_FILES] = next.toolSearchFilesEnabled
            p[Keys.TOOL_SEARCH_CONTENT] = next.toolSearchContentEnabled
            p[Keys.TOOL_READ_FILE] = next.toolReadFileEnabled
            p[Keys.TOOL_GET_FILE_INFO] = next.toolGetFileInfoEnabled
            p[Keys.TOOL_CREATE_FILE] = next.toolCreateFileEnabled
            p[Keys.TOOL_EDIT_FILE] = next.toolEditFileEnabled
            p[Keys.DIRECTORY_TREE_ENABLED] = next.directoryTreeEnabled
            p[Keys.DIRECTORY_TREE_DEPTH] = next.directoryTreeDepth.coerceIn(1, 8)
            p[Keys.SYSTEM_PROMPT_SOURCE] = next.systemPromptSource.takeIf { it in setOf("text", "file") } ?: "text"
            p[Keys.SYSTEM_PROMPT_FILE_URI] = next.systemPromptFileUri
            p[Keys.SYSTEM_PROMPT_FILE_NAME] = next.systemPromptFileName
            p[Keys.SKILLS_ENABLED] = next.skillsEnabled
            p[Keys.SKILLS_SOURCE] = next.skillsSource.takeIf { it in setOf("text", "file") } ?: "text"
            p[Keys.SKILLS_TEXT] = next.skillsText
            p[Keys.SKILLS_FILE_URI] = next.skillsFileUri
            p[Keys.SKILLS_FILE_NAME] = next.skillsFileName
        }
    }
}
