package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.miniichat.ChatViewModel
import com.miniichat.R
import com.miniichat.data.ProviderConfig
import kotlinx.coroutines.launch

private enum class Screen { Chat, Settings, Providers, ProviderEdit, RequestLogs, RequestPanel, CardEdit, HistoryManager }

/** Like the normal file chooser, but also asks for permission to write (needed by the Memory file). */
private class OpenDocumentReadWrite : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return super.createIntent(context, input).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: ChatViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshActiveHistory()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val workspacePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.setWorkspace(uri.toString())
        }
    }

    val storagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.updateSettings { it.copy(storageTreeUri = uri.toString()) }
        }
    }

    val systemPromptFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull().orEmpty().ifBlank { "Selected file" }
            vm.updateSettings { it.copy(systemPromptFileUri = uri.toString(), systemPromptFileName = name) }
        }
    }

    val skillsFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull().orEmpty().ifBlank { "Selected file" }
            vm.updateSettings { it.copy(skillsFileUri = uri.toString(), skillsFileName = name) }
        }
    }

    // The card whose file is being chosen, the card being edited, and the file just chosen for it.
    var pickingCardId by remember { mutableStateOf<String?>(null) }
    var editingCardId by remember { mutableStateOf<String?>(null) }
    var pendingNewCard by remember { mutableStateOf<com.miniichat.data.RequestCard?>(null) }
    var pickedFile by remember { mutableStateOf<PickedFile?>(null) }
    val cardFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val cardId = pickingCardId
        if (uri != null && cardId != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull().orEmpty().ifBlank { "Selected file" }
            pickedFile = PickedFile(cardId, uri.toString(), name)
        }
        pickingCardId = null
    }

    val memoryFilePicker = rememberLauncherForActivityResult(
        contract = remember { OpenDocumentReadWrite() }
    ) { uri ->
        val cardId = pickingCardId
        if (uri != null && cardId != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull().orEmpty().ifBlank { "Selected file" }
            pickedFile = PickedFile(cardId, uri.toString(), name)
        }
        pickingCardId = null
    }

    // The kind of template file being created ("tools", "memory" or "system").
    var pendingTemplateKind by remember { mutableStateOf<String?>(null) }
    val templateCreator = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val kind = pendingTemplateKind
        if (uri != null && kind != null) vm.createTemplate(kind, uri.toString())
        pendingTemplateKind = null
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }

    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    androidx.activity.compose.BackHandler(enabled = showModelPicker) {
        showModelPicker = false
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Settings) {
        screen = Screen.Chat
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Providers) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RequestPanel) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.HistoryManager) {
        screen = Screen.RequestPanel
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.ProviderEdit) {
        screen = Screen.Providers
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RequestLogs) {
        screen = Screen.Chat
    }

    val conversations by vm.conversations.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val settings by vm.settings.collectAsState()
    val providers by vm.providers.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val streamingOverlay by vm.streamingOverlay.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val fetchingId by vm.fetchingModelsFor.collectAsState()
    val pendingToolApproval by vm.pendingToolApproval.collectAsState()
    val pendingLargeFileRead by vm.pendingLargeFileRead.collectAsState()
    val llmRequestLogs by vm.llmRequestLogs.collectAsState()
    val requestLayout by vm.requestLayout.collectAsState()

    val activeConv = conversations.firstOrNull { it.id == activeId }
    val activeProvider = providers.firstOrNull { it.id == settings.activeProviderId }
    val workspaceName = remember(settings.workspaceTreeUri) {
        if (settings.workspaceTreeUri.isBlank()) {
            ""
        } else {
            runCatching {
                val treeUri = Uri.parse(settings.workspaceTreeUri)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
                context.contentResolver.query(
                    documentUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull().orEmpty().ifBlank { "Selected folder" }
        }
    }

    val sessionMemoryEnabled =
        (activeConv?.sessionId != null && !activeConv.sessionStorageUri.isNullOrBlank()) ||
            (settings.storageTreeUri.isNotBlank() &&
                (activeConv == null || activeConv.messages.isEmpty()))

    val storageName = remember(settings.storageTreeUri) {
        if (settings.storageTreeUri.isBlank()) {
            ""
        } else {
            runCatching {
                val treeUri = Uri.parse(settings.storageTreeUri)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
                context.contentResolver.query(
                    documentUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull().orEmpty().ifBlank { "Selected folder" }
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.clearToast()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (screen) {
            Screen.RequestLogs -> {
                RequestLogScreen(
                    logs = llmRequestLogs,
                    onBack = { screen = Screen.Chat },
                    onClearLogs = { vm.clearLlmRequestLogs() }
                )
            }
            Screen.Chat -> {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        GlassDrawer(
                            conversations = conversations,
                            activeId = activeId,
                            onSelect = { id ->
                                vm.selectConversation(id)
                                scope.launch { drawerState.close() }
                            },
                            onNew = {
                                vm.newConversation()
                                scope.launch { drawerState.close() }
                            },
                            onDelete = { vm.deleteConversation(it) },
                            onRename = { id, t -> vm.renameConversation(id, t) },
                            onOpenSettings = {
                                screen = Screen.Settings
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                ) {
                    ChatScreen(
                        conversation = activeConv,
                        settings = settings,
                        activeProvider = activeProvider,
                        isStreaming = isStreaming,
                        streamingOverlay = streamingOverlay,
                        onMenu = { scope.launch { drawerState.open() } },
                        onSend = { text, atts -> vm.sendMessage(text, atts) },
                        onStop = { vm.stopStreaming() },
                        onRegenerate = { vm.regenerate() },
                        onRegenerateFrom = { msgId -> vm.regenerateFrom(msgId) },
                        onDeleteMessage = { msgId -> vm.deleteMessage(msgId) },
                        onEditMessage = { msgId, newText -> vm.editMessage(msgId, newText) },
                        onNew = { vm.newConversation() },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenRequestLogs = { screen = Screen.RequestLogs },
                        hasFileSession = activeConv?.sessionId != null,
                        onRepairHistory = { vm.repairActiveHistory() },
                        onRepairMemory = { vm.repairActiveMemory() },
                        onCreateHistoryTemplate = { vm.createActiveHistoryTemplate() },
                        onPickModel = {
                            if (providers.isEmpty()) {
                                editingProvider = null
                                screen = Screen.ProviderEdit
                            } else showModelPicker = true
                        }
                    )
                }
            }
            Screen.Settings -> {
                SettingsScreen(
                    settings = settings,
                    providers = providers,
                    workspaceTreeUri = settings.workspaceTreeUri,
                    workspaceName = workspaceName,
                    storageTreeUri = settings.storageTreeUri,
                    storageName = storageName,
                    onBack = { screen = Screen.Chat },
                    onChange = { vm.updateSettings(it) },
                    onChooseWorkspace = { workspacePicker.launch(null) },
                    onClearWorkspace = { vm.setWorkspace(null) },
                    onChooseStorage = { storagePicker.launch(null) },
                    onClearStorage = { vm.updateSettings { it.copy(storageTreeUri = "") } },
                    onOpenProviders = { screen = Screen.Providers },
                    onChooseSystemPromptFile = { systemPromptFilePicker.launch(arrayOf("text/*", "application/octet-stream", "application/markdown", "application/x-markdown")) },
                    onChooseSkillsFile = { skillsFilePicker.launch(arrayOf("text/*", "application/octet-stream", "application/markdown", "application/x-markdown")) },
                    onOpenRequestPanel = { screen = Screen.RequestPanel }
                )
            }
            Screen.RequestPanel -> {
                RequestPanelScreen(
                    layout = requestLayout,
                    settings = settings,
                    hasWorkspace = settings.workspaceTreeUri.isNotBlank(),
                    historyTurnCount = activeConv?.messages?.count { it.role == "user" } ?: 0,
                    onBack = { screen = Screen.Settings },
                    onLayoutChange = { vm.saveLayout(it) },
                    onAddCard = { newLayout, newCard ->
                        pendingNewCard = newCard
                        editingCardId = newCard.id
                        vm.saveLayout(newLayout)
                        screen = Screen.CardEdit
                    },
                    onEditCard = { cardId ->
                        pendingNewCard = null
                        editingCardId = cardId
                        screen = Screen.CardEdit
                    },
                    makePreview = { vm.previewRequest() },
                    onReset = { vm.resetLayout() }
                )
            }
            Screen.CardEdit -> {
                val editingCard = requestLayout.cards.firstOrNull { it.id == editingCardId }
                    ?: pendingNewCard?.takeIf { it.id == editingCardId }
                if (editingCard == null) {
                    androidx.activity.compose.BackHandler(enabled = true) { screen = Screen.RequestPanel }
                } else {
                    CardEditScreen(
                        card = editingCard,
                        settings = settings,
                        hasWorkspace = settings.workspaceTreeUri.isNotBlank(),
                        sessionMemoryEnabled = sessionMemoryEnabled,
                        historyTurnCount = activeConv?.messages?.count { it.role == "user" } ?: 0,
                        pickedFile = pickedFile,
                        onPickedFileConsumed = { pickedFile = null },
                        onSaveCard = { updated ->
                            vm.saveLayout(
                                com.miniichat.data.RequestLayout(
                                    requestLayout.cards.map { if (it.id == updated.id) updated else it }
                                )
                            )
                        },
                        onSaveSettings = { vm.updateSettings(it) },
                        onClose = {
                            pendingNewCard = null
                            screen = Screen.RequestPanel
                        },
                        onPickFile = { cardId ->
                            pickingCardId = cardId
                            cardFilePicker.launch(arrayOf("text/*", "application/octet-stream", "application/markdown", "application/x-markdown"))
                        },
                        onPickMemoryFile = { cardId ->
                            pickingCardId = cardId
                            memoryFilePicker.launch(arrayOf("text/*", "application/octet-stream", "application/markdown", "application/x-markdown"))
                        },
                        onCreateTemplate = { kind ->
                            pendingTemplateKind = kind
                            templateCreator.launch("$kind.md")
                        },
                        onOpenHistoryManager = { screen = Screen.HistoryManager },
                        checkMemory = { vm.memoryReferenceWarning() }
                    )
                }
            }
            Screen.HistoryManager -> {
                HistoryManagerScreen(
                    conversation = activeConv,
                    isBusy = isStreaming,
                    onBack = { screen = Screen.RequestPanel },
                    onEditMessage = { id, text -> vm.editMessage(id, text) },
                    onDeleteMessage = { id -> vm.deleteMessage(id) },
                    onDeleteTurn = { id -> vm.deleteTurn(id) },
                    onDeleteToolRound = { id, index -> vm.deleteToolRound(id, index) },
                    onEditToolResult = { id, round, result, text -> vm.editToolResult(id, round, result, text) }
                )
            }
            Screen.Providers -> {
                ProvidersScreen(
                    providers = providers,
                    fetchingId = fetchingId,
                    activeProviderId = settings.activeProviderId,
                    activeModel = settings.activeModel,
                    onBack = { screen = Screen.Settings },
                    onCreate = {
                        editingProvider = null
                        screen = Screen.ProviderEdit
                    },
                    onEdit = { p ->
                        editingProvider = p
                        screen = Screen.ProviderEdit
                    },
                    onDelete = { vm.deleteProvider(it) },
                    onFetchModels = { vm.fetchModels(it) },
                    onAddManualModel = { id, m -> vm.addManualModel(id, m) },
                    onRemoveModel = { id, m -> vm.removeModel(id, m) },
                    onSelectModel = { id, m -> vm.selectModel(id, m) }
                )
            }
            Screen.ProviderEdit -> {
                ProviderEditorScreen(
                    initial = editingProvider,
                    onCancel = { screen = Screen.Providers },
                    onSave = { p ->
                        vm.upsertProvider(p)
                        screen = Screen.Providers
                    }
                )
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.fillMaxSize())
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = providers,
            activeProviderId = settings.activeProviderId,
            activeModel = settings.activeModel,
            onPick = { pid, m -> vm.selectModel(pid, m) },
            onDismiss = { showModelPicker = false }
        )
    }

    pendingLargeFileRead?.let { request ->
        AlertDialog(
            onDismissRequest = { vm.respondToLargeFileRead(false) },
            title = { Text("File too large to read at once") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("The file is larger than the current chunk size.")
                    Text(request.path, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "File size: ${request.sizeBytes?.let { "${it} bytes" } ?: "unknown"}. " +
                            "Max chunk size: ${request.maxChunkBytes / 1000} kB."
                    )
                    Text("The file will be read in chunks. You can change the chunk size in Settings → Tool permissions → Max chunk size.")
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.respondToLargeFileRead(true) }) {
                    Text("Read more")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.respondToLargeFileRead(false) }) {
                    Text("Cancel")
                }
            }
        )
    }

    pendingToolApproval?.let { request ->
        AlertDialog(
            onDismissRequest = { vm.respondToToolApproval(false) },
            title = { Text("Confirm tool call") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Agent wants to execute the ${request.toolName} tool with:")
                    Text(request.path, style = MaterialTheme.typography.titleSmall)
                    Text(request.summary)
                    Text("Allow only if this tool call is expected. Write tools must be enabled in Tool permissions and stay within the allowed write scope.")
                }
            },
            confirmButton = { TextButton(onClick = { vm.respondToToolApproval(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { vm.respondToToolApproval(false) }) { Text("Deny") } }
        )
    }
    error?.let { msg ->
        val needsProviderFix = msg.contains("provider", ignoreCase = true)
            || msg.contains("api key", ignoreCase = true)
            || msg.contains("model", ignoreCase = true)
            || msg.contains("401")
            || msg.contains("403")
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            confirmButton = {
                if (needsProviderFix) {
                    TextButton(onClick = {
                        vm.clearError()
                        screen = Screen.Providers
                    }) { Text(stringResource(R.string.error_open_providers)) }
                } else {
                    TextButton(onClick = { vm.clearError() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = if (needsProviderFix) {
                {
                    TextButton(onClick = { vm.clearError() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            } else null,
            title = { Text(stringResource(R.string.error_dialog_title)) },
            text = { Text(msg) }
        )
    }
}
