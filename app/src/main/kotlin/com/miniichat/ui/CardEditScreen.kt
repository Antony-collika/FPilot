package com.miniichat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.miniichat.data.AppSettings
import com.miniichat.data.CardRole
import com.miniichat.data.RequestCard
import com.miniichat.data.SessionFiles
import com.miniichat.data.ToolsCardException
import com.miniichat.data.ToolsCardFormat
import com.miniichat.data.displayName
import com.miniichat.files.MarkdownFileTools

/** A file the user just chose for a card. It goes into the card being edited, but only counts after Save. */
data class PickedFile(val cardId: String, val uri: String, val name: String)

/**
 * The edit screen of one card. Changes are kept in a draft and only applied with Save.
 * Leaving with unsaved changes asks first.
 */
@Composable
fun CardEditScreen(
    card: RequestCard,
    settings: AppSettings,
    hasWorkspace: Boolean,
    sessionMemoryEnabled: Boolean,
    historyTurnCount: Int,
    pickedFile: PickedFile?,
    onPickedFileConsumed: () -> Unit,
    onSaveCard: (RequestCard) -> Unit,
    onSaveSettings: ((AppSettings) -> AppSettings) -> Unit,
    onClose: () -> Unit,
    onPickFile: (String) -> Unit,
    onPickMemoryFile: (String) -> Unit,
    onCreateTemplate: (String) -> Unit,
    onOpenHistoryManager: () -> Unit,
    sessionFiles: SessionFiles?,
    onRepairHistory: () -> Unit,
    onRepairMemory: () -> Unit,
    onCreateHistoryTemplate: () -> Unit,
    onOpenFile: (String) -> Unit,
    sessionFiles: SessionFiles?,
    onRepairMemory: () -> Unit,
    checkMemory: suspend () -> String?
) {
    var draft by remember(card.id) { mutableStateOf(card) }
    var temperature by remember(card.id) { mutableStateOf(settings.temperature) }
    var stream by remember(card.id) { mutableStateOf(settings.stream) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(pickedFile) {
        if (pickedFile != null && pickedFile.cardId == card.id) {
            draft = draft.copy(source = "file", fileUri = pickedFile.uri, fileName = pickedFile.name)
            onPickedFileConsumed()
        }
    }

    val isMetadata = card.role == CardRole.METADATA
    val dirty = if (isMetadata) {
        temperature != settings.temperature || stream != settings.stream
    } else {
        draft != card
    }

    fun requestExit() {
        if (dirty) confirmDiscard = true else onClose()
    }

    fun save() {
        if (isMetadata) {
            onSaveSettings { it.copy(temperature = temperature, stream = stream) }
        } else {
            onSaveCard(draft)
        }
        onClose()
    }

    BackHandler(enabled = true) { requestExit() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(title = card.displayName(), onBack = { requestExit() })
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (card.role) {
                CardRole.METADATA -> MetadataEditor(
                    settings = settings,
                    temperature = temperature,
                    onTemperature = { temperature = it },
                    stream = stream,
                    onStream = { stream = it }
                )
                CardRole.DIRECTORY_TREE -> TreeEditor(draft, hasWorkspace) { draft = it }
                CardRole.HISTORY -> HistoryEditor(draft, dirty, { draft = it }, onOpenHistoryManager, sessionFiles, onRepairHistory, onCreateHistoryTemplate, onOpenFile)
                CardRole.MEMORY -> MemoryEditor(
                    card = draft,
                    historyTurnCount = historyTurnCount,
                    sessionMemoryEnabled = sessionMemoryEnabled,
                    onChange = { draft = it },
                    onPickMemoryFile = { onPickMemoryFile(card.id) },
                    onCreateTemplate = onCreateTemplate,
                    onOpenFile = onOpenFile,
                    sessionFiles = sessionFiles,
                    onRepairMemory = onRepairMemory,
                    checkMemory = checkMemory
                )
                CardRole.TOOLS -> ToolsEditor(
                    card = draft,
                    hasWorkspace = hasWorkspace,
                    onChange = { draft = it },
                    onPickFile = { onPickFile(card.id) },
                    onCreateTemplate = onCreateTemplate,
                    onOpenFile = onOpenFile
                )
                else -> TextEditor(
                    card = draft,
                    onChange = { draft = it },
                    onPickFile = { onPickFile(card.id) },
                    onCreateTemplate = onCreateTemplate,
                    onOpenFile = onOpenFile
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { requestExit() }) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { save() }, enabled = dirty) { Text("Save") }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("You changed this card but did not save it.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } }
        )
    }
}

@Composable
private fun MetadataEditor(
    settings: AppSettings,
    temperature: Float,
    onTemperature: (Float) -> Unit,
    stream: Boolean,
    onStream: (Boolean) -> Unit
) {
    Text("Model", style = MaterialTheme.typography.labelLarge)
    Text(
        settings.activeModel.ifBlank { "No model selected" },
        style = MaterialTheme.typography.bodyLarge
    )
    Text(
        "The model and provider are chosen with the model picker in the chat.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Text("Temperature: ${"%.2f".format(temperature)}", style = MaterialTheme.typography.labelLarge)
    Slider(value = temperature, onValueChange = onTemperature, valueRange = 0f..2f, steps = 19)
    SwitchRow(
        title = "Streaming",
        description = "Show the answer as it is written. Used when no tools run; with tools the app waits for the whole answer.",
        checked = stream,
        onCheckedChange = onStream
    )
}

@Composable
private fun TreeEditor(card: RequestCard, hasWorkspace: Boolean, onChange: (RequestCard) -> Unit) {
    UnderlinedField(
        value = card.roleLabel,
        onValueChange = { onChange(card.copy(roleLabel = it)) },
        label = "Role sent to the model"
    )
    Hint("Default is user. The first line of the tree tells the model it was scanned by the app and not typed by you. " +
        "If your provider rejects the role with an error, try system.")
    Spacer(Modifier.height(8.dp))
    Text("Maximum scan depth: ${card.treeDepth}", style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = card.treeDepth.toFloat(),
        onValueChange = { onChange(card.copy(treeDepth = it.toInt().coerceIn(1, 8))) },
        valueRange = 1f..8f,
        steps = 6
    )
    Hint("Adds a tree of the workspace folder. A tree marked verified=false may be incomplete.")
    if (!hasWorkspace) {
        Hint("Choose a workspace folder in Settings first, or sending will stop with an error.", isError = true)
    }
}

@Composable
private fun HistoryEditor(
    card: RequestCard,
    dirty: Boolean,
    onChange: (RequestCard) -> Unit,
    onOpenHistoryManager: () -> Unit,
    sessionFiles: SessionFiles?,
    onRepairHistory: () -> Unit,
    onCreateHistoryTemplate: () -> Unit,
    onOpenFile: (String) -> Unit
) {
    SwitchRow(
        title = "Short notes for old tool results",
        description = "Old turns send a one-line note instead of the full text the tools returned. " +
            "The full text stays saved. Changing this makes the provider read the whole History again once.",
        checked = card.historyCompact,
        onCheckedChange = { onChange(card.copy(historyCompact = it)) }
    )
    SwitchRow(
        title = "Number the turns",
        description = "Every message you send starts with a tag such as [T7], so the model and the Memory file can refer to a turn. " +
            "Changing this makes the provider read the whole History again once.",
        checked = card.turnIdEnabled,
        onCheckedChange = { onChange(card.copy(turnIdEnabled = it)) }
    )
    sessionFiles?.let { files ->
        TextButton(onClick = { onOpenFile(files.historyUri) }) { Text("Open history.md") }
        TextButton(onClick = onRepairHistory) { Text("Repair history.md") }
        TextButton(onClick = onCreateHistoryTemplate) { Text("Create history.md template") }
    }
    TextButton(onClick = onOpenHistoryManager, enabled = !dirty) { Text("Manage messages of this chat") }
    if (dirty) Hint("Save your changes first to open the message list.")
}

@Composable
private fun MemoryEditor(
    card: RequestCard,
    historyTurnCount: Int,
    sessionMemoryEnabled: Boolean,
    onChange: (RequestCard) -> Unit,
    onPickMemoryFile: () -> Unit,
    onCreateTemplate: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    checkMemory: suspend () -> String?
) {
    var warning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(card.fileUri, historyTurnCount, sessionMemoryEnabled) { warning = checkMemory() }

    Text("Role sent to the model", style = MaterialTheme.typography.bodyLarge)
    SourceRadio("user (recommended)", card.roleLabel.trim().lowercase() != "system") { onChange(card.copy(roleLabel = "user")) }
    SourceRadio("system", card.roleLabel.trim().lowercase() == "system") { onChange(card.copy(roleLabel = "system")) }
    Hint("Some providers move every system message to the top of the conversation, which breaks the cache. " +
        "With user, the memory stays where you put the card, and its first line tells the model it is not something you typed.")
    Spacer(Modifier.height(4.dp))
    if (sessionMemoryEnabled) {
        Text(
            "memory.md — one file per session",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Hint("The app creates this file inside the session folder when the first message is sent. It is read again before every request, so manual edits are respected.")
        sessionFiles?.let { files ->
            TextButton(onClick = { onOpenFile(files.memoryUri) }) { Text("Open memory.md") }
            TextButton(onClick = onRepairMemory) { Text("Repair memory.md") }
        }
    } else {
        Text(
            card.fileName.ifBlank { "No Memory file selected" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onPickMemoryFile) {
            Text(if (card.fileUri.isBlank()) "Choose Memory file" else "Change Memory file")
        }
        if (card.fileUri.isNotBlank()) TextButton(onClick = { onOpenFile(card.fileUri) }) { Text("Open file") }
        TextButton(onClick = { onCreateTemplate("memory") }) { Text("Create a template file") }
        Hint("The model updates this file itself at the end of its answers. You can edit the file by hand between messages.")
    }
    warning?.let { Hint(it, isError = true) }
}

@Composable
private fun ToolsEditor(
    card: RequestCard,
    hasWorkspace: Boolean,
    onChange: (RequestCard) -> Unit,
    onPickFile: () -> Unit,
    onCreateTemplate: (String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    var message by remember { mutableStateOf<String?>(null) }
    Hint("This card is not a message: it is the tool list sent with the request, so its position in the panel changes nothing. " +
        "Each block has four fields. uid and origin_description say which tool it really is (do not change them). " +
        "name and description are what the model sees (you can change them).")
    if (!hasWorkspace) {
        Hint("Choose a workspace folder in Settings first, or sending will stop with an error.", isError = true)
    }
    SourceRadio("Text input", card.source == "text") { onChange(card.copy(source = "text")) }
    SourceRadio("External file", card.source == "file") { onChange(card.copy(source = "file")) }
    if (card.source == "text") {
        UnderlinedField(
            value = card.text,
            onValueChange = { message = null; onChange(card.copy(text = it)) },
            label = "Tools text",
            minLines = 8,
            maxLines = 40
        )
        val problem = remember(card.text) {
            try {
                ToolsCardFormat.parse(card.text, MarkdownFileTools.allDefinitions().associate { it.name to it.description })
                null
            } catch (e: ToolsCardException) {
                e.message
            }
        }
        if (problem != null) Hint(problem, isError = true) else Hint("This text is valid.")
        TextButton(onClick = {
            try {
                onChange(card.copy(text = ToolsCardFormat.refresh(card.text, MarkdownFileTools.allDefinitions())))
                message = "Refreshed: uid and origin_description are up to date. Your names and descriptions were kept."
            } catch (e: ToolsCardException) {
                message = e.message
            }
        }) { Text("Refresh uid and origin lines") }
        TextButton(onClick = {
            onChange(card.copy(text = ToolsCardFormat.template(MarkdownFileTools.allDefinitions())))
            message = "Replaced with a fresh template (nothing is saved until you press Save)."
        }) { Text("Replace with a fresh template") }
        message?.let { Hint(it) }
    } else {
        Text(
            card.fileName.ifBlank { "No file selected" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onPickFile) { Text(if (card.fileUri.isBlank()) "Choose file" else "Change file") }
        if (card.fileUri.isNotBlank()) TextButton(onClick = { onOpenFile(card.fileUri) }) { Text("Open file") }
        TextButton(onClick = { onCreateTemplate("tools") }) { Text("Create a template file") }
        Hint("The file is read again every time you send a message.")
    }
}

@Composable
private fun TextEditor(
    card: RequestCard,
    onChange: (RequestCard) -> Unit,
    onPickFile: () -> Unit,
    onCreateTemplate: (String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    UnderlinedField(
        value = card.title,
        onValueChange = { onChange(card.copy(title = it)) },
        label = "Name"
    )
    if (card.role == CardRole.FREE) {
        UnderlinedField(
            value = card.roleLabel,
            onValueChange = { onChange(card.copy(roleLabel = it)) },
            label = "Role sent to the model (for example system or user)"
        )
    }
    Spacer(Modifier.height(4.dp))
    SourceRadio("Text input", card.source == "text") { onChange(card.copy(source = "text")) }
    SourceRadio("External file", card.source == "file") { onChange(card.copy(source = "file")) }
    if (card.source == "text") {
        UnderlinedField(
            value = card.text,
            onValueChange = { onChange(card.copy(text = it)) },
            label = "Text",
            minLines = 5,
            maxLines = 30
        )
    } else {
        Text(
            card.fileName.ifBlank { "No file selected" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onPickFile) { Text(if (card.fileUri.isBlank()) "Choose file" else "Change file") }
        if (card.fileUri.isNotBlank()) TextButton(onClick = { onOpenFile(card.fileUri) }) { Text("Open file") }
        TextButton(onClick = { onCreateTemplate("system") }) { Text("Create a template file") }
        Hint("The file is read again every time you send a message.")
    }
}

@Composable
private fun Hint(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SourceRadio(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
