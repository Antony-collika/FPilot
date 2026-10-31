package com.miniichat.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichat.files.SafFileRepository

@Composable
fun WorkspaceBrowserScreen(
    onBack: () -> Unit,
    onList: (String, (Result<List<SafFileRepository.Entry>>) -> Unit) -> Unit,
    onRead: (String, (Result<String>) -> Unit) -> Unit,
    onSave: (String, String, String, (Result<Unit>) -> Unit) -> Unit
) {
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<SafFileRepository.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var editorPath by remember { mutableStateOf<String?>(null) }
    var originalContent by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSaveConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(currentPath, editorPath) {
        if (editorPath == null) {
            loading = true
            error = null
            onList(currentPath) { result ->
                result.onSuccess { entries = it }
                    .onFailure { error = it.message ?: "Could not list this folder." }
                loading = false
            }
        }
    }

    fun openDocument(path: String) {
        loading = true
        error = null
        onRead(path) { result ->
            result.onSuccess {
                editorPath = path
                originalContent = it
                draft = it
            }.onFailure {
                error = it.message ?: "Could not read this document."
            }
            loading = false
        }
    }

    fun leaveEditor() {
        editorPath = null
        originalContent = ""
        draft = ""
        error = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (editorPath != null) leaveEditor() else onBack()
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(if (editorPath == null) "Workspace" else editorPath!!.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    if (editorPath == null) currentPath.ifBlank { "Selected folder root" } else editorPath!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        if (editorPath == null) {
            if (currentPath.isNotBlank()) {
                TextButton(
                    onClick = { currentPath = currentPath.substringBeforeLast('/', "") },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { Text("Parent folder") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("This folder is empty, or it contains no items available to FPilot.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(entries, key = { it.relativePath }) { entry ->
                        val markdown = entry.name.endsWith(".md", true) || entry.name.endsWith(".markdown", true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = entry.isDirectory || markdown) {
                                    if (entry.isDirectory) currentPath = entry.relativePath
                                    else openDocument(entry.relativePath)
                                }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (entry.isDirectory) "Folder" else
                                        entry.sizeBytes?.let { "$it bytes" } ?: "Markdown document",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!entry.isDirectory && !markdown) {
                                Text("Not editable", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (draft == originalContent) "No unsaved changes" else "Unsaved changes",
                    color = if (draft == originalContent) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelMedium
                )
                Button(
                    onClick = { showSaveConfirmation = true },
                    enabled = !saving && draft != originalContent
                ) { Text("Save") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                )
            }
        }
    }

    if (showSaveConfirmation) {
        AlertDialog(
            onDismissRequest = { showSaveConfirmation = false },
            title = { Text("Save workspace document?") },
            text = {
                Text(
                    "Replace the current contents of ${editorPath.orEmpty()} with the edited text? " +
                        "FPilot will re-read the document first and reject the save if it changed since it was opened. " +
                        "This direct edit is a user action and does not require enabling agent write tools."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val path = editorPath ?: return@TextButton
                    showSaveConfirmation = false
                    saving = true
                    onSave(path, originalContent, draft) { result ->
                        result.onSuccess {
                            originalContent = draft
                            error = null
                        }.onFailure {
                            error = it.message ?: "Could not save this document."
                        }
                        saving = false
                    }
                }) { Text(if (saving) "Saving…" else "Save document") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}
