package com.miniichat.data

/**
 * Capability is guidance, not a request filter. UNKNOWN and CONDITIONAL parameters remain
 * user-configurable through the advanced JSON editor; only built-in controls are guarded.
 */
enum class CapabilityStatus {
    SUPPORTED, CONDITIONAL, UNSUPPORTED, UNKNOWN
}

data class ProviderParameterCapability(
    val parameterKey: String,
    val status: CapabilityStatus,
    val sourceUrl: String?,
    val explanation: String
)

object ProviderCapabilityRegistry {
    private val chat = ProviderApiProtocol.CHAT_COMPLETIONS
    private val responses = ProviderApiProtocol.RESPONSES

    private val documentation: Map<String, Map<String, String>> = mapOf(
        ProviderKinds.OPENAI to mapOf(
            chat to "https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create",
            responses to "https://developers.openai.com/api/reference/resources/responses/methods/create"
        ),
        ProviderKinds.OPENROUTER to mapOf(
            chat to "https://openrouter.ai/docs/api-reference/parameters"
        ),
        ProviderKinds.DEEPSEEK to mapOf(
            chat to "https://api-docs.deepseek.com/api/create-chat-completion",
            responses to "https://api-docs.deepseek.com/api/create-response/"
        ),
        ProviderKinds.GROQ to mapOf(
            chat to "https://console.groq.com/docs/api-reference"
        ),
        ProviderKinds.MISTRAL to mapOf(
            chat to "https://docs.mistral.ai/api/endpoint/chat"
        ),
        ProviderKinds.TOGETHER to mapOf(
            chat to "https://docs.together.ai/reference/chat-completions"
        ),
        ProviderKinds.GEMINI to mapOf(
            chat to "https://ai.google.dev/gemini-api/docs/openai"
        ),
        ProviderKinds.SILICONFLOW to mapOf(
            chat to "https://docs.siliconflow.cn/docs/api/chat-completions-post"
        ),
        ProviderKinds.OLLAMA to mapOf(
            chat to "https://github.com/ollama/ollama/blob/main/docs/api/openai-compatibility.mdx"
        ),
        ProviderKinds.LM_STUDIO to mapOf(
            chat to "https://lmstudio.ai/docs/developer/openai-compat",
            responses to "https://lmstudio.ai/docs/developer/openai-compat/responses"
        )
    )

    private val documentedFields: Map<String, Map<String, Set<String>>> = mapOf(
        ProviderKinds.OPENAI to mapOf(
            chat to setOf("temperature", "top_p", "max_completion_tokens", "reasoning_effort", "response_format", "seed", "stop", "parallel_tool_calls", "service_tier", "frequency_penalty", "presence_penalty", "logprobs", "top_logprobs", "tool_choice", "user"),
            responses to setOf("temperature", "top_p", "max_output_tokens", "reasoning.effort", "text", "store", "truncation", "service_tier", "parallel_tool_calls", "tool_choice", "top_logprobs", "metadata", "user")
        ),
        ProviderKinds.OPENROUTER to mapOf(
            chat to setOf("temperature", "top_p", "max_tokens", "max_completion_tokens", "reasoning", "response_format", "seed", "stop", "parallel_tool_calls", "frequency_penalty", "presence_penalty", "top_k", "min_p", "repetition_penalty", "tool_choice", "user")
        ),
        ProviderKinds.DEEPSEEK to mapOf(
            chat to setOf("temperature", "top_p", "max_tokens", "reasoning_effort", "thinking", "response_format", "stop", "tool_choice"),
            responses to setOf("temperature", "top_p", "max_output_tokens", "reasoning.effort", "text", "tool_choice", "top_logprobs", "service_tier", "store", "truncation")
        ),
        ProviderKinds.GROQ to mapOf(
            chat to setOf("temperature", "top_p", "max_tokens", "reasoning_effort", "response_format", "stop", "seed", "frequency_penalty", "presence_penalty", "tool_choice")
        ),
        ProviderKinds.MISTRAL to mapOf(
            chat to setOf("temperature", "top_p", "max_tokens", "reasoning_effort", "response_format", "random_seed", "stop", "frequency_penalty", "presence_penalty", "repetition_penalty", "prompt_mode", "tool_choice")
        ),
        ProviderKinds.TOGETHER to mapOf(
            chat to setOf("temperature", "top_p", "max_tokens", "response_format", "seed", "stop", "frequency_penalty", "presence_penalty", "top_k", "min_p", "repetition_penalty", "tool_choice")
        ),
        ProviderKinds.GEMINI to mapOf(
            chat to setOf("temperature", "top_p", "max_tokens", "reasoning_effort", "response_format", "stop", "tool_choice")
        ),
        ProviderKinds.SILICONFLOW to mapOf(
            chat to setOf("temperature", "top_p", "max_tokens", "reasoning_effort", "thinking_budget", "enable_thinking", "response_format", "stop", "top_k", "frequency_penalty", "presence_penalty", "repetition_penalty", "tool_choice")
        ),
        ProviderKinds.OLLAMA to mapOf(
            chat to setOf("temperature", "top_p", "max_tokens", "stop", "seed", "frequency_penalty", "presence_penalty", "repeat_penalty", "top_k", "min_p"),
            responses to setOf("temperature", "top_p", "max_output_tokens", "reasoning.effort", "text", "tool_choice")
        ),
        ProviderKinds.LM_STUDIO to mapOf(
            chat to setOf("temperature", "top_p", "max_tokens", "stop", "seed", "frequency_penalty", "presence_penalty", "tool_choice"),
            responses to setOf("temperature", "top_p", "max_output_tokens", "reasoning.effort", "text", "tool_choice")
        )
    )

