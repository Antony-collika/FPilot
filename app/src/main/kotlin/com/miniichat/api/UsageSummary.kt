package com.miniichat.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/**
 * Finds the token usage in a provider response (a normal JSON body, or the text of a
 * streamed response) and describes it in one line, or returns null if there is none.
 *
 * The number of cached tokens is read from the places providers commonly use:
 * prompt_tokens_details.cached_tokens, prompt_cache_hit_tokens, cache_read_input_tokens.
 */
fun summarizeUsage(body: String): String? {
    val objects = mutableListOf<JsonObject>()
    val trimmed = body.trim()
    if (trimmed.startsWith("{")) {
        runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()?.let { objects += it }
    } else {
        for (line in body.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()?.let { objects += it }
        }
    }
    val usage = objects.mapNotNull { item ->
        (item["usage"] as? JsonObject)
            ?: ((item["response"] as? JsonObject)?.get("usage") as? JsonObject)
    }.lastOrNull() ?: return null

    val prompt = usage.intValue("prompt_tokens") ?: usage.intValue("input_tokens")
    val completion = usage.intValue("completion_tokens") ?: usage.intValue("output_tokens")
    val cached = (usage["input_tokens_details"] as? JsonObject)?.intValue("cached_tokens")
        ?: (usage["prompt_tokens_details"] as? JsonObject)?.intValue("cached_tokens")
        ?: usage.intValue("prompt_cache_hit_tokens")
        ?: usage.intValue("cache_read_input_tokens")

    val parts = mutableListOf<String>()
    if (prompt != null) parts += "prompt $prompt"
    if (cached != null) {
        val share = if (prompt != null && prompt > 0) " (${cached * 100 / prompt}%)" else ""
        parts += "cached $cached$share"
    } else {
        parts += "cached: not reported by the provider"
    }
    if (completion != null) parts += "completion $completion"
    return parts.joinToString(" · ")
}
