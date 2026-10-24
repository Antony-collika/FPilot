package com.miniichat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniichat.api.ChatMessage
import com.miniichat.api.LlmClient
import com.miniichat.api.ToolCall
import com.miniichat.agent.AgentLoop
import com.miniichat.files.MarkdownFileTools
import com.miniichat.files.SafFileRepository
import com.miniichat.data.AppSettings
import com.miniichat.data.Conversation
import com.miniichat.data.ConversationStore
import com.miniichat.data.Message
import com.miniichat.data.ProviderConfig
import com.miniichat.data.ProviderStore
import com.miniichat.data.SettingsRepository
import com.miniichat.data.ToolRound
import com.miniichat.data.ToolRoundSettings
import com.miniichat.data.buildToolRounds
import com.miniichat.data.toolRoundsToChatMessages
import com.miniichat.data.withTurnNumbers
import com.miniichat.util.PromptVars
import com.miniichat.util.newId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ToolApprovalRequest(val toolName: String, val path: String, val summary: String)
data class LargeFileReadRequest(val path: String, val sizeBytes: Long?, val maxChunkBytes: Int)

/** Temporary authorization scoped to one AgentLoop.run() invocation. */
private class ToolPermissionSession {
    var allowOnceGranted: Boolean = false
    /** Large-file approval lasts for this agent run, so continuation chunks do not prompt again. */
    val approvedLargeReadPaths: MutableSet<String> = mutableSetOf()
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConversationStore(app)
    val settingsRepo = SettingsRepository(app)
    val providerStore = ProviderStore(app)
    private val client = LlmClient()
    val llmRequestLogs: StateFlow<String> = client.requestLogs

    fun clearLlmRequestLogs() = client.clearRequestLogs()

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val providers: StateFlow<List<ProviderConfig>> = providerStore.providersFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val conversations: StateFlow<List<Conversation>> = store.conversationsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** In-memory overlay for the message currently being streamed.
     *  Avoids a DataStore write on every token while keeping the UI live. */
    private val _streamingOverlay = MutableStateFlow<Pair<String, String>?>(null)
    val streamingOverlay: StateFlow<Pair<String, String>?> = _streamingOverlay.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _fetchingModelsFor = MutableStateFlow<String?>(null)
    val fetchingModelsFor: StateFlow<String?> = _fetchingModelsFor.asStateFlow()

    private var streamingJob: Job? = null
    private val _pendingToolApproval = MutableStateFlow<ToolApprovalRequest?>(null)
    val pendingToolApproval: StateFlow<ToolApprovalRequest?> = _pendingToolApproval.asStateFlow()
    private var approvalCompletion: CompletableDeferred<Boolean>? = null
    private val _pendingLargeFileRead = MutableStateFlow<LargeFileReadRequest?>(null)
    val pendingLargeFileRead: StateFlow<LargeFileReadRequest?> = _pendingLargeFileRead.asStateFlow()
    private var largeFileReadCompletion: CompletableDeferred<Boolean>? = null