    fun capability(provider: ProviderConfig, model: String, parameterKey: String): ProviderParameterCapability {
        val kind = ProviderKinds.resolve(provider)
        val docs = documentation[kind]?.get(provider.apiProtocol)
        provider.metadataCapabilityOverrides[parameterKey]?.let { declared ->
            val status = runCatching { CapabilityStatus.valueOf(declared.trim().uppercase()) }
                .getOrDefault(CapabilityStatus.UNKNOWN)
            return ProviderParameterCapability(
                parameterKey = parameterKey,
                status = status,
                sourceUrl = docs,
                explanation = "Capability status explicitly declared in this provider configuration."
            )
        }
        val supported = documentedFields[kind]?.get(provider.apiProtocol).orEmpty()
        if (parameterKey in supported) {
            val modelCondition = when {
                parameterKey in setOf("reasoning_effort", "reasoning", "reasoning.effort", "thinking", "thinking_budget", "enable_thinking") ->
                    "This field is model-dependent. Confirm the selected model's reasoning/thinking support."
                parameterKey in setOf("temperature", "top_p") && isReasoningModel(model) ->
                    "Some reasoning models ignore or restrict sampling controls."
                kind in setOf(ProviderKinds.OPENROUTER, ProviderKinds.TOGETHER, ProviderKinds.GEMINI, ProviderKinds.SILICONFLOW, ProviderKinds.OLLAMA, ProviderKinds.LM_STUDIO) ->
                    "Endpoint support is documented, but the routed or loaded model can expose a narrower subset."
                else -> "Documented for this provider protocol; model-specific restrictions may still apply."
            }
            return ProviderParameterCapability(
                parameterKey = parameterKey,
                status = if (parameterKey in setOf("reasoning_effort", "reasoning", "reasoning.effort", "thinking", "thinking_budget", "enable_thinking") || isReasoningModel(model) || kind in setOf(ProviderKinds.OPENROUTER, ProviderKinds.TOGETHER, ProviderKinds.GEMINI, ProviderKinds.SILICONFLOW, ProviderKinds.OLLAMA, ProviderKinds.LM_STUDIO)) {
                    CapabilityStatus.CONDITIONAL
                } else {
                    CapabilityStatus.SUPPORTED
                },
                sourceUrl = docs,
                explanation = modelCondition
            )
        }

        val definition = MetadataParameterCatalog.definition(parameterKey)
        if (definition != null && provider.apiProtocol !in definition.protocols) {
            return ProviderParameterCapability(
                parameterKey, CapabilityStatus.UNSUPPORTED, docs,
                "This catalog field belongs to a different API protocol."
            )
        }
        if (kind == ProviderKinds.CUSTOM || docs == null) {
            return ProviderParameterCapability(
                parameterKey, CapabilityStatus.UNKNOWN, null,
                "No provider-specific documentation is registered. You can still send it from Advanced Parameters."
            )
        }
        return ProviderParameterCapability(
            parameterKey, CapabilityStatus.UNKNOWN, docs,
            "No verified rule is registered for this parameter and protocol. It remains available as a custom override."
        )
    }

    fun isReasoningModel(model: String): Boolean {
        val id = model.lowercase()
        return id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") ||
            id.startsWith("gpt-5") || id.contains("deepseek-reasoner") ||
            id.contains("gemini-2.5") || id.contains("gemini-3")
    }

    fun statusLabel(status: CapabilityStatus): String = when (status) {
        CapabilityStatus.SUPPORTED -> "Documented"
        CapabilityStatus.CONDITIONAL -> "Model-dependent"
        CapabilityStatus.UNSUPPORTED -> "Not applicable to this protocol"
        CapabilityStatus.UNKNOWN -> "Unverified"
    }
}
