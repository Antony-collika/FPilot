package com.miniichat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichat.R
import com.miniichat.data.AppSettings
import com.miniichat.data.ProviderConfig

@Composable
fun SettingsScreen(
    settings: AppSettings,
    providers: List<ProviderConfig>,
    workspaceName: String,
    workspaceTreeUri: String,
    storageName: String,
    storageTreeUri: String,
    onBack: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onChooseWorkspace: () -> Unit,
    onClearWorkspace: () -> Unit,
    onChooseStorage: () -> Unit,
    onClearStorage: () -> Unit,
    onOpenProviders: () -> Unit,
    onChooseSystemPromptFile: () -> Unit,
    onChooseSkillsFile: () -> Unit,
    onOpenRequestPanel: () -> Unit
) {
    // Local state seeded once; LaunchedEffect resyncs only when external settings change.
    var settingsSubScreen by rememberSaveable { mutableStateOf("main") }
    var system by rememberSaveable { mutableStateOf(settings.systemPrompt) }
    var skills by rememberSaveable { mutableStateOf(settings.skillsText) }
    var temperature by rememberSaveable { mutableStateOf(settings.temperature) }
    val stream = settings.stream

    BackHandler(enabled = settingsSubScreen != "main") { settingsSubScreen = "main" }

    androidx.compose.runtime.LaunchedEffect(settings.systemPrompt) {
        if (settings.systemPrompt != system) system = settings.systemPrompt
    }
    androidx.compose.runtime.LaunchedEffect(settings.skillsText) {
        if (settings.skillsText != skills) skills = settings.skillsText
    }
    androidx.compose.runtime.LaunchedEffect(settings.temperature) {
        if (settings.temperature != temperature) temperature = settings.temperature
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(title = when (settingsSubScreen) { "permissions" -> "Tool permissions"; "tree" -> "Directory tree"; "behavior" -> "Behavior"; else -> stringResource(R.string.settings) }, onBack = { if (settingsSubScreen == "main") onBack() else settingsSubScreen = "main" })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            if (settingsSubScreen == "permissions") {
                SectionHeader("Permissions")
                SectionCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        SegmentedRow(
                            options = listOf("always_ask" to "Always ask", "allow_once" to "Allow once", "always_allow" to "Always allow"),
                            selectedKey = settings.toolPermissionMode,
                            onSelect = { mode -> onChange { it.copy(toolPermissionMode = mode) } }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when (settings.toolPermissionMode) {
                                "allow_once" -> "Ask once per agent run. Approval covers the remaining tool calls in that run only."
                                "always_allow" -> "Run enabled tools automatically. Disabled tools and write limits still apply."
                                else -> "Ask before every tool call."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                SectionHeader("Read actions")
                SectionCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        ControlSwitch("All read actions", when { settings.toolListFilesEnabled && settings.toolSearchFilesEnabled && settings.toolSearchContentEnabled && settings.toolReadFileEnabled && settings.toolGetFileInfoEnabled -> "All five read tools are enabled."; listOf(settings.toolListFilesEnabled, settings.toolSearchFilesEnabled, settings.toolSearchContentEnabled, settings.toolReadFileEnabled, settings.toolGetFileInfoEnabled).any { it } -> "Some read tools are enabled."; else -> "All five read tools are disabled." },
                            settings.toolListFilesEnabled && settings.toolSearchFilesEnabled && settings.toolSearchContentEnabled && settings.toolReadFileEnabled && settings.toolGetFileInfoEnabled,
                            { enabled -> onChange { it.copy(toolListFilesEnabled = enabled, toolSearchFilesEnabled = enabled, toolSearchContentEnabled = enabled, toolReadFileEnabled = enabled, toolGetFileInfoEnabled = enabled) } })
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        ControlSwitch("List files", "list_files", settings.toolListFilesEnabled, { v -> onChange { it.copy(toolListFilesEnabled = v) } })
                        ControlSwitch("Search files", "search_files", settings.toolSearchFilesEnabled, { v -> onChange { it.copy(toolSearchFilesEnabled = v) } })
                        ControlSwitch("Search file contents", "search_content", settings.toolSearchContentEnabled, { v -> onChange { it.copy(toolSearchContentEnabled = v) } })
                        ControlSwitch("Read file", "read_file", settings.toolReadFileEnabled, { v -> onChange { it.copy(toolReadFileEnabled = v) } })
                        ControlSwitch("Get file info", "get_file_info", settings.toolGetFileInfoEnabled, { v -> onChange { it.copy(toolGetFileInfoEnabled = v) } })
                    }
                }

                SectionHeader("Write actions")
                SectionCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        ControlSwitch("All write actions", when { settings.toolCreateFileEnabled && settings.toolEditFileEnabled && settings.allowWorkspaceCreate && settings.allowWorkspaceEdit -> "Both write tools are enabled."; settings.toolCreateFileEnabled || settings.toolEditFileEnabled || settings.allowWorkspaceCreate || settings.allowWorkspaceEdit -> "Some write permissions are enabled."; else -> "Both write tools are disabled." },
                            settings.toolCreateFileEnabled && settings.toolEditFileEnabled && settings.allowWorkspaceCreate && settings.allowWorkspaceEdit,
                            { enabled -> onChange { it.copy(toolCreateFileEnabled = enabled, toolEditFileEnabled = enabled, allowWorkspaceCreate = enabled, allowWorkspaceEdit = enabled) } })
                        ControlSwitch("Create Markdown file", "create_file",
                            settings.toolCreateFileEnabled && settings.allowWorkspaceCreate,
                            { v -> onChange { it.copy(toolCreateFileEnabled = v, allowWorkspaceCreate = v) } })
                        ControlSwitch("Edit Markdown file", "edit_file",
                            settings.toolEditFileEnabled && settings.allowWorkspaceEdit,
                            { v -> onChange { it.copy(toolEditFileEnabled = v, allowWorkspaceEdit = v) } })
                    }
                }

                SectionHeader("Limits")
                SectionCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text("Maximum tool rounds: ${settings.agentMaxToolRounds}", style = MaterialTheme.typography.bodyLarge)
                        Slider(value = settings.agentMaxToolRounds.toFloat(), onValueChange = { v -> onChange { it.copy(agentMaxToolRounds = v.toInt().coerceIn(1, 20)) } }, valueRange = 1f..20f, steps = 18)
                        Text("Max chunk size: ${settings.agentMaxFileReadKb} kB", style = MaterialTheme.typography.bodyLarge)
                        Slider(value = settings.agentMaxFileReadKb.toFloat(), onValueChange = { v -> onChange { it.copy(agentMaxFileReadKb = ((v.toInt() + 4) / 8 * 8).coerceIn(8, 256)) } }, valueRange = 8f..256f, steps = 30)
                        Text("Large files will be read in chunks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Maximum file write: ${settings.agentMaxWriteChars / 1000}k characters", style = MaterialTheme.typography.bodyLarge)
                        Slider(value = settings.agentMaxWriteChars.toFloat(), onValueChange = { v -> onChange { it.copy(agentMaxWriteChars = ((v.toInt() + 5000) / 10000 * 10000).coerceIn(10000, 100000)) } }, valueRange = 10000f..100000f, steps = 8)
                    }
                }
            } else if (settingsSubScreen == "behavior") {
                SectionHeader("System prompt")
                SectionCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text("Choose one source for the system prompt.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SourceChoiceRow("Text input", "text", settings.systemPromptSource) {
                            onChange { it.copy(systemPromptSource = "text") }
                        }
                        SourceChoiceRow("External file", "file", settings.systemPromptSource) {
                            onChange { it.copy(systemPromptSource = "file") }
                        }
                        if (settings.systemPromptSource == "text") {
                            Spacer(Modifier.height(6.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = system,
                                onValueChange = {
                                    system = it
                                    onChange { current -> current.copy(systemPrompt = it) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(
                                    color = LocalContentColor.current,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                minLines = 3,
                                maxLines = 10
                            )
                            HorizontalDivider(Modifier.padding(top = 4.dp))
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                settings.systemPromptFileName.ifBlank { "No system prompt file selected" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.material3.TextButton(onClick = onChooseSystemPromptFile) {
                                Text(if (settings.systemPromptFileUri.isBlank()) "Choose file" else "Change file")
                            }
                            Text("Reads UTF-8 text from the selected file when sending a request.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                SectionHeader("Skills")
                SectionCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Include Skills", style = MaterialTheme.typography.bodyLarge)
                                Text("Adds extra task instructions to each request when enabled.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = settings.skillsEnabled,
                                onCheckedChange = { enabled -> onChange { it.copy(skillsEnabled = enabled) } }
                            )
                        }
                        if (settings.skillsEnabled) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text("Choose one Skills source.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SourceChoiceRow("Text input", "text", settings.skillsSource) {
                                onChange { it.copy(skillsSource = "text") }
                            }
                            SourceChoiceRow("External file", "file", settings.skillsSource) {
                                onChange { it.copy(skillsSource = "file") }
                            }
                            if (settings.skillsSource == "text") {
                                Spacer(Modifier.height(6.dp))
                                androidx.compose.foundation.text.BasicTextField(
                                    value = skills,
                                    onValueChange = { value ->
                                        skills = value
                                        onChange { it.copy(skillsText = value) }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = LocalTextStyle.current.copy(
                                        color = LocalContentColor.current,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    minLines = 3,
                                    maxLines = 10
                                )
                                HorizontalDivider(Modifier.padding(top = 4.dp))
                            } else {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    settings.skillsFileName.ifBlank { "No Skills file selected" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                androidx.compose.material3.TextButton(onClick = onChooseSkillsFile) {
                                    Text(if (settings.skillsFileUri.isBlank()) "Choose file" else "Change file")
                                }
                                Text("Reads UTF-8 text from the selected file when sending a request.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                SectionHeader("Generation")
                SectionCard {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            "${stringResource(R.string.setting_temperature)}: ${"%.2f".format(temperature)}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Slider(
                            value = temperature,
                            onValueChange = {
                                temperature = it
                                onChange { current -> current.copy(temperature = it) }
                            },
                            valueRange = 0f..2f,
                            steps = 19
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.setting_stream), style = MaterialTheme.typography.bodyLarge)
                                Text("Server-Sent Events", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = stream,
                                onCheckedChange = { enabled -> onChange { it.copy(stream = enabled) } }
                            )
                        }
                    }
                }
            } else if (settingsSubScreen == "tree") {
                SectionHeader("Prompt context")
                SectionCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        ControlSwitch("Include directory tree", "Adds a stable, timestamp-free tree of the selected folder to the request.",
                            settings.directoryTreeEnabled, { v -> onChange { it.copy(directoryTreeEnabled = v) } })
                        Spacer(Modifier.height(8.dp))
                        Text("Maximum scan depth: ${settings.directoryTreeDepth}", style = MaterialTheme.typography.bodyLarge)
                        Slider(value = settings.directoryTreeDepth.toFloat(),
                            onValueChange = { v -> onChange { it.copy(directoryTreeDepth = v.toInt().coerceIn(1, 8)) } },
                            valueRange = 1f..8f, steps = 6)
                        Text("Only the selected SAF folder is scanned. A tree marked verified=false may be incomplete.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (workspaceTreeUri.isBlank()) {
                            Text("Choose a workspace folder first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else {
                // Keep the main settings page compact; advanced agent controls live on their own pages.
                SectionHeader("Workspace")
                SectionCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Workspace", style = MaterialTheme.typography.titleMedium)
                                Text(if (workspaceTreeUri.isBlank()) "No selection" else workspaceName,
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (workspaceTreeUri.isNotBlank()) androidx.compose.material3.TextButton(onClick = onClearWorkspace) { Text("Clear") }
                            androidx.compose.material3.Button(onClick = onChooseWorkspace) {
                                Text(if (workspaceTreeUri.isBlank()) "Choose folder" else "Change folder")
                            }
                        }
                        Text("File access is confined to this folder. Write actions only create or edit Markdown files.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Allowed write subfolder (optional)", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        OutlinedFieldBox {
                            BasicTextField(value = settings.workspaceWriteSubpath,
                                onValueChange = { value -> onChange { it.copy(workspaceWriteSubpath = value) } },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current, fontSize = 14.sp, lineHeight = 20.sp),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = true)
                        }
                        Text("Blank allows writes anywhere in the selected workspace. Example: notes/projects",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SectionHeader("Session storage")
                SectionCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Storage", style = MaterialTheme.typography.titleMedium)
                                Text(if (storageTreeUri.isBlank()) "No selection" else storageName,
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (storageTreeUri.isNotBlank()) androidx.compose.material3.TextButton(onClick = onClearStorage) { Text("Clear") }
                            androidx.compose.material3.Button(onClick = onChooseStorage) {
                                Text(if (storageTreeUri.isBlank()) "Choose folder" else "Change folder")
                            }
                        }
                        Text(
                            "Separate from Workspace. New chats create a sessions folder with a unique ID, history.md, and memory.md. Existing chats are kept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                SectionCard {
                    SettingsNavigationRow("Tool permissions", "Configure access to seven file tools", Icons.Default.Storage) { settingsSubScreen = "permissions" }
                }
            // Behavior entry
            SectionHeader("Request")
            SectionCard {
                SettingsNavigationRow(
                    "Request Control Panel",
                    "Cards sent to the model: system prompt, directory tree, history, temperature, streaming",
                    Icons.Default.Storage
                ) { onOpenRequestPanel() }
            }

            // Providers entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenProviders)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.providers),
                            style = MaterialTheme.typography.titleMedium)
                        Text("${providers.size} configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Appearance
            SectionHeader(stringResource(R.string.setting_appearance))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.setting_dynamic_color),
                                style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.setting_dynamic_color_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = { v ->
                                onChange { s -> s.copy(dynamicColor = v) }
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.setting_theme_mode),
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    SegmentedRow(
                        options = listOf(
                            "system" to stringResource(R.string.setting_theme_system),
                            "light" to stringResource(R.string.setting_theme_light),
                            "dark" to stringResource(R.string.setting_theme_dark)
                        ),
                        selectedKey = settings.themeMode,
                        onSelect = { k -> onChange { s -> s.copy(themeMode = k) } }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.setting_language),
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    SegmentedRow(
                        options = listOf(
                            "system" to stringResource(R.string.setting_language_system),
                            "en" to stringResource(R.string.setting_language_en),
                            "zh" to stringResource(R.string.setting_language_zh)
                        ),
                        selectedKey = settings.language,
                        onSelect = { k -> onChange { s -> s.copy(language = k) } }
                    )
                }
            }

            SectionHeader(stringResource(R.string.section_about))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(stringResource(R.string.about_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceChoiceRow(
    label: String,
    key: String,
    selectedKey: String,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.RadioButton(
            selected = selectedKey == key,
            onClick = onSelect
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ControlSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SegmentedRow(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
    ) {
        options.forEach { (key, label) ->
            val selected = key == selectedKey
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { onSelect(key) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SectionCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) { content() }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun OutlinedFieldBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) { content() }
}

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}
