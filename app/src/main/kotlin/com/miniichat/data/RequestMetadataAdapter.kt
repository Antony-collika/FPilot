package com.miniichat.data

/** Conservative provider/model capability map for Request Control Panel metadata. */
object RequestMetadataAdapter {
    private fun isReasoningModel(model: String): Boolean {
        val id = model.lowercase()
        return id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") ||
            id.startsWith("gpt-5") || id.contains("deepseek-r1")
    }

    fun supportedOptions(provider: ProviderConfig, model: String): Set<String> {
        if (model.isBlank()) return emptySet()
        val name = provider.name.lowercase()
        val knownChat = listOf("openai", "openrouter", "deepseek", "groq", "mistral", "together", "gemini", "siliconflow", "ollama", "lm studio")
            .any { name.contains(it) }
        return when (provider.apiProtocol) {
            ProviderApiProtocol.CHAT_COMPLETIONS -> if (!knownChat) emptySet() else buildSet {
                if (!isReasoningModel(model)) {
                    add("temperature")
                    add("top_p")
                }
                add("max_output_tokens")
                if ((name.contains("openai") && isReasoningModel(model)) ||
                    (name.contains("deepseek") && model.contains("r1", true))) add("reasoning_effort")
            }
            ProviderApiProtocol.RESPONSES -> if (!name.contains("openai")) emptySet() else buildSet {
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
        val result = linkedMapOf<String, String>()
        if ("temperature" in supported) result["temperature"] =
            if (settings.temperatureEnabled) settings.temperature.coerceIn(0f, 2f).toString() else "@omit"
        if ("top_p" in supported) result["top_p"] =
            if (settings.topPEnabled) settings.topP.coerceIn(0f, 1f).toString() else "@omit"
        if ("max_output_tokens" in supported) {
            val key = if (provider.apiProtocol == ProviderApiProtocol.RESPONSES) "max_output_tokens"
            else if (provider.name.contains("openai", true) || provider.name.contains("groq", true) ||
                provider.name.contains("openrouter", true)) "max_completion_tokens" else "max_tokens"
            result[key] = if (settings.maxOutputTokensEnabled) settings.maxOutputTokens.coerceIn(1, 1_000_000).toString() else "@omit"
        }
        if ("reasoning_effort" in supported) {
            val level = settings.reasoningEffort.takeIf { it in setOf("low", "medium", "high") } ?: "medium"
            if (settings.reasoningEnabled && provider.apiProtocol == ProviderApiProtocol.CHAT_COMPLETIONS) {
                result["reasoning_effort"] = "\"$level\""
            } else if (settings.reasoningEnabled) {
                result["reasoning"] = "{\"effort\":\"$level\"}"
            } else result["reasoning_effort"] = "@omit"
        }
        return result
    }
}
