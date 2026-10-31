package com.miniichat.api

import com.miniichat.data.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Clean-room wire-protocol codecs for native provider APIs.
 *
 * Provider protocols are translated to/from FPilot's normalized ChatMessage and ToolDefinition
 * models. These codecs do not depend on a provider SDK or import implementation code from APilot.
 */
internal object NativeProviderProtocol {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun anthropicRequestBody(
        modelId: String,
        settings: AppSettings,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        extraBody: Map<String, String> = emptyMap()
    ): String {
        val system = messages.filter { it.role == "system" }
            .joinToString("\n\n") { messageText(it) }
            .trim()
        val turns = mutableListOf<JsonObject>()
        for (message in messages.filterNot { it.role == "system" }) {
            if (message.role == "tool") {
                val toolId = message.toolCallId.orEmpty()
                require(toolId.isNotBlank()) { "Anthropic tool results must include a tool-call ID." }
                turns += buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", toolId)
                            put("content", message.content)
                        }
                    }
                }
                continue
            }
            val role = if (message.role == "assistant") "assistant" else "user"
            val blocks = buildJsonArray {
                message.parts.orEmpty().forEach { part ->
                    when (part.type) {
                        "text" -> if (!part.text.isNullOrEmpty()) add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        })
                        "image_url" -> part.imageUrl?.url?.let { add(anthropicImageBlock(it)) }
                    }
                }
                if (message.parts.isNullOrEmpty() && message.content.isNotEmpty()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", message.content)
                    })
                }
                message.toolCalls.orEmpty().forEach { call ->
                    val input = parseObject(call.arguments, "arguments for tool '${call.name}'")
                    add(buildJsonObject {
                        put("type", "tool_use")
                        put("id", call.id)
                        put("name", call.name)
                        put("input", input)
                    })
                }
            }
            if (blocks.isNotEmpty()) {
                turns += buildJsonObject {
                    put("role", role)
                    put("content", blocks)
                }
            }
        }

        val body = buildJsonObject {
            put("model", modelId)
            put("max_tokens", 8192)
            put("temperature", settings.temperature)
            put("messages", JsonArray(turns))
            if (system.isNotBlank()) put("system", system)
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.parameters)
                        })
                    }
                }
            }
            extraBody.forEach { (key, raw) ->
                if (key.isNotBlank() && key !in setOf("model", "max_tokens", "temperature", "messages", "system", "tools")) {
                    val element = runCatching { json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
                    put(key, element)
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }

    fun parseAnthropicResponse(response: String): ChatMessage {
        val root = json.parseToJsonElement(response).jsonObject
        val content = root["content"]?.jsonArray.orEmpty()
        val text = content.mapNotNull { block ->
            val item = block.jsonObject
            if (item["type"]?.jsonPrimitive?.contentOrNull == "text" &&
                item["thought"]?.jsonPrimitive?.contentOrNull != "true"
            ) {
                item["text"]?.jsonPrimitive?.contentOrNull
            } else null
        }.joinToString("")
        val calls = content.mapNotNull { block ->
            val item = block.jsonObject
            if (item["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@mapNotNull null
            val input = item["input"] as? JsonObject ?: JsonObject(emptyMap())
            ToolCall(
                id = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                arguments = json.encodeToString(JsonObject.serializer(), input)
            )
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        return ChatMessage(
            role = "assistant",
            content = text,
            toolCalls = calls.takeIf { it.isNotEmpty() }
        )
    }

    fun geminiRequestBody(
        settings: AppSettings,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        extraBody: Map<String, String> = emptyMap()
    ): String {
        val systemParts = messages.filter { it.role == "system" }
            .map { messageText(it) }
            .filter { it.isNotBlank() }
        val contents = mutableListOf<JsonObject>()
        for (message in messages.filterNot { it.role == "system" }) {
            if (message.role == "tool") {
                val name = message.name?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Gemini tool results must include a tool name.")
                contents += buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", name)
                                message.toolCallId?.let { put("id", it) }
                                putJsonObject("response") {
                                    put("content", message.content)
                                }
                            }
                        }
                    }
                }
                continue
            }
            val role = if (message.role == "assistant") "model" else "user"
            val parts = buildJsonArray {
                message.parts.orEmpty().forEach { part ->
                    when (part.type) {
                        "text" -> if (!part.text.isNullOrEmpty()) add(buildJsonObject { put("text", part.text) })
                        "image_url" -> part.imageUrl?.url?.let { add(geminiImagePart(it)) }
                    }
                }
                if (message.parts.isNullOrEmpty() && message.content.isNotEmpty()) {
                    add(buildJsonObject { put("text", message.content) })
                }
                message.toolCalls.orEmpty().forEach { call ->
                    val args = parseObject(call.arguments, "arguments for tool '${call.name}'")
                    add(buildJsonObject {
                        putJsonObject("functionCall") {
                            put("name", call.name)
                            put("args", args)
                            put("id", call.id)
                        }
                        call.extraContent?.get("thoughtSignature")?.let {
                            put("thoughtSignature", it)
                        }
                    })
                }
            }
            if (parts.isNotEmpty()) {
                contents += buildJsonObject {
                    put("role", role)
                    put("parts", parts)
                }
            }
        }

        val body = buildJsonObject {
            put("contents", JsonArray(contents))
            if (systemParts.isNotEmpty()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        systemParts.forEach { addJsonObject { put("text", it) } }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", settings.temperature)
                put("maxOutputTokens", 8192)
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parameters)
                                }
                            }
                        }
                    }
                }
                putJsonObject("toolConfig") {
                    putJsonObject("functionCallingConfig") { put("mode", "AUTO") }
                }
            }
            extraBody.forEach { (key, raw) ->
                if (key.isNotBlank() && key !in setOf("contents", "systemInstruction", "generationConfig", "tools", "toolConfig")) {
                    val element = runCatching { json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
                    put(key, element)
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }

    fun parseGeminiResponse(response: String): ChatMessage {
        val root = json.parseToJsonElement(response).jsonObject
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("Gemini returned no candidate.")
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        val text = parts.mapNotNull { item ->
            val part = item.jsonObject
            if (part["thought"]?.jsonPrimitive?.contentOrNull == "true") {
                null
            } else {
                part["text"]?.jsonPrimitive?.contentOrNull
            }
        }.joinToString("")
        val calls = parts.mapNotNull { item ->
            val part = item.jsonObject
            val functionCall = part["functionCall"] as? JsonObject ?: return@mapNotNull null
            val args = functionCall["args"] as? JsonObject ?: JsonObject(emptyMap())
            val id = functionCall["id"]?.jsonPrimitive?.contentOrNull
                ?: "gemini-${java.util.UUID.randomUUID()}"
            val thoughtSignature = part["thoughtSignature"]
            ToolCall(
                id = id,
                name = functionCall["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                arguments = json.encodeToString(JsonObject.serializer(), args),
                extraContent = thoughtSignature?.let { buildJsonObject { put("thoughtSignature", it) } }
            )
        }.filter { it.name.isNotBlank() }
        val extra = candidate["finishReason"]?.let { buildJsonObject { put("finishReason", it) } }
        return ChatMessage(
            role = "assistant",
            content = text,
            toolCalls = calls.takeIf { it.isNotEmpty() },
            extraContent = extra
        )
    }

    fun parseAnthropicModels(response: String): List<String> =
        json.parseToJsonElement(response).jsonObject["data"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .distinct()
            .sorted()

    fun parseGeminiModels(response: String): List<String> =
        json.parseToJsonElement(response).jsonObject["models"]?.jsonArray.orEmpty()
            .filter { item ->
                item.jsonObject["supportedGenerationMethods"]?.jsonArray
                    ?.any { it.jsonPrimitive.contentOrNull == "generateContent" } == true
            }
            .mapNotNull { item ->
                item.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('/')
            }
            .distinct()
            .sorted()

    private fun messageText(message: ChatMessage): String =
        if (message.parts.isNullOrEmpty()) message.content
        else message.parts.mapNotNull { if (it.type == "text") it.text else null }.joinToString("\n")

    private fun parseObject(raw: String, label: String): JsonObject =
        runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw IllegalArgumentException("Invalid JSON $label.", it) }

    private fun dataImage(url: String): Pair<String, String> {
        val match = Regex("^data:([^;,]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(url)
            ?: throw IllegalArgumentException("Native image inputs must be base64 data URLs.")
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun anthropicImageBlock(url: String): JsonObject {
        val (mime, encoded) = dataImage(url)
        return buildJsonObject {
            put("type", "image")
            putJsonObject("source") {
                put("type", "base64")
                put("media_type", mime)
                put("data", encoded)
            }
        }
    }

    private fun geminiImagePart(url: String): JsonObject {
        val (mime, encoded) = dataImage(url)
        return buildJsonObject {
            putJsonObject("inlineData") {
                put("mimeType", mime)
                put("data", encoded)
            }
        }
    }
}
