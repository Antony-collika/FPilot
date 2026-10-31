package com.miniichat.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

enum class MetadataIssueLevel { ERROR, WARNING }

data class MetadataValidationIssue(
    val key: String,
    val level: MetadataIssueLevel,
    val message: String
)

/** Validates custom metadata while allowing unknown provider extensions with a warning. */
object MetadataValidator {
    private val json = Json { isLenient = false; allowSpecialFloatingPointValues = false }
    private val keyPattern = Regex("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*")
    private val reserved = setOf("model", "messages", "input", "stream", "tools")

    fun validate(
        values: Map<String, String>,
        protocol: String,
        strictUnknownKeys: Boolean = false
    ): List<MetadataValidationIssue> = buildList {
        values.forEach { (key, raw) ->
            if (!keyPattern.matches(key)) {
                add(MetadataValidationIssue(key, MetadataIssueLevel.ERROR, "Invalid parameter name. Use a JSON key or dotted object path."))
                return@forEach
            }
            if (key.substringBefore(".") in reserved) {
                add(MetadataValidationIssue(key, MetadataIssueLevel.ERROR, "This key is reserved for the request structure and cannot be overridden."))
                return@forEach
            }
            if (raw.trim() in setOf("@omit", "@disabled")) return@forEach
            val value = runCatching { json.parseToJsonElement(raw) }.getOrElse {
                add(MetadataValidationIssue(key, MetadataIssueLevel.ERROR, "Value must be a valid JSON literal."))
                return@forEach
            }
            if ((value as? JsonPrimitive)?.let { it.isString && it.content in setOf("@omit", "@disabled") } == true) return@forEach
            val definition = MetadataParameterCatalog.definition(key)
            if (definition == null) {
                add(MetadataValidationIssue(
                    key,
                    if (strictUnknownKeys) MetadataIssueLevel.ERROR else MetadataIssueLevel.WARNING,
                    "Unknown provider-specific field. It will be sent unchanged; verify it against the provider documentation."
                ))
                return@forEach
            }
            if (protocol !in definition.protocols) {
                add(MetadataValidationIssue(key, MetadataIssueLevel.ERROR, "This parameter is not part of the selected API protocol."))
                return@forEach
            }
            val validType = when (definition.valueType) {
                MetadataValueType.NUMBER -> (value as? JsonPrimitive)?.let { !it.isString && it.doubleOrNull != null } == true
                MetadataValueType.INTEGER -> (value as? JsonPrimitive)?.let { !it.isString && (it.intOrNull != null || it.longOrNull != null) } == true
                MetadataValueType.BOOLEAN -> (value as? JsonPrimitive)?.booleanOrNull != null
                MetadataValueType.STRING, MetadataValueType.ENUM -> (value as? JsonPrimitive)?.isString == true
                MetadataValueType.ARRAY -> value is JsonArray
                MetadataValueType.OBJECT -> value is JsonObject
                MetadataValueType.JSON -> true
            }
            if (!validType) {
                add(MetadataValidationIssue(
                    key, MetadataIssueLevel.ERROR,
                    "Expected ${definition.valueType.name.lowercase()} value for this parameter."
                ))
            }
        }
    }

    fun validateModelOverrides(raw: String): List<MetadataValidationIssue> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return listOf(MetadataValidationIssue("*", MetadataIssueLevel.ERROR, "Model overrides must be a valid JSON object."))
        return root.mapNotNull { (modelId, value) ->
            if (modelId.isBlank()) MetadataValidationIssue("*", MetadataIssueLevel.ERROR, "Model ID cannot be empty.")
            else if (value !is JsonObject) MetadataValidationIssue(modelId, MetadataIssueLevel.ERROR, "Each model override must be a JSON object.")
            else null
        }
    }
}