    private suspend fun confirmLargeFileRead(path: String, sizeBytes: Long?, maxChunkBytes: Int): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        largeFileReadCompletion = deferred
        _pendingLargeFileRead.value = LargeFileReadRequest(path, sizeBytes, maxChunkBytes)
        return try {
            deferred.await()
        } finally {
            if (largeFileReadCompletion === deferred) {
                largeFileReadCompletion = null
                _pendingLargeFileRead.value = null
            }
        }
    }

    fun respondToLargeFileRead(approved: Boolean) {
        largeFileReadCompletion?.complete(approved)
    }

    /**
     * Maps the name the model used to the internal tool id (uid).
     * Today the two are identical. Once tool names become editable in the Request
     * Control Panel, only this function has to change, and every safety check below
     * keeps working because it compares the uid, never the name.
     */
    private fun toolUid(name: String): String = name

    private suspend fun confirmToolCall(
        call: ToolCall,
        arguments: JsonObject,
        permissionSession: ToolPermissionSession,
        workspaceRepository: SafFileRepository
    ): Boolean {
        val current = settings.value
        val uid = toolUid(call.name)
        if (uid == "read_file") {
            val path = arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            // Approval applies to this file for the current agent run, including later chunks.
            if (path in permissionSession.approvedLargeReadPaths) return true
            val maxChunkBytes = current.agentMaxFileReadKb * 1_000
            val metadata = try {
                workspaceRepository.fileEntry(path)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val sizeBytes = metadata?.sizeBytes
            if (sizeBytes != null && sizeBytes > maxChunkBytes.toLong()) {
                val approved = confirmLargeFileRead(path, sizeBytes, maxChunkBytes)
                if (!approved) {
                    throw com.miniichat.agent.AgentLoopUserCancelledException(
                        "The user cancelled reading '$path' after the application reported size_bytes=$sizeBytes, above the $maxChunkBytes-byte chunk size."
                    )
                }
                // The decision authorizes bounded reads of this path for this agent run.
                permissionSession.approvedLargeReadPaths += path
                return true
            }
        }
        val isWrite = uid == "create_file" || uid == "edit_file"
        if (isWrite) {
            // These are hard safety gates, independent of the selected tool permission mode.
            if (uid == "create_file" && !current.allowWorkspaceCreate) return false
            if (uid == "edit_file" && !current.allowWorkspaceEdit) return false
            val path = arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val normalizedPath = path.trim().replace(92.toChar(), '/').trim('/')
            val pathSegments = normalizedPath.split('/')
            if (normalizedPath.isBlank() || pathSegments.any { it.isBlank() || it == "." || it == ".." } || normalizedPath.contains(":")) return false
            val scope = current.workspaceWriteSubpath.trim().replace(92.toChar(), '/').trim('/')
            val scopeSegments = scope.split('/')
            if (scope.isNotBlank() && (scopeSegments.any { it.isBlank() || it == "." || it == ".." } || scope.contains(":") || !normalizedPath.startsWith("$scope/"))) return false
            val proposedLength = if (uid == "create_file") valueLength(arguments, "content") else valueLength(arguments, "replacement")
            if (proposedLength > current.agentMaxWriteChars) return false
        }

        if (current.toolPermissionMode != "allow_once") {
            permissionSession.allowOnceGranted = false
        }
        if (current.toolPermissionMode == "always_allow") return true
        if (current.toolPermissionMode == "allow_once" && permissionSession.allowOnceGranted) {
            return true
        }

        fun value(name: String): String = arguments[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun preview(name: String): String {
            val value = value(name)
            return if (value.length > 500) value.take(500) + "…" else value
        }
        val path = value("path").ifBlank { value("query").ifBlank { "(no path)" } }
        val summary = when (uid) {
            "create_file" -> "Create a Markdown file (${value("content").length} characters). Preview:\n${preview("content")}"
            "edit_file" -> "Replace one exact text occurrence. Target:\n${preview("target")}\nReplacement:\n${preview("replacement")}"
            "read_file" -> "Read file content. Path: $path"
            "list_files" -> "List directory entries. Path: ${value("path").ifBlank { "(workspace root)" }}"
            "search_files", "search_content" -> "Search query: ${value("query")}\nStarting path: ${value("path").ifBlank { "(workspace root)" }}"
            "get_file_info" -> "Inspect file or directory metadata. Path: $path"
            else -> arguments.toString().take(1_000)
        }
        val deferred = CompletableDeferred<Boolean>()
        approvalCompletion = deferred
        _pendingToolApproval.value = ToolApprovalRequest(call.name, path, summary)
        return try {
            val approved = deferred.await()
            // Approval authorizes the remaining tool calls only in this Agent run.
            // Never persist the temporary grant into AppSettings.
            if (approved && settings.value.toolPermissionMode == "allow_once") {
                permissionSession.allowOnceGranted = true
            }
            approved
        } finally {
            if (approvalCompletion === deferred) {
                approvalCompletion = null
                _pendingToolApproval.value = null
            }
        }
    }

    private fun valueLength(arguments: JsonObject, key: String): Int =
        arguments[key]?.jsonPrimitive?.contentOrNull?.length ?: 0

    fun respondToToolApproval(approved: Boolean) { approvalCompletion?.complete(approved) }

    fun selectConversation(id: String?) { _activeId.value = id }

    fun newConversation(): String {
        val id = newId()
        _activeId.value = id
        return id
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            store.delete(id)
            if (_activeId.value == id) _activeId.value = null
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { store.rename(id, title) }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        _isStreaming.value = false
    }

    fun clearError() { _error.value = null }
    fun clearToast() { _toast.value = null }

    fun activeProvider(): ProviderConfig? =
        providers.value.firstOrNull { it.id == settings.value.activeProviderId }

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeProviderId = providerId, activeModel = model) }
        }
    }

    fun upsertProvider(p: ProviderConfig) {
        viewModelScope.launch {
            providerStore.upsert(p)
            val all = providerStore.snapshot()
            if (settings.value.activeProviderId.isBlank() && all.isNotEmpty()) {
                val target = all.firstOrNull { it.id == p.id } ?: all.first()
                val firstModel = target.models.firstOrNull() ?: ""
                settingsRepo.update {
                    it.copy(activeProviderId = target.id, activeModel = firstModel)
                }
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            providerStore.delete(id)
            if (settings.value.activeProviderId == id) {
                val remaining = providerStore.snapshot()
                val nextProvider = remaining.firstOrNull()
                settingsRepo.update {
                    it.copy(
                        activeProviderId = nextProvider?.id ?: "",
                        activeModel = nextProvider?.models?.firstOrNull() ?: ""
                    )
                }
            }
        }
    }

    fun fetchModels(providerId: String) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            _fetchingModelsFor.value = providerId
            try {
                val models = client.listModels(provider)
                if (models.isEmpty()) {
                    _toast.value = "No models returned from /models"
                } else {
                    val updated = provider.copy(models = (provider.models + models).distinct().sorted())
                    providerStore.upsert(updated)
                    _toast.value = "Fetched ${models.size} models"
                }
            } catch (e: Exception) {
                _error.value = "Fetch models failed: ${e.message}"
            } finally {
                _fetchingModelsFor.value = null
            }
        }
    }

    fun addManualModel(providerId: String, model: String) {
        viewModelScope.launch {
            val trimmed = model.trim()
            if (trimmed.isEmpty()) return@launch
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            val updated = provider.copy(models = (provider.models + trimmed).distinct().sorted())
            providerStore.upsert(updated)
        }
    }

    fun removeModel(providerId: String, model: String) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            val updated = provider.copy(models = provider.models - model)
            providerStore.upsert(updated)
            if (settings.value.activeProviderId == providerId && settings.value.activeModel == model) {
                settingsRepo.update {
                    it.copy(activeModel = updated.models.firstOrNull() ?: "")
                }
            }
        }
    }

    fun sendMessage(text: String, attachments: List<com.miniichat.data.Attachment> = emptyList()) {
        // Reject re-entrant sends while a stream is in flight (debounce double-tap)
        if (_isStreaming.value || streamingJob?.isActive == true) return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return

        val current = settings.value
        val provider = activeProvider()
        if (provider == null) {
            _error.value = "No provider configured."
            return
        }

        val model = current.activeModel
        if (model.isBlank()) {
            _error.value = "No model selected."
            return
        }

        if (provider.apiKey.isBlank()
            && !provider.baseUrl.contains("localhost")
            && !provider.baseUrl.contains("10.0.2.2")
        ) {
            _error.value = "API key is empty for ${provider.name}."
            return
        }

        val temperature = current.temperature

        viewModelScope.launch {
            val systemPromptRaw = try {
                if (current.systemPromptSource == "file") {
                    readPromptFile(current.systemPromptFileUri, "System prompt")
                } else {
                    current.systemPrompt
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not read the system prompt source."
                return@launch
            }
            val skillsContent = if (current.skillsEnabled) {
                try {
                    if (current.skillsSource == "file") {
                        readPromptFile(current.skillsFileUri, "Skills")
                    } else {
                        current.skillsText
                    }
                } catch (e: Exception) {
                    _error.value = e.message ?: "Could not read the Skills source."
                    return@launch
                }
            } else ""
            val systemPrompt = PromptVars.render(
                template = systemPromptRaw,
                model = model,
                provider = provider.name,
                assistant = ""
            )

            val activeId = _activeId.value ?: newId().also { _activeId.value = it }
            val existing = store.snapshot().firstOrNull { it.id == activeId }
            val baseTitle = trimmed.take(30).replace("\n", " ")

            // Old chats get their turn numbers here, once. The counter only goes up.
            val baseConversation = (existing ?: Conversation(id = activeId, title = baseTitle, messages = emptyList()))
                .withTurnNumbers()
            val turnNo = baseConversation.nextTurnNo

            val userMsg = Message(
                id = newId(),
                role = "user",
                content = trimmed,
                attachments = attachments,
                turnNo = turnNo
            )
            val assistantId = newId()
            val assistantPlaceholder = Message(id = assistantId, role = "assistant", content = "")

            val updated = baseConversation
                .let { conv ->
                    conv.copy(
                        title = if (conv.messages.isEmpty()) baseTitle else conv.title,
                        messages = conv.messages + userMsg + assistantPlaceholder,
                        nextTurnNo = turnNo + 1,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            store.upsert(updated)

            val historyForApi = mutableListOf<ChatMessage>()
            if (systemPrompt.isNotBlank()) {
                historyForApi.add(ChatMessage("system", systemPrompt))
            }
            if (skillsContent.isNotBlank()) {
                historyForApi.add(ChatMessage("system", "[SKILLS]\n$skillsContent\n[/SKILLS]"))
            }
            updated.messages
                .filter { !(it.role == "assistant" && it.content.isEmpty()) }
                .forEach { msg ->
                    // Tool rounds of finished turns come right before that turn's final answer.
                    if (msg.role == "assistant" && msg.toolRounds.isNotEmpty()) {
                        historyForApi.addAll(
                            toolRoundsToChatMessages(msg.toolRounds, ToolRoundSettings.COMPACT_HISTORY)
                        )
                    }
                    val imgs = msg.attachments.filter { it.type == "image" }
                    if (msg.role == "user" && imgs.isNotEmpty()) {
                        // Build multipart content: text + image_url parts.
                        val parts = mutableListOf<com.miniichat.api.ChatPart>()
                        if (msg.content.isNotBlank()) {
                            parts.add(com.miniichat.api.ChatPart(type = "text", text = msg.content))
                        }
                        for (att in imgs) {
                            // Try to load + base64-encode the image. Fall back silently if it fails.
                            val loaded = runCatching {
                                com.miniichat.util.AttachmentLoader.load(
                                    resolver = getApplication<android.app.Application>().contentResolver,
                                    uri = android.net.Uri.parse(att.uri),
                                    mimeFallback = att.mimeType.ifBlank { "image/jpeg" }
                                )
                            }.getOrNull() ?: continue
                            val dataUrl = "data:${loaded.mimeType};base64,${loaded.base64}"
                            parts.add(com.miniichat.api.ChatPart(
                                type = "image_url",
                                imageUrl = com.miniichat.api.ChatPart.ImageUrl(url = dataUrl)
                            ))
                        }
                        // Also describe non-image attachments as text references.
                        val others = msg.attachments.filter { it.type != "image" }
                        if (others.isNotEmpty()) {
                            val tail = others.joinToString("\n") { "[file: ${it.name} (${it.mimeType})]" }
                            val merged = if (parts.firstOrNull()?.type == "text") {
                                parts[0] = com.miniichat.api.ChatPart(
                                    type = "text",
                                    text = (parts[0].text ?: "") + "\n\n" + tail
                                )
                                parts
                            } else {
                                listOf(com.miniichat.api.ChatPart(type = "text", text = tail)) + parts
                            }
                            historyForApi.add(ChatMessage(msg.role, msg.content, merged, extraContent = msg.extraContent))
                        } else {
                            historyForApi.add(ChatMessage(msg.role, msg.content, parts, extraContent = msg.extraContent))
                        }
                    } else if (msg.role == "user" && msg.attachments.isNotEmpty()) {
                        // Files only — describe inline as text refs.
                        val refs = msg.attachments.joinToString("\n") {
                            "[file: ${it.name} (${it.mimeType})]"
                        }
                        val combined = if (msg.content.isBlank()) refs else "${msg.content}\n\n$refs"
                        historyForApi.add(ChatMessage(msg.role, combined, extraContent = msg.extraContent))
                    } else {
                        historyForApi.add(ChatMessage(msg.role, msg.content, extraContent = msg.extraContent))
                    }
                }

            _isStreaming.value = true
            val builder = StringBuilder()
            var streamExtraContent: JsonObject? = null

            // Use the Behavior temperature selected in Settings.
            val effectiveSettings = current.copy(temperature = temperature)

            streamingJob = launch {
                var lastFlush = 0L
                val flushIntervalMs = 250L
                _streamingOverlay.value = assistantId to ""
                try {
                    if (current.workspaceTreeUri.isNotBlank()) {
                        val treeUri = android.net.Uri.parse(current.workspaceTreeUri)
                        val repository = SafFileRepository(
                            getApplication<android.app.Application>().contentResolver,
                            treeUri
                        )
                        if (current.directoryTreeEnabled) {
                            val tree = runCatching { repository.directoryTree(current.directoryTreeDepth) }
                                .getOrElse {
                                    "[WORKSPACE PATH RULE]\n" +
                                        "All file-tool paths are relative to the selected workspace root. The root folder name is display-only; do not prepend it to tool paths. Use an empty path for the workspace root directory.\n" +
                                        "[/WORKSPACE PATH RULE]\n" +
                                        "[TREE depth=${current.directoryTreeDepth.coerceIn(1, 8)}, verified=false]\n[tree unavailable: ${it.message ?: "scan failed"}]\n[/TREE]"
                                }
                            val treeIndex = if (systemPrompt.isNotBlank()) 1 else 0
                            historyForApi.add(treeIndex.coerceAtMost(historyForApi.size), ChatMessage("system", tree))
                        }
                        val enabledTools = buildSet {
                            if (current.toolListFilesEnabled) add("list_files")
                            if (current.toolSearchFilesEnabled) add("search_files")
                            if (current.toolSearchContentEnabled) add("search_content")
                            if (current.toolReadFileEnabled) add("read_file")
                            if (current.toolGetFileInfoEnabled) add("get_file_info")
                            if (current.toolCreateFileEnabled) add("create_file")
                            if (current.toolEditFileEnabled) add("edit_file")
                        }
                        // Each agent.run() receives an isolated, non-persistent permission session.
                        val permissionSession = ToolPermissionSession()
                        val agent = AgentLoop(
                            registry = MarkdownFileTools.registry(repository,
                                allowCreate = current.allowWorkspaceCreate && current.toolCreateFileEnabled,
                                allowEdit = current.allowWorkspaceEdit && current.toolEditFileEnabled,
                                maxWriteChars = current.agentMaxWriteChars,
                                allowedWriteSubpath = current.workspaceWriteSubpath,
                                enabledTools = enabledTools,
                                maxChunkBytes = current.agentMaxFileReadKb * 1_000,
                                confirmLargeRead = { path, sizeBytes, limit ->
                                    if (path in permissionSession.approvedLargeReadPaths) {
                                        true
                                    } else {
                                        val approved = confirmLargeFileRead(path, sizeBytes, limit)
                                        if (approved) permissionSession.approvedLargeReadPaths += path
                                        approved
                                    }
                                }
                            ),
                            complete = { messages, tools ->
                                client.completeTurn(
                                    provider = provider,
                                    settings = effectiveSettings,
                                    modelId = model,
                                    messages = messages,
                                    tools = tools,
                                    conversationId = activeId
                                )
                            },
                            maxToolRounds = current.agentMaxToolRounds,
                            confirmToolCall = { call, arguments ->
                                confirmToolCall(call, arguments, permissionSession, repository)
                            }
                        )
                        val initialCount = historyForApi.size
                        val result = agent.run(historyForApi)
                        builder.append(result.finalMessage.content)
                        _streamingOverlay.value = assistantId to builder.toString()
                        // Everything the agent added after the first request messages, minus the final answer.
                        val toolRounds = buildToolRounds(
                            generated = result.transcript.drop(initialCount).dropLast(1),
                            turnNo = turnNo
                        )
                        appendAssistant(
                            activeId, assistantId, builder.toString(),
                            result.finalMessage.extraContent,
                            toolRounds
                        )
                    } else {
                        client.chatStream(
                            provider = provider,
                            settings = effectiveSettings,
                            modelId = model,
                            messages = historyForApi,
                            conversationId = activeId,
                            onExtraContent = { streamExtraContent = it }
                        )
                            .collect { delta ->
                                builder.append(delta)
                                // 1) update in-memory overlay every token (no IO)
                                _streamingOverlay.value = assistantId to builder.toString()
                                // 2) throttled DataStore flush
                                val now = System.currentTimeMillis()
                                if (now - lastFlush >= flushIntervalMs) {
                                    appendAssistant(activeId, assistantId, builder.toString())
                                    lastFlush = now
                                }
                            }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    _error.value = e.message ?: "Request failed"
                    if (builder.isEmpty()) builder.append("(error: ${e.message ?: "Request failed"})")
                    appendAssistant(activeId, assistantId, builder.toString())
                } finally {
                    if (builder.isNotEmpty()) {
                        appendAssistant(activeId, assistantId, builder.toString(), streamExtraContent)
                    }
                    _streamingOverlay.value = null
                    _isStreaming.value = false
                    streamingJob = null
                }
            }
        }
    }

    private suspend fun appendAssistant(
        convId: String,
        msgId: String,
        content: String,
        extraContent: JsonObject? = null,
        toolRounds: List<ToolRound>? = null
    ) {
        val list = store.snapshot()
        val conv = list.firstOrNull { it.id == convId } ?: return
        val newMsgs = conv.messages.map {
            if (it.id == msgId) {
                var updatedMsg = it.copy(content = content)
                if (extraContent != null) updatedMsg = updatedMsg.copy(extraContent = extraContent)
                if (toolRounds != null) updatedMsg = updatedMsg.copy(toolRounds = toolRounds)
                updatedMsg
            } else it
        }
        store.upsert(conv.copy(messages = newMsgs, updatedAt = System.currentTimeMillis()))
    }

    fun regenerate() {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val msgs = conv.messages
            val lastUserIdx = msgs.indexOfLast { it.role == "user" }
            if (lastUserIdx < 0) return@launch
            val lastUser = msgs[lastUserIdx]
            // Cut BEFORE the user message: sendMessage adds it again, so it must not stay here.
            val trimmed = msgs.subList(0, lastUserIdx)
            store.upsert(conv.copy(messages = trimmed, updatedAt = System.currentTimeMillis()))
            sendMessage(lastUser.content, lastUser.attachments)
        }
    }

    /** Branch from a specific user message — drops everything after it and re-sends. */
    fun regenerateFrom(messageId: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val msgs = conv.messages
            val idx = msgs.indexOfFirst { it.id == messageId }
            if (idx < 0) return@launch
            val target = msgs[idx]
            if (target.role != "user") return@launch
            // Cut BEFORE the user message: sendMessage adds it again, so it must not stay here.
            val trimmed = msgs.subList(0, idx)
            store.upsert(conv.copy(messages = trimmed, updatedAt = System.currentTimeMillis()))
            sendMessage(target.content, target.attachments)
        }
    }

    /** Delete a single message in the current conversation. */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val newMsgs = conv.messages.filterNot { it.id == messageId }
            store.upsert(conv.copy(messages = newMsgs, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Edit a message's text content (no resend). */
    fun editMessage(messageId: String, newContent: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val newMsgs = conv.messages.map {
                if (it.id == messageId) it.copy(content = newContent) else it
            }
            store.upsert(conv.copy(messages = newMsgs, updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun readPromptFile(uri: String, label: String): String = withContext(Dispatchers.IO) {
        if (uri.isBlank()) {
            throw IllegalStateException("$label is set to use an external file, but no file has been selected.")
        }
        val resolver = getApplication<Application>().contentResolver
        val text = resolver.openInputStream(android.net.Uri.parse(uri))
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: throw IllegalStateException("Could not open the selected $label file. Please select it again.")
        if (text.isBlank()) {
            throw IllegalStateException("The selected $label file is empty.")
        }
        text
    }

    fun setWorkspace(uri: String?) {
        viewModelScope.launch {
            val previous = settings.value.workspaceTreeUri
            if (previous.isNotBlank() && previous != uri.orEmpty()) {
                runCatching {
                    getApplication<android.app.Application>().contentResolver.releasePersistableUriPermission(
                        android.net.Uri.parse(previous),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
            settingsRepo.update { it.copy(workspaceTreeUri = uri.orEmpty()) }
            _toast.value = if (uri.isNullOrBlank()) "Agent workspace cleared" else "Agent workspace selected"
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }
}
