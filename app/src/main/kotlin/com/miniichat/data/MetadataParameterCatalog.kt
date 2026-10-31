package com.miniichat.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

enum class MetadataValueType {
    NUMBER, INTEGER, BOOLEAN, STRING, ENUM, ARRAY, OBJECT, JSON
}

data class MetadataParameterDefinition(
    /** Native JSON key or object path. Dot-separated paths are expanded into nested JSON objects. */
    val key: String,
    val protocols: Set<String>,
    val valueType: MetadataValueType,
    val description: String,
    val example: String = ""
)

/**
 * Protocol reference catalog. The catalog describes wire format only; it does not assert
 * that every model behind an API endpoint accepts every parameter.
 */
object MetadataParameterCatalog {
    private val chat = ProviderApiProtocol.CHAT_COMPLETIONS
    private val responses = ProviderApiProtocol.RESPONSES
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    val definitions: List<MetadataParameterDefinition> = listOf(
        MetadataParameterDefinition("temperature", setOf(chat, responses), MetadataValueType.NUMBER, "Sampling temperature.", "0.7"),
        MetadataParameterDefinition("top_p", setOf(chat, responses), MetadataValueType.NUMBER, "Nucleus sampling.", "0.9"),
        MetadataParameterDefinition("max_tokens", setOf(chat), MetadataValueType.INTEGER, "Maximum generated tokens on many Chat Completions providers.", "2048"),
        MetadataParameterDefinition("max_completion_tokens", setOf(chat), MetadataValueType.INTEGER, "OpenAI Chat Completions output-token limit.", "2048"),
        MetadataParameterDefinition("max_output_tokens", setOf(responses), MetadataValueType.INTEGER, "Responses output-token limit.", "2048"),
        MetadataParameterDefinition("seed", setOf(chat), MetadataValueType.INTEGER, "Sampling seed where supported.", "42"),
        MetadataParameterDefinition("random_seed", setOf(chat), MetadataValueType.INTEGER, "Mistral seed field.", "42"),
        MetadataParameterDefinition("reasoning_effort", setOf(chat), MetadataValueType.ENUM, "Provider-specific reasoning effort on Chat Completions.", "\"high\""),
        MetadataParameterDefinition("reasoning", setOf(chat, responses), MetadataValueType.OBJECT, "Native reasoning configuration. Fields vary by provider and protocol.", "{\"effort\":\"high\"}"),
        MetadataParameterDefinition("reasoning.effort", setOf(responses), MetadataValueType.ENUM, "Responses API reasoning effort path.", "\"high\""),
        MetadataParameterDefinition("thinking", setOf(chat), MetadataValueType.OBJECT, "Provider-specific thinking mode configuration.", "{\"type\":\"enabled\"}"),
        MetadataParameterDefinition("enable_thinking", setOf(chat), MetadataValueType.BOOLEAN, "SiliconFlow thinking mode toggle.", "true"),
        MetadataParameterDefinition("thinking_budget", setOf(chat), MetadataValueType.INTEGER, "Provider/model-specific thinking token budget.", "8192"),
        MetadataParameterDefinition("response_format", setOf(chat), MetadataValueType.OBJECT, "JSON or structured output configuration.", "{\"type\":\"json_object\"}"),
        MetadataParameterDefinition("text", setOf(responses), MetadataValueType.OBJECT, "Responses output format and verbosity container. Example: text.verbosity.", "{\"verbosity\":\"low\"}"),
        MetadataParameterDefinition("stop", setOf(chat), MetadataValueType.ARRAY, "Stop sequences.", "[\"END\"]"),
        MetadataParameterDefinition("parallel_tool_calls", setOf(chat, responses), MetadataValueType.BOOLEAN, "Parallel function-call control where supported.", "false"),
        MetadataParameterDefinition("service_tier", setOf(chat, responses), MetadataValueType.STRING, "Provider service tier.", "\"default\""),
        MetadataParameterDefinition("store", setOf(responses), MetadataValueType.BOOLEAN, "Responses storage behavior.", "false"),
        MetadataParameterDefinition("truncation", setOf(responses), MetadataValueType.ENUM, "Responses input truncation behavior.", "\"auto\""),
        MetadataParameterDefinition("frequency_penalty", setOf(chat, responses), MetadataValueType.NUMBER, "Frequency penalty where supported.", "0.2"),
        MetadataParameterDefinition("presence_penalty", setOf(chat, responses), MetadataValueType.NUMBER, "Presence penalty where supported.", "0.2"),
        MetadataParameterDefinition("top_k", setOf(chat), MetadataValueType.INTEGER, "Provider-specific top-k sampling.", "40"),
        MetadataParameterDefinition("min_p", setOf(chat), MetadataValueType.NUMBER, "Provider-specific min-p sampling.", "0.05"),
        MetadataParameterDefinition("repetition_penalty", setOf(chat), MetadataValueType.NUMBER, "Provider-specific repetition penalty.", "1.05"),
        MetadataParameterDefinition("prompt_mode", setOf(chat), MetadataValueType.STRING, "Mistral prompt mode, including reasoning.", "\"reasoning\""),
        MetadataParameterDefinition("logprobs", setOf(chat), MetadataValueType.BOOLEAN, "Return token log probabilities where supported.", "true"),
        MetadataParameterDefinition("top_logprobs", setOf(chat, responses), MetadataValueType.INTEGER, "Number of top log probabilities.", "5"),
        MetadataParameterDefinition("tool_choice", setOf(chat, responses), MetadataValueType.JSON, "Tool selection policy.", "\"auto\""),
        MetadataParameterDefinition("user", setOf(chat, responses), MetadataValueType.STRING, "Optional end-user identifier.", "\"user-123\""),
        MetadataParameterDefinition("metadata", setOf(responses), MetadataValueType.OBJECT, "Provider request metadata.", "{\"source\":\"FPilot\"}")
    )

    fun forProtocol(protocol: String): List<MetadataParameterDefinition> =
        definitions.filter { protocol in it.protocols }

    fun definition(key: String): MetadataParameterDefinition? =
        definitions.firstOrNull { it.key == key }

    /** Formats stored JSON literals as an editable JSON object. Invalid legacy literals stay strings. */
    fun formatOverrides(values: Map<String, String>): String {
        val elementMap = values.mapValues { (_, raw) -> parseLiteral(raw) }
        return json.encodeToString(JsonObject.serializer(), JsonObject(elementMap))
    }

    /** Parses a user-edited JSON object into native JSON literal strings. */
    fun parseOverrides(raw: String): Map<String, String> {
        val element = json.parseToJsonElement(raw)
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("Metadata overrides must be a JSON object.")
        if (obj.keys.any { it.isBlank() }) {
            throw IllegalArgumentException("Metadata parameter names cannot be empty.")
        }
        return obj.mapValues { (_, value) -> value.toString() }
    }

    fun parseLiteral(raw: String): JsonElement =
        runCatching { json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }

    fun emptyOverridesText(): String = buildJsonObject {}.toString()
}
