package com.miniichat.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichat.data.AppSettings
import com.miniichat.data.CardRole
import com.miniichat.data.DIRECTORY_TREE_ROLE
import com.miniichat.data.RequestCard
import com.miniichat.data.RequestLayout
import com.miniichat.data.ToolsCardFormat
import com.miniichat.data.displayName
import com.miniichat.data.insertionIndexFor
import com.miniichat.data.moved
import com.miniichat.files.MarkdownFileTools
import com.miniichat.util.newId
import kotlinx.coroutines.launch

/**
 * The Request Control Panel: the list of cards that make up every request.
 * Cards are sent to the model from top to bottom.
 */
@Composable
fun RequestPanelScreen(
    layout: RequestLayout,
    settings: AppSettings,
    hasWorkspace: Boolean,
    historyTurnCount: Int,
    onBack: () -> Unit,
    onLayoutChange: (RequestLayout) -> Unit,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onPickFile: (String) -> Unit,
    onPickMemoryFile: (String) -> Unit,
    onCreateTemplate: (String) -> Unit,
    onOpenHistoryManager: () -> Unit,
    checkMemory: suspend () -> String?,
    makePreview: suspend () -> String,
    onReset: () -> Unit
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val hasTools = layout.cards.any { it.role == CardRole.TOOLS }
    val hasMemory = layout.cards.any { it.role == CardRole.MEMORY }

    fun addCard(role: String) {
        val card = when (role) {
            CardRole.FREE -> RequestCard(id = newId(), role = role, title = "Free card", roleLabel = "user")
            CardRole.DIRECTORY_TREE -> RequestCard(id = newId(), role = role, roleLabel = DIRECTORY_TREE_ROLE)
            CardRole.TOOLS -> RequestCard(
                id = newId(),
                role = role,
                text = ToolsCardFormat.template(MarkdownFileTools.allDefinitions())
            )
            else -> RequestCard(id = newId(), role = role)
        }
        val list = layout.cards.toMutableList()
        list.add(layout.insertionIndexFor(role), card)
        notice = null
        editingId = card.id
        onLayoutChange(RequestLayout(list))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(title = "Request Control Panel", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Every request is built from these cards, from top to bottom. " +
                    "Put cards that rarely change near the top and cards that change often near the bottom, " +
                    "so the provider can reuse its cache. History must stay above Last turn.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            layout.cards.forEach { card ->
                androidx.compose.runtime.key(card.id) {
                    PanelCard(
                        card = card,
                        settings = settings,
                        hasWorkspace = hasWorkspace,
                        historyTurnCount = historyTurnCount,
                        editing = editingId == card.id,
                        onToggleEdit = { editingId = if (editingId == card.id) null else card.id },
                        onChange = { changed ->
                            notice = null
                            onLayoutChange(RequestLayout(layout.cards.map { if (it.id == changed.id) changed else it }))
                        },
                        onMove = { delta ->
                            val result = layout.moved(card.id, delta)
                            if (result == null) {
                                notice = "This card cannot move there. Metadata stays first and History stays above Last turn."
                            } else {
                                notice = null
                                onLayoutChange(result)
                            }
                        },
                        onDelete = { deletingId = card.id },
                        onPickFile = { onPickFile(card.id) },
                        onPickMemoryFile = { onPickMemoryFile(card.id) },
                        onCreateTemplate = onCreateTemplate,
                        onOpenHistoryManager = onOpenHistoryManager,
                        checkMemory = checkMemory,
                        onSettingsChange = onSettingsChange
                    )
                }
            }

            Box {
                TextButton(onClick = { showAddMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add card")
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("System card") },
                        onClick = { showAddMenu = false; addCard(CardRole.SYSTEM) }
                    )
                    DropdownMenuItem(
                        text = { Text("Free card (you choose the role)") },
                        onClick = { showAddMenu = false; addCard(CardRole.FREE) }
                    )
                    DropdownMenuItem(
                        text = { Text("Directory Tree") },
                        onClick = { showAddMenu = false; addCard(CardRole.DIRECTORY_TREE) }
                    )
                    if (!hasTools) {
                        DropdownMenuItem(
                            text = { Text("Tools (names and descriptions)") },
                            onClick = { showAddMenu = false; addCard(CardRole.TOOLS) }
                        )
                    }
                    if (!hasMemory) {
                        DropdownMenuItem(
                            text = { Text("Memory") },
                            onClick = { showAddMenu = false; addCard(CardRole.MEMORY) }
                        )
                    }
                }
            }
            TextButton(onClick = { scope.launch { previewText = makePreview() } }) {
                Text("Preview the next request")
            }
            TextButton(onClick = { showReset = true }) { Text("Reset to default cards") }
            Spacer(Modifier.height(24.dp))
        }
    }

    deletingId?.let { id ->
        val name = layout.cards.firstOrNull { it.id == id }?.displayName() ?: "this card"
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Delete card?") },
            text = { Text("\"$name\" will be removed from the request.") },
            confirmButton = {
                TextButton(onClick = {
                    onLayoutChange(RequestLayout(layout.cards.filterNot { it.id == id }))
                    if (editingId == id) editingId = null
                    deletingId = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } }
        )
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset cards?") },
            text = { Text("All cards are replaced by the default ones: Metadata, System, History and Last turn.") },
            confirmButton = {
                TextButton(onClick = { showReset = false; editingId = null; onReset() }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } }
        )
    }

    previewText?.let { text ->
        AlertDialog(
            onDismissRequest = { previewText = null },
            title = { Text("Next request") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { previewText = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun PanelCard(
    card: RequestCard,
    settings: AppSettings,
    hasWorkspace: Boolean,
    historyTurnCount: Int,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onChange: (RequestCard) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onPickFile: () -> Unit,
    onPickMemoryFile: () -> Unit,
    onCreateTemplate: (String) -> Unit,
    onOpenHistoryManager: () -> Unit,
    checkMemory: suspend () -> String?,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit
) {
    val fixed = card.role in CardRole.FIXED
    val canEdit = card.role != CardRole.METADATA && card.role != CardRole.LAST_TURN
    SectionCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.displayName(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        summaryOf(card, settings, hasWorkspace, historyTurnCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                if (!fixed) {
                    Switch(checked = card.enabled, onCheckedChange = { onChange(card.copy(enabled = it)) })
                }
            }

            if (card.role == CardRole.METADATA) {
                MetadataControls(settings, onSettingsChange)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onMove(-1) }) {
                        Icon(Icons.Default.ExpandLess, contentDescription = "Move up")
                    }
                    IconButton(onClick = { onMove(1) }) {
                        Icon(Icons.Default.ExpandMore, contentDescription = "Move down")
                    }
                    Spacer(Modifier.weight(1f))
                    if (canEdit) {
                        IconButton(onClick = onToggleEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    }
                    if (!fixed) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }

            if (editing && canEdit) {
                Spacer(Modifier.height(8.dp))
                when (card.role) {
                    CardRole.DIRECTORY_TREE -> TreeEditor(card, hasWorkspace, onChange)
                    CardRole.HISTORY -> HistoryEditor(card, onChange, onOpenHistoryManager)
                    CardRole.MEMORY -> MemoryEditor(card, historyTurnCount, onChange, onPickMemoryFile, onCreateTemplate, checkMemory)
                    CardRole.TOOLS -> TextCardEditor(
                        card = card,
                        showTitle = false,
                        hint = "One block per tool: a line \"## tool_id\", then \"name:\" and \"description:\". " +
                            "The name and description are what the model sees. The tool_id is internal.",
                        templateKind = "tools",
                        hasWorkspace = hasWorkspace,
                        onChange = onChange,
                        onPickFile = onPickFile,
                        onCreateTemplate = onCreateTemplate
                    )
                    else -> TextCardEditor(
                        card = card,
                        showTitle = true,
                        hint = null,
                        templateKind = "system",
                        hasWorkspace = hasWorkspace,
                        onChange = onChange,
                        onPickFile = onPickFile,
                        onCreateTemplate = onCreateTemplate
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataControls(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit
) {
    var temperature by remember { mutableStateOf(settings.temperature) }
    Spacer(Modifier.height(6.dp))
    Text("Temperature: ${"%.2f".format(temperature)}", style = MaterialTheme.typography.labelLarge)
    Slider(
        value = temperature,
        onValueChange = {
            temperature = it
            onSettingsChange { current -> current.copy(temperature = it) }
        },
        valueRange = 0f..2f,
        steps = 19
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Streaming", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Used when no tools run. The model and provider are chosen with the model picker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = settings.stream, onCheckedChange = { value -> onSettingsChange { it.copy(stream = value) } })
    }
}

@Composable
private fun TreeEditor(card: RequestCard, hasWorkspace: Boolean, onChange: (RequestCard) -> Unit) {
    var roleLabel by remember(card.id) { mutableStateOf(card.roleLabel) }
    UnderlinedField(
        value = roleLabel,
        onValueChange = { roleLabel = it; onChange(card.copy(roleLabel = it)) },
        label = "Role sent to the model"
    )
    Text(
        "If your provider rejects this role name with an error, change it to system.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Text("Maximum scan depth: ${card.treeDepth}", style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = card.treeDepth.toFloat(),
        onValueChange = { onChange(card.copy(treeDepth = it.toInt().coerceIn(1, 8))) },
        valueRange = 1f..8f,
        steps = 6
    )
    Text(
        "Adds a tree of the workspace folder. A tree marked verified=false may be incomplete.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!hasWorkspace) {
        Text(
            "Choose a workspace folder in Settings first, or sending will stop with an error.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun HistoryEditor(card: RequestCard, onChange: (RequestCard) -> Unit, onOpenHistoryManager: () -> Unit) {
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
    TextButton(onClick = onOpenHistoryManager) { Text("Manage messages of this chat") }
}

@Composable
private fun MemoryEditor(
    card: RequestCard,
    historyTurnCount: Int,
    onChange: (RequestCard) -> Unit,
    onPickMemoryFile: () -> Unit,
    onCreateTemplate: (String) -> Unit,
    checkMemory: suspend () -> String?
) {
    var warning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(card.fileUri, historyTurnCount) { warning = checkMemory() }

    Text("Role sent to the model", style = MaterialTheme.typography.bodyLarge)
    SourceRadio("system", card.roleLabel.trim().lowercase() != "user") { onChange(card.copy(roleLabel = "system")) }
    SourceRadio("user", card.roleLabel.trim().lowercase() == "user") { onChange(card.copy(roleLabel = "user")) }
    Spacer(Modifier.height(4.dp))
    Text(
        card.fileName.ifBlank { "No Memory file selected" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    TextButton(onClick = onPickMemoryFile) {
        Text(if (card.fileUri.isBlank()) "Choose Memory file" else "Change Memory file")
    }
    TextButton(onClick = { onCreateTemplate("memory") }) { Text("Create a template file") }
    Text(
        "The model updates this file itself at the end of its answers. " +
            "You can edit the file by hand between messages. Some providers may refuse a system message after the History; try user if you get an error.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    warning?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TextCardEditor(
    card: RequestCard,
    showTitle: Boolean,
    hint: String?,
    templateKind: String,
    hasWorkspace: Boolean,
    onChange: (RequestCard) -> Unit,
    onPickFile: () -> Unit,
    onCreateTemplate: (String) -> Unit
) {
    var title by remember(card.id) { mutableStateOf(card.title) }
    var roleLabel by remember(card.id) { mutableStateOf(card.roleLabel) }
    var text by remember(card.id) { mutableStateOf(card.text) }

    if (showTitle) {
        UnderlinedField(
            value = title,
            onValueChange = { title = it; onChange(card.copy(title = it)) },
            label = "Name"
        )
        Spacer(Modifier.height(8.dp))
    }
    if (card.role == CardRole.FREE) {
        UnderlinedField(
            value = roleLabel,
            onValueChange = { roleLabel = it; onChange(card.copy(roleLabel = it)) },
            label = "Role sent to the model (for example system or user)"
        )
        Spacer(Modifier.height(8.dp))
    }
    hint?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (card.role == CardRole.TOOLS && !hasWorkspace) {
            Text(
                "Choose a workspace folder in Settings first, or sending will stop with an error.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(4.dp))
    }
    SourceRadio("Text input", card.source == "text") { onChange(card.copy(source = "text")) }
    SourceRadio("External file", card.source == "file") { onChange(card.copy(source = "file")) }
    if (card.source == "text") {
        UnderlinedField(
            value = text,
            onValueChange = { text = it; onChange(card.copy(text = it)) },
            label = "Text",
            minLines = 3,
            maxLines = 14
        )
    } else {
        Text(
            card.fileName.ifBlank { "No file selected" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onPickFile) {
            Text(if (card.fileUri.isBlank()) "Choose file" else "Change file")
        }
        TextButton(onClick = { onCreateTemplate(templateKind) }) { Text("Create a template file") }
        Text(
            "The file is read again every time you send a message.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SourceRadio(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun summaryOf(
    card: RequestCard,
    settings: AppSettings,
    hasWorkspace: Boolean,
    historyTurnCount: Int
): String = when (card.role) {
    CardRole.METADATA -> "Request settings · temperature ${"%.2f".format(settings.temperature)} · streaming ${if (settings.stream) "on" else "off"}"
    CardRole.SYSTEM, CardRole.FREE -> {
        val rolePart = if (card.role == CardRole.FREE) "Role: ${card.roleLabel} · " else ""
        val body = if (card.source == "file") {
            "File: " + card.fileName.ifBlank { "none selected" }
        } else {
            card.text.take(70).replace("\n", " ").ifBlank { "(empty)" }
        }
        rolePart + body
    }
    CardRole.DIRECTORY_TREE ->
        "Role: ${card.roleLabel.ifBlank { DIRECTORY_TREE_ROLE }} · depth ${card.treeDepth}" +
            if (hasWorkspace) "" else " · no workspace selected"
    CardRole.TOOLS -> {
        val body = if (card.source == "file") "File: " + card.fileName.ifBlank { "none selected" } else "Text"
        "Not a message: the tool list sent with the request · $body"
    }
    CardRole.MEMORY ->
        "Role: ${if (card.roleLabel.trim().equals("user", ignoreCase = true)) "user" else "system"} · " +
            "File: " + card.fileName.ifBlank { "none selected" }
    CardRole.HISTORY -> "$historyTurnCount finished turn(s) · " +
        (if (card.historyCompact) "short notes for old tool results" else "full old tool results") +
        (if (card.turnIdEnabled) " · turns numbered" else "")
    CardRole.LAST_TURN -> "The message you are sending now, added automatically"
    else -> card.role
}
