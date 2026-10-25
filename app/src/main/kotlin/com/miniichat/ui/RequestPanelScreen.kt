package com.miniichat.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.miniichat.data.RequestLayout
import com.miniichat.data.displayName
import com.miniichat.data.insertionIndexFor
import com.miniichat.data.moved
import com.miniichat.util.newId

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
    onReset: () -> Unit
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun addCard(role: String) {
        val card = RequestCard(
            id = newId(),
            role = role,
            title = if (role == CardRole.FREE) "Free card" else "",
            roleLabel = if (role == CardRole.FREE) "user" else "system"
        )
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
                        layout = layout,
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
                        onSettingsChange = onSettingsChange
                    )
                }
            }

            androidx.compose.foundation.layout.Box {
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
                }
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
}

@Composable
private fun PanelCard(
    card: RequestCard,
    layout: RequestLayout,
    settings: AppSettings,
    hasWorkspace: Boolean,
    historyTurnCount: Int,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onChange: (RequestCard) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onPickFile: () -> Unit,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit
) {
    val fixed = card.role in CardRole.FIXED
    val canEdit = card.role == CardRole.SYSTEM || card.role == CardRole.FREE || card.role == CardRole.DIRECTORY_TREE
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
                CardEditor(card, hasWorkspace, onChange, onPickFile)
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
                "Used when the Tools are off. The model and provider are chosen with the model picker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = settings.stream, onCheckedChange = { value -> onSettingsChange { it.copy(stream = value) } })
    }
}

@Composable
private fun CardEditor(
    card: RequestCard,
    hasWorkspace: Boolean,
    onChange: (RequestCard) -> Unit,
    onPickFile: () -> Unit
) {
    Spacer(Modifier.height(8.dp))
    if (card.role == CardRole.DIRECTORY_TREE) {
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
        return
    }

    var title by remember(card.id) { mutableStateOf(card.title) }
    var roleLabel by remember(card.id) { mutableStateOf(card.roleLabel) }
    var text by remember(card.id) { mutableStateOf(card.text) }

    OutlinedTextField(
        value = title,
        onValueChange = { title = it; onChange(card.copy(title = it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Name") }
    )
    if (card.role == CardRole.FREE) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = roleLabel,
            onValueChange = { roleLabel = it; onChange(card.copy(roleLabel = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Role sent to the model (for example system or user)") }
        )
    }
    Spacer(Modifier.height(8.dp))
    SourceRadio("Text input", card.source == "text") { onChange(card.copy(source = "text")) }
    SourceRadio("External file", card.source == "file") { onChange(card.copy(source = "file")) }
    if (card.source == "text") {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onChange(card.copy(text = it)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 12,
            label = { Text("Text") }
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
        Text(
            "The file is read again every time you send a message.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
        "Workspace folder tree · depth ${card.treeDepth}" + if (hasWorkspace) "" else " · no workspace selected"
    CardRole.HISTORY -> "$historyTurnCount finished turn(s) of this chat, added automatically"
    CardRole.LAST_TURN -> "The message you are sending now, added automatically"
    else -> card.role
}
