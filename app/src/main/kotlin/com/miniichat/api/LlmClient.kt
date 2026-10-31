package com.miniichat.api

import com.miniichat.data.AppSettings
import com.miniichat.data.ProviderConfig
import com.miniichat.data.ProviderApiProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI

@Serializable
data class ChatPart(
    val type: String,                 // "text" | "image_url"
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null
) {
    @Serializable
    data class ImageUrl(val url: String, val detail: String? = null)
}

/**
 * A chat message that can either carry plain text (string content) or a
 * multipart payload (text + image parts). We serialize manually to keep
 * compatibility with both OpenAI-style string content and array-of-parts.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val parts: List<ChatPart>? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    /** Provider-specific metadata on the assistant message, e.g. Gemini thought signatures. */
    val extraContent: JsonObject? = null
) {
    fun isMultipart(): Boolean = !parts.isNullOrEmpty()
}

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    /** Provider-specific metadata required to replay a tool call (e.g. Gemini thought signatures). */
    val extraContent: JsonObject? = null
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
private data class ChatChunk(
    val choices: List<Choice> = emptyList()
) {
    @Serializable
    data class Choice(
        val delta: Delta? = null,
        val message: ResponseMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )
    @Serializable
    data class Delta(
        val content: String? = null,
        val role: String? = null,
        @SerialName("extra_content") val extraContent: JsonObject? = null
    )
    @Serializable
    data class ResponseMessage(
        val role: String = "assistant",
        val content: String? = "",
        @SerialName("tool_calls") val toolCalls: List<ResponseToolCall>? = null,
        @SerialName("extra_content") val extraContent: JsonObject? = null
    )
    @Serializable
    data class ResponseToolCall(
        val id: String,
        val type: String = "function",
        val function: ResponseFunction,
        @SerialName("extra_content") val extraContent: JsonObject? = null
    )
    @Serializable
    data class ResponseFunction(val name: String, val arguments: String)
}

@Serializable
private data class ChatResponse(
    val choices: List<ChatChunk.Choice> = emptyList()
)

@Serializable
private data class ModelEntry(val id: String)

@Serializable
private data class ModelsResponse(val data: List<ModelEntry> = emptyList())

class LlmClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val responsesOutputKey = "fpilot_responses_output"

    private val _requestLogs = MutableStateFlow("")
    val requestLogs: StateFlow<String> = _requestLogs.asStateFlow()
    private val requestLogLock = Any()
    private val maxLogChars = 500_000

    fun clearRequestLogs() {
        synchronized(requestLogLock) {
            _requestLogs.value = ""
        }
    }

    /** Appends a request/response entry in arrival order, trimming only the oldest log text. */
    private fun appendLogEntry(type: String, bodyText: String) {
        val entry = buildString {
            append("===== ")
            append(type)
            append(" ")
            append(System.currentTimeMillis())
            append(" =====\n")
            append(bodyText)
            if (!bodyText.endsWith("\n")) append("\n")
            append("---\n")
        }
        synchronized(requestLogLock) {
            val combined = _requestLogs.value + entry
            _requestLogs.value = if (combined.length <= maxLogChars) {
                combined
            } else {
                "[Earlier LLM logs trimmed]\n" + combined.takeLast(maxLogChars - 28)
            }
        }
    }

    /** Records the exact JSON body before sending it to the provider. */
    private fun recordRequestBody(bodyText: String) = appendLogEntry("REQUEST", bodyText)

    /** Records the raw response body before parsing or extracting user-visible text. */
    private fun recordReceivedBody(statusCode: Int, bodyText: String) {
        // If the provider reported token usage, show a one-line summary above the raw body.
        val usage = runCatching { summarizeUsage(bodyText) }.getOrNull()
        appendLogEntry("RECEIVED (HTTP $statusCode)", if (usage == null) bodyText else "[usage] $usage\n$bodyText")
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    private fun chatEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/chat/completions"
    private fun responsesEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/responses"
    private fun modelsEndpoint(baseUrl: String) = "${baseUrl.trimEnd('/')}/models"

    /** Best-effort parse of a value that may be a number, bool, or string. */
    private fun coerceToJson(v: String): JsonElement =
        runCatching { json.parseToJsonElement(v) }.getOrElse { JsonPrimitive(v) }

    private fun resolvedMetadataParameters(provider: ProviderConfig, modelId: String): Map<String, String> {
        val resolved = provider.metadataParameters.toMutableMap()
        val modelOverrides = provider.metadataParametersByModel[modelId]?.let { raw ->
            runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
                throw IllegalArgumentException("Model-specific metadata for '$modelId' must be a valid JSON object.")
            }
        }
        modelOverrides?.forEach { (key, value) -> resolved[key] = value.toString() }
        return resolved
    }

    internal fun buildRequestBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Float,
        tools: List<ToolDefinition> = emptyList(),
        conversationId: String? = null
    ): String {
        val msgsJson = kotlinx.serialization.json.buildJsonArray {
            for (m in messages) {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("role", m.role)
                    if (m.isMultipart()) {
                        put("content", kotlinx.serialization.json.buildJsonArray {
                            for (part in m.parts!!) {
                                add(json.encodeToJsonElement(ChatPart.serializer(), part))
                            }
                        })
                    } else {
                        put("content", m.content)
                    }
                    if (!m.toolCallId.isNullOrBlank()) put("tool_call_id", m.toolCallId)
                    if (!m.name.isNullOrBlank()) put("name", m.name)
                    m.extraContent?.let { metadata ->
                        val providerMetadata = buildJsonObject {
                            for ((key, value) in metadata) {
                                if (key != responsesOutputKey) put(key, value)
                            }
                        }
                        if (providerMetadata.isNotEmpty()) put("extra_content", providerMetadata)
                    }
                    if (!m.toolCalls.isNullOrEmpty()) {
                        put("tool_calls", kotlinx.serialization.json.buildJsonArray {
                            for (call in m.toolCalls) {
                                add(serializeToolCall(call))
                            }
                        })
                    }
                })
            }
        }
        val obj = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            put("temperature", temperature)
            put("messages", msgsJson)
            mistralPromptCacheKey(provider, conversationId)?.let {
                put("prompt_cache_key", it)
            }
            if (tools.isNotEmpty()) {
                put("tools", kotlinx.serialization.json.buildJsonArray {
                    for (tool in tools) {
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            })
                        })
                    }
                })
                put("tool_choice", "auto")
            }
            // Metadata overrides are provider-scoped and only emitted when explicitly configured.
            for ((k, v) in resolvedMetadataParameters(provider, modelId)) {
                if (k.isBlank()) continue
                if (v == "@omit") { remove(k); continue }
                if (v.isBlank()) continue
                put(k, coerceToJson(v))
            }
            for ((k, v) in provider.extraBody) {
                if (k.isBlank()) continue
                put(k, coerceToJson(v))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Builds a Responses request and replays original output items so reasoning payloads
     * remain attached to the assistant turn that produced them.
     */
    internal fun buildResponsesRequestBody(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        tools: List<ToolDefinition> = emptyList()
    ): String {
        val input = kotlinx.serialization.json.buildJsonArray {
            for (message in messages) {
                val savedOutput = message.extraContent?.get(responsesOutputKey) as? JsonArray
                if (message.role == "assistant" && savedOutput != null) {
                    savedOutput.forEach { add(it) }
                    continue
                }
                if (message.role == "tool") {
                    val callId = message.toolCallId
                        ?: throw IllegalStateException("Responses API requires a call ID for each tool result.")
                    add(buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", callId)
                        put("output", message.content)
                    })
                    continue
                }
                if (message.role == "assistant" && !message.toolCalls.isNullOrEmpty()) {
                    if (message.content.isNotBlank()) add(buildJsonObject {
                        put("type", "message")
                        put("role", "assistant")
                        put("content", message.content)
                    })
                    for (call in message.toolCalls) add(buildJsonObject {
                        put("type", "function_call")
                        put("call_id", call.id)
                        put("name", call.name)
                        put("arguments", call.arguments)
                    })
                    continue
                }
                add(buildJsonObject {
                    put("role", message.role)
                    if (message.isMultipart()) {
                        put("content", kotlinx.serialization.json.buildJsonArray {
                            for (part in message.parts.orEmpty()) {
                                when (part.type) {
                                    "text" -> add(buildJsonObject {
                                        put("type", "input_text")
                                        put("text", part.text.orEmpty())
                                    })
                                    "image_url" -> part.imageUrl?.let { image ->
                                        add(buildJsonObject {
                                            put("type", "input_image")
                                            put("image_url", image.url)
                                            image.detail?.let { put("detail", it) }
                                        })
                                    }
                                }
                            }
                        })
                    } else put("content", message.content)
                })
            }
        }

        val body = buildJsonObject {
            put("model", modelId)
            put("input", input)
            put("stream", stream)
            put("include", kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive("reasoning.encrypted_content"))
            })
            if (tools.isNotEmpty()) {
                put("tools", kotlinx.serialization.json.buildJsonArray {
                    for (tool in tools) add(buildJsonObject {
                        put("type", "function")
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                        put("strict", false)
                    })
                })
                put("tool_choice", "auto")
            }
            // Metadata overrides use the selected protocol's native JSON field names.
            for ((key, value) in resolvedMetadataParameters(provider, modelId)) {
                if (key.isBlank()) continue
                if (value == "@omit") { remove(key); continue }
                if (value.isBlank()) continue
                put(key, coerceToJson(value))
            }
            for ((key, value) in provider.extraBody) {
                if (key.isBlank()) continue
                put(key, coerceToJson(value))
            }
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }

    private fun responsesMetadata(output: JsonArray): JsonObject = buildJsonObject {
        put(responsesOutputKey, output)
    }

    /**
     * Mistral uses this stable conversation-scoped key to improve prompt-cache reuse.
     * Do not send the parameter to other OpenAI-compatible providers.
     */
    internal fun mistralPromptCacheKey(provider: ProviderConfig, conversationId: String?): String? {
        if (conversationId.isNullOrBlank()) return null
        val host = runCatching { URI(provider.baseUrl).host }.getOrNull()
        if (!host.equals("api.mistral.ai", ignoreCase = true)) return null
        return "fpilot-$conversationId"
    }

    /** Serialize a tool call without discarding provider-specific replay metadata. */
    internal fun serializeToolCall(call: ToolCall): JsonObject = buildJsonObject {
        put("id", call.id)
        put("type", "function")
        put("function", buildJsonObject {
            put("name", call.name)
            put("arguments", call.arguments)
        })
        call.extraContent?.let { put("extra_content", it) }
    }

    suspend fun listModels(provider: ProviderConfig): List<String> {
        val resp = client.get(modelsEndpoint(provider.baseUrl)) {
            headers {
                if (provider.apiKey.isNotBlank()) {
                    append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                }
                for ((k, v) in provider.customHeaders) {
                    if (k.isBlank()) continue
                    append(k, v)
                }
            }
        }
        if (!resp.status.isSuccess()) {
            val err = runCatching { resp.bodyAsText() }.getOrDefault("")
            throw RuntimeException("HTTP ${resp.status.value}: ${err.take(300)}")
        }
        val text = resp.bodyAsText()
        val parsed = runCatching {
            json.decodeFromString(ModelsResponse.serializer(), text)
        }.getOrNull()
        if (parsed != null && parsed.data.isNotEmpty()) {
            return parsed.data.map { it.id }.distinct().sorted()
        }
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        return ids.distinct().sorted()
    }


    /**
     * Executes one non-streaming completion with optional OpenAI-compatible tools.
     * This method returns tool calls as structured data; callers must validate and
     * execute them through an application-owned registry.
     */
    suspend fun completeTurn(
        provider: ProviderConfig,
        settings: AppSettings,
        modelId: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        conversationId: String? = null
    ): ChatMessage {
        if (provider.apiProtocol == ProviderApiProtocol.RESPONSES) {
            return completeResponsesTurn(provider, modelId, messages, tools)
        }
        val bodyText = buildRequestBody(
            provider = provider,
            modelId = modelId,
            messages = messages,
            stream = false,
            temperature = settings.temperature,
            tools = tools,
            conversationId = conversationId
        )
        recordRequestBody(bodyText)
        val response = client.preparePost(chatEndpoint(provider.baseUrl)) {
            contentType(ContentType.Application.Json)
            headers {
                if (provider.apiKey.isNotBlank()) {
                    append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                }
                append(HttpHeaders.Accept, "application/json")
                for ((k, v) in provider.customHeaders) {
                    if (k.isBlank()) continue
                    append(k, v)
                }
            }
            setBody(bodyText)
        }.execute { resp ->
            val text = resp.bodyAsText()
            recordReceivedBody(resp.status.value, text)
            if (!resp.status.isSuccess()) {
                throw RuntimeException("HTTP ${resp.status.value}: ${text.take(500)}")
            }
            text
        }

        return parseCompletionResponse(response)
    }

    private suspend fun completeResponsesTurn(
        provider: ProviderConfig,
        modelId: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): ChatMessage {
        val bodyText = buildResponsesRequestBody(provider, modelId, messages, stream = false, tools = tools)
        recordRequestBody(bodyText)
        val response = client.preparePost(responsesEndpoint(provider.baseUrl)) {
            contentType(ContentType.Application.Json)
            headers {
                if (provider.apiKey.isNotBlank()) append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                append(HttpHeaders.Accept, "application/json")
                for ((key, value) in provider.customHeaders) if (key.isNotBlank()) append(key, value)
            }
            setBody(bodyText)
        }.execute { resp ->
            val text = resp.bodyAsText()
            recordReceivedBody(resp.status.value, text)
            if (!resp.status.isSuccess()) throw RuntimeException("HTTP ${resp.status.value}: ${text.take(500)}")
            text
        }
        return parseResponsesCompletionResponse(response)
    }

    /** Parse output and preserve raw items for subsequent requests and persisted history. */
    internal fun parseResponsesCompletionResponse(response: String): ChatMessage {
        val root = json.parseToJsonElement(response).jsonObject
        val output = root["output"] as? JsonArray ?: JsonArray(emptyList())
        val textParts = mutableListOf<String>()
        val calls = mutableListOf<ToolCall>()
        for (element in output) {
            val item = element as? JsonObject ?: continue
            when (item["type"]?.jsonPrimitive?.content) {
                "message" -> {
                    val parts = item["content"] as? JsonArray ?: JsonArray(emptyList())
                    for (partElement in parts) {
                        val part = partElement as? JsonObject ?: continue
                        when (part["type"]?.jsonPrimitive?.content) {
                            "output_text", "text" -> part["text"]?.jsonPrimitive?.content
                                ?.takeIf { it.isNotEmpty() }?.let(textParts::add)
                            "refusal" -> part["refusal"]?.jsonPrimitive?.content
                                ?.takeIf { it.isNotEmpty() }?.let(textParts::add)
                        }
                    }
                }
                "function_call" -> {
                    val callId = item["call_id"]?.jsonPrimitive?.content
                        ?: item["id"]?.jsonPrimitive?.content ?: continue
                    val name = item["name"]?.jsonPrimitive?.content ?: continue
                    val arguments = item["arguments"]?.jsonPrimitive?.content ?: "{}"
                    calls += ToolCall(callId, name, arguments)
                }
            }
        }
        val content = textParts.joinToString("")
            .ifEmpty { root["output_text"]?.jsonPrimitive?.content.orEmpty() }
        if (output.isEmpty() && content.isBlank()) {
            val error = (root["error"] as? JsonObject)?.get("message")?.toString()?.trim('"')
            throw RuntimeException(error ?: "The Responses API returned no output.")
        }
        return ChatMessage(
            role = "assistant",
            content = content,
            toolCalls = calls.takeIf { it.isNotEmpty() },
            extraContent = if (output.isEmpty()) null else responsesMetadata(output)
        )
    }

    private fun responseEventError(event: JsonObject): String? {
        val nested = event["response"] as? JsonObject
        val error = (event["error"] as? JsonObject) ?: (nested?.get("error") as? JsonObject)
        return error?.get("message")?.jsonPrimitive?.content
            ?: event["message"]?.jsonPrimitive?.content
    }

    /** Parse a non-streaming response while retaining metadata needed by the next agent turn. */
    internal fun parseCompletionResponse(response: String): ChatMessage {
        val parsed = json.decodeFromString(ChatResponse.serializer(), response)
        val message = parsed.choices.firstOrNull()?.message
            ?: throw RuntimeException("The model returned no message.")
        return ChatMessage(
            role = message.role,
            content = message.content.orEmpty(),
            extraContent = message.extraContent,
            toolCalls = message.toolCalls.orEmpty().map { call ->
                ToolCall(
                    id = call.id,
                    name = call.function.name,
                    arguments = call.function.arguments,
                    extraContent = call.extraContent
                )
            }.takeIf { it.isNotEmpty() }
        )
    }

    fun chatStream(
        provider: ProviderConfig,
        settings: AppSettings,
        modelId: String,
        messages: List<ChatMessage>,
        conversationId: String? = null,
        onExtraContent: (JsonObject) -> Unit = {}
    ): Flow<String> = flow {
        val useResponses = provider.apiProtocol == ProviderApiProtocol.RESPONSES
        val bodyText = if (useResponses) {
            buildResponsesRequestBody(provider, modelId, messages, stream = settings.stream)
        } else {
            buildRequestBody(
                provider = provider,
                modelId = modelId,
                messages = messages,
                stream = settings.stream,
                temperature = settings.temperature,
                conversationId = conversationId
            )
        }
        recordRequestBody(bodyText)

        client.preparePost(
            if (useResponses) responsesEndpoint(provider.baseUrl) else chatEndpoint(provider.baseUrl)
        ) {
            contentType(ContentType.Application.Json)
            headers {
                if (provider.apiKey.isNotBlank()) {
                    append(HttpHeaders.Authorization, "Bearer ${provider.apiKey}")
                }
                append(HttpHeaders.Accept, if (settings.stream) "text/event-stream" else "application/json")
                for ((k, v) in provider.customHeaders) {
                    if (k.isBlank()) continue
                    append(k, v)
                }
            }
            setBody(bodyText)
        }.execute { response ->
            if (settings.stream) {
                val rawStream = StringBuilder()
                var streamError: String? = null
                var receivedTerminalEvent = false
                val channel: ByteReadChannel = response.bodyAsChannel()
                try {
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        // readUTF8Line strips separators; restore a newline so the complete
                        // SSE event/data stream remains readable in the log.
                        rawStream.append(line).append('\n')
                        if (!response.status.isSuccess()) continue
                        if (line.isEmpty()) continue
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        if (payload.isEmpty()) continue

                        if (useResponses) {
                            val event = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
                                ?: continue
                            when (event["type"]?.jsonPrimitive?.content) {
                                "response.output_text.delta" -> {
                                    val delta = event["delta"]?.jsonPrimitive?.content
                                    if (!delta.isNullOrEmpty()) emit(delta)
                                }
                                "response.completed", "response.incomplete" -> {
                                    receivedTerminalEvent = true
                                    val responseObject = event["response"] as? JsonObject
                                    val output = responseObject?.get("output") as? JsonArray
                                    if (output != null) onExtraContent(responsesMetadata(output))
                                }
                                "response.failed", "error" -> {
                                    receivedTerminalEvent = true
                                    streamError = responseEventError(event)
                                        ?: "The Responses API reported a streaming error."
                                }
                            }
                        } else {
                            val chunk = runCatching {
                                json.decodeFromString(ChatChunk.serializer(), payload)
                            }.getOrNull() ?: continue
                            val choice = chunk.choices.firstOrNull()
                            choice?.delta?.extraContent?.let(onExtraContent)
                            choice?.message?.extraContent?.let(onExtraContent)
                            val delta = choice?.delta?.content
                            if (!delta.isNullOrEmpty()) emit(delta)
                        }
                    }
                } finally {
                    recordReceivedBody(response.status.value, rawStream.toString())
                }
                if (!response.status.isSuccess()) {
                    throw RuntimeException("HTTP ${response.status.value}: ${rawStream.toString().take(500)}")
                }
                if (useResponses) {
                    if (streamError != null) throw RuntimeException(streamError)
                    if (!receivedTerminalEvent) {
                        throw RuntimeException("The Responses stream ended before a completion event was received.")
                    }
                }
            } else {
                val text = response.bodyAsText()
                recordReceivedBody(response.status.value, text)
                if (!response.status.isSuccess()) {
                    throw RuntimeException("HTTP ${response.status.value}: ${text.take(500)}")
                }
                if (useResponses) {
                    val message = parseResponsesCompletionResponse(text)
                    message.extraContent?.let(onExtraContent)
                    if (message.content.isNotEmpty()) emit(message.content)
                } else {
                    val parsed = runCatching {
                        json.decodeFromString(ChatResponse.serializer(), text)
                    }.getOrNull()
                    val message = parsed?.choices?.firstOrNull()?.message
                    message?.extraContent?.let(onExtraContent)
                    val content = message?.content
                    if (!content.isNullOrEmpty()) emit(content)
                }
            }
        }
    }
}
