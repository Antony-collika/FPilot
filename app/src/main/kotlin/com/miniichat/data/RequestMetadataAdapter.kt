package com.miniichat.data

import java.net.URI

/** Conservative provider/model capability map for Request Control Panel metadata. */
object RequestMetadataAdapter {
    private fun isReasoningModel(model: String): Boolean {
        val id = model.lowercase()
        return id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") ||
            id.startsWith("gpt-5")
    }

    fun supportedOptions(provider: ProviderConfig, model: String): Set<String> {
        if (model.isBlank()) return emptySet()
        val name = provider.name.lowercase()
        val knownChat = listOf("openai", "openrouter", "deepseek", "groq", "mistral", "together", "gemini", "siliconflow", "ollama", "lm studio")
            .any { name.contains(it) }
        val officialOpenAI = runCatching { URI(provider.baseUrl).host.equals("api.openai.com", ignoreCase = true) }
            .getOrDefault(false)
        return when (provider.apiProtocol) {
            ProviderApiProtocol.CHAT_COMPLETIONS -> if (!knownChat) emptySet() else buildSet {
                if (!isReasoningModel(model)) {
                    add("temperature")
                    add("top_p")
                }
                add("max_output_tokens")
                if (officialOpenAI && isReasoningModel(model)) add("reasoning_effort")
            }
            ProviderApiProtocol.RESPONSES -> if (!officialOpenAI) emptySet() else buildSet {
                if (!isReasoningModel(model)) {
                    add("temperature")
                    add("top_p")
                }
                add("max_output_tokens")
                if (isReasoningModel(model)) add("reasoning_effort")
            }
            else -> emptySet()
        }
    }

    fun parameters(provider: ProviderConfig, model: String, settings: AppSettings): Map<String, String> {
        val supported = supportedOptions(provider, model)
        val result = linkedMapOf(
            "temperature" to "@omit",
            "top_p" to "@omit",
            "max_tokens" to "@omit",
            "max_completion_tokens" to "@omit",
            "max_output_tokens" to "@omit",
            "reasoning_effort" to "@omit",
            "reasoning" to "@omit",
            "thinking" to "@omit"
        )
        if ("temperature" in supported && settings.temperatureEnabled) {
            result["temperature"] = settings.temperature.coerceIn(0f, 2f).toString()
        }
        if ("top_p" in supported && settings.topPEnabled) {
            result["top_p"] = settings.topP.coerceIn(0f, 1f).toString()
        }
        if ("max_output_tokens" in supported && settings.maxOutputTokensEnabled) {
            val key = if (provider.apiProtocol == ProviderApiProtocol.RESPONSES) "max_output_tokens"
            else if (provider.name.contains("openai", true) || provider.name.contains("groq", true)) "max_completion_tokens"
            else "max_tokens"
            result[key] = settings.maxOutputTokens.coerceIn(1, 16_384).toString()
        }
        if ("reasoning_effort" in supported && settings.reasoningEnabled) {
            val level = settings.reasoningEffort.takeIf { it in setOf("low", "medium", "high") } ?: "medium"
            if (provider.apiProtocol == ProviderApiProtocol.CHAT_COMPLETIONS) {
                result["reasoning_effort"] = "\"$level\""
            } else {
                result["reasoning"] = "{\"effort\":\"$level\"}"
            }
        }
        return result
    }
}
