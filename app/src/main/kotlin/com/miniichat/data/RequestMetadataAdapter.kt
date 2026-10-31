package com.miniichat.data

/**
 * Maps the four common Request Control Panel controls to native request fields.
 * Provider-specific and arbitrary settings are configured separately in the JSON editors.
 */
object RequestMetadataAdapter {
    fun supportedOptions(provider: ProviderConfig, model: String): Set<String> =
        setOf("temperature", "top_p", "max_output_tokens", "reasoning_effort")

    private fun maySend(provider: ProviderConfig, model: String, key: String, settings: AppSettings): Boolean {
        val status = ProviderCapabilityRegistry.capability(provider, model, key).status
        return status == CapabilityStatus.SUPPORTED || settings.allowUnverifiedMetadata
    }

    private fun maxTokensKey(provider: ProviderConfig): String =
        if (provider.apiProtocol == ProviderApiProtocol.RESPONSES) {
            "max_output_tokens"
        } else if (ProviderKinds.resolve(provider) == ProviderKinds.OPENAI) {
            "max_completion_tokens"
        } else {
            "max_tokens"
        }

    private fun reasoningKey(provider: ProviderConfig): String =
        if (provider.apiProtocol == ProviderApiProtocol.RESPONSES) "reasoning.effort"
        else if (ProviderKinds.resolve(provider) == ProviderKinds.OPENROUTER) "reasoning"
        else "reasoning_effort"

    fun parameters(provider: ProviderConfig, model: String, settings: AppSettings): Map<String, String> =
        buildMap {
            if (settings.temperatureEnabled && maySend(provider, model, "temperature", settings)) {
                put("temperature", settings.temperature.coerceIn(0f, 2f).toString())
            }
            if (settings.topPEnabled && maySend(provider, model, "top_p", settings)) {
                put("top_p", settings.topP.coerceIn(0.0001f, 1f).toString())
            }
            val tokenKey = maxTokensKey(provider)
            if (settings.maxOutputTokensEnabled && maySend(provider, model, tokenKey, settings)) {
                put(tokenKey, settings.maxOutputTokens.coerceIn(1, 1_000_000).toString())
            }
            val reasonKey = reasoningKey(provider)
            if (settings.reasoningEnabled && maySend(provider, model, reasonKey, settings)) {
                val effort = settings.reasoningEffort.takeIf { it in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max") } ?: "medium"
                put(reasonKey, if (reasonKey == "reasoning") "{\"effort\":\"$effort\"}" else "\"$effort\"")
            }
        }

    fun capabilitySummary(provider: ProviderConfig, model: String, logicalOption: String): ProviderParameterCapability {
        val wireKey = when (logicalOption) {
            "max_output_tokens" -> maxTokensKey(provider)
            "reasoning_effort" -> reasoningKey(provider)
            else -> logicalOption
        }
        return ProviderCapabilityRegistry.capability(provider, model, wireKey)
    }
}
