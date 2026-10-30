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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.miniichat.data.Conversation
import com.miniichat.data.Message
import com.miniichat.data.WorkingFile

private class TurnGroup(val user: Message?, val others: List<Message>)

private class EditRequest(val title: String, val initial: String, val onSave: (String, (Boolean) -> Unit) -> Unit)

private class ConfirmRequest(val title: String, val text: String, val onConfirm: () -> Unit)

private fun groupTurns(messages: List<Message>): List<TurnGroup> {
    val groups = mutableListOf<TurnGroup>()
    var user: Message? = null
    var others = mutableListOf<Message>()
    var started = false
    for (message in messages) {
        if (message.role == "user") {
            if (started) groups += TurnGroup(user, others)
            user = message
            others = mutableListOf()
            started = true
        } else {
            started = true
            others.add(message)
        }
    }
    if (started) groups += TurnGroup(user, others)
    return groups
}

/**
 * Lists the turns of the open chat with their numbers. Every message, and every tool round
 * the model made, can be edited or deleted here. Tool calls and their results are always
 * deleted together.
 */
@Composable
fun HistoryManagerScreen(
    conversation: Conversation?,
    isBusy: Boolean,
    onBack: () -> Unit,
    onEditMessage: (String, String, (Boolean) -> Unit) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onDeleteTurn: (String) -> Unit,
    onDeleteToolRound: (String, Int) -> Unit,
    onEditToolResult: (String, Int, Int, String, (Boolean) -> Unit) -> Unit,
    workingFiles: List<WorkingFile> = emptyList(),
    onOpenFile: (String) -> Unit = {}
) {
    val turns = remember(conversation) { groupTurns(conversation?.messages.orEmpty()) }
    var editing by remember { mutableStateOf<EditRequest?>(null) }
    var confirming by remember { mutableStateOf<ConfirmRequest?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(title = "Messages of this chat", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Files", style = MaterialTheme.typography.titleMedium)
                        val attachments = conversation?.messages.orEmpty().flatMap { message ->
                            message.attachments.map { attachment -> Triple(attachment, message, "Attachment") }
                        }
                        if (attachments.isNotEmpty()) {
                            Text("User attachments", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            attachments.forEach { (attachment, message, _) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(attachment.name.ifBlank { "Unnamed file" }, style = MaterialTheme.typography.bodyMedium)
                                        Text("Attached in message: ${message.id.take(8)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { onOpenFile(attachment.uri) }) { Text("Open") }
                                }
                            }
                        }
                        if (workingFiles.isNotEmpty()) {
                            Text("Workspace files used by the model", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            workingFiles.forEach { file ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(file.path, style = MaterialTheme.typography.bodyMedium)
                                        Text("${file.operation} · Message: ${file.messageId.take(8)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { onOpenFile(file.uri) }) { Text("Open") }
                                }
                            }
                        }
                        if (attachments.isEmpty() && workingFiles.isEmpty()) {
                            Text("No working files are associated with this chat.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
                    }
                }
            }
            item {
                Text(
                    "Editing or deleting here changes what the model sees from that point on, " +
                        "so the provider reads the chat again from there (its cache restarts at that message). " +
                        "Numbers are never reused: deleting a turn does not renumber the others.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isBusy) {
                    Text(
                        "An answer is being written. Editing is paused until it is finished.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (turns.isEmpty()) {
                    Text("This chat has no messages yet.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(turns) { turn ->
                val label = turn.user?.turnNo?.let { "T$it" } ?: "Turn"
                SectionCard {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            val userId = turn.user?.id
                            if (userId != null) {
                                TextButton(
                                    enabled = !isBusy,
                                    onClick = {
                                        confirming = ConfirmRequest(
                                            "Delete $label?",
                                            "The question, the tool rounds and the answer of this turn are removed."
                                        ) { onDeleteTurn(userId) }
                                    }
                                ) { Text("Delete turn") }
                            }
                        }
                        turn.user?.let { user ->
                            MessageRow(
                                label = "You",
                                text = user.content.ifBlank { "(attachment only)" },
                                enabled = !isBusy,
                                onEdit = {
                                    editing = EditRequest("Edit your message", user.content) { value, done -> onEditMessage(user.id, value, done) }
                                },
                                onDelete = {
                                    confirming = ConfirmRequest(
                                        "Delete this message?",
                                        "The turn stays without its question."
                                    ) { onDeleteMessage(user.id) }
                                }
                            )
                        }
                        turn.others.forEach { message ->
                            message.toolRounds.forEachIndexed { roundIndex, round ->
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Tool round ${roundIndex + 1}",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        enabled = !isBusy,
                                        onClick = {
                                            confirming = ConfirmRequest(
                                                "Delete this tool round?",
                                                "The tool calls and their results are removed together."
                                            ) { onDeleteToolRound(message.id, roundIndex) }
                                        }
                                    ) { Icon(Icons.Default.Delete, contentDescription = "Delete tool round") }
                                }
                                round.toolCalls.forEach { call ->
                                    Text(
                                        call.name + " " + call.arguments.take(80),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                round.results.forEachIndexed { resultIndex, result ->
                                    MessageRow(
                                        label = "Result of ${result.name}" + if (result.compact != null) " (sent as a short note)" else "",
                                        text = result.content,
                                        enabled = !isBusy,
                                        onEdit = {
                                            editing = EditRequest("Edit the tool result", result.content) { value, done ->
                                                onEditToolResult(message.id, roundIndex, resultIndex, value, done)
                                            }
                                        },
                                        onDelete = null
                                    )
                                }
                            }
                            MessageRow(
                                label = if (message.role == "assistant") "Assistant" else message.role,
                                text = message.content.ifBlank { "(empty)" },
                                enabled = !isBusy,
                                onEdit = {
                                    editing = EditRequest("Edit the answer", message.content) { value, done -> onEditMessage(message.id, value, done) }
                                },
                                onDelete = {
                                    confirming = ConfirmRequest(
                                        "Delete this answer?",
                                        "Its tool rounds are removed with it."
                                    ) { onDeleteMessage(message.id) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { request ->
        var draft by remember(request) { mutableStateOf(request.initial) }
        var saving by remember(request) { mutableStateOf(false) }
        var saveFailed by remember(request) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(request.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnderlinedField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = "Text",
                    minLines = 3,
                    maxLines = 12
                    )
                    if (saveFailed) Text("Could not save this edit. Your text is still here; check the Storage file and try again.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    saveFailed = false
                    request.onSave(draft) { success ->
                        saving = false
                        if (success) editing = null else saveFailed = true
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }

    confirming?.let { request ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(request.title) },
            text = { Text(request.text + " The provider's cache restarts from here.") },
            confirmButton = {
                TextButton(onClick = { request.onConfirm(); confirming = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MessageRow(
    label: String,
    text: String,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
        }
        IconButton(enabled = enabled, onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit")
        }
        if (onDelete != null) {
            IconButton(enabled = enabled, onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}
