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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

private const val RECOMMENDED_ORDER =
    "Recommended order (things that rarely change first):\n" +
        "  Metadata        fixed at the top, not a message\n" +
        "  System\n" +
        "  Tools           not a message: its position changes nothing\n" +
        "  History\n" +
        "  Directory Tree  changes often\n" +
        "  Memory          changes often\n" +
        "  Last turn       your new message"

/**
 * The Request Control Panel: the list of cards that make up every request, from top to bottom.
 * Each card shows only a name, a short line, and buttons. Editing opens its own screen.
 */
@Composable
fun RequestPanelScreen(
    layout: RequestLayout,
    settings: AppSettings,
    hasWorkspace: Boolean,
    historyTurnCount: Int,
    onBack: () -> Unit,
    onLayoutChange: (RequestLayout) -> Unit,
    onAddCard: (RequestLayout, RequestCard) -> Unit,
    onEditCard: (String) -> Unit,
    makePreview: suspend () -> String,
    onReset: () -> Unit
) {
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
            CardRole.MEMORY -> RequestCard(id = newId(), role = role, roleLabel = "user")
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
        onAddCard(RequestLayout(list), card)
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Every request is built from these cards, from top to bottom, and each card goes exactly where it is here " +
                    "(the Tools card is the one exception). History must stay above Last turn.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                RECOMMENDED_ORDER,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
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
                        onToggle = { enabled ->
                            notice = null
                            onLayoutChange(RequestLayout(layout.cards.map { if (it.id == card.id) it.copy(enabled = enabled) else it }))
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
                        onEdit = { onEditCard(card.id) },
                        onDelete = { deletingId = card.id }
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
                    DropdownMenuItem(text = { Text("System card") }, onClick = { showAddMenu = false; addCard(CardRole.SYSTEM) })
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
                        DropdownMenuItem(text = { Text("Memory") }, onClick = { showAddMenu = false; addCard(CardRole.MEMORY) })
                    }
                }
            }
            TextButton(onClick = { scope.launch { previewText = makePreview() } }) { Text("Preview the next request") }
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
            confirmButton = { TextButton(onClick = { showReset = false; onReset() }) { Text("Reset") } },
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
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val fixed = card.role in CardRole.FIXED
    SectionCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(card.displayName(), style = MaterialTheme.typography.titleMedium)
            Text(
                summaryOf(card, settings, hasWorkspace, historyTurnCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.role != CardRole.METADATA) {
                    IconButton(onClick = { onMove(-1) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.ExpandLess, contentDescription = "Move up")
                    }
                    IconButton(onClick = { onMove(1) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.ExpandMore, contentDescription = "Move down")
                    }
                }
                if (!fixed) {
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = card.enabled, onCheckedChange = onToggle)
                }
                Spacer(Modifier.weight(1f))
                if (card.role != CardRole.LAST_TURN) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                }
                if (!fixed) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }
}

private fun summaryOf(
    card: RequestCard,
    settings: AppSettings,
    hasWorkspace: Boolean,
    historyTurnCount: Int
): String = when (card.role) {
    CardRole.METADATA ->
        "${settings.activeModel.ifBlank { "no model" }} · temperature ${"%.2f".format(settings.temperature)} · " +
            "streaming ${if (settings.stream) "on" else "off"}"
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
        "Role: ${if (card.roleLabel.trim().equals("system", ignoreCase = true)) "system" else "user"} · " +
            "File: " + card.fileName.ifBlank { "none selected" }
    CardRole.HISTORY -> "$historyTurnCount finished turn(s) · " +
        (if (card.historyCompact) "short notes for old tool results" else "full old tool results") +
        (if (card.turnIdEnabled) " · turns numbered" else "")
    CardRole.LAST_TURN -> "The message you are sending now, added automatically"
    else -> card.role
}
