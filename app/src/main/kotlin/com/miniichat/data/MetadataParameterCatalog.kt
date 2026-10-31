package com.miniichat.data

/**
 * Reference catalog for common API metadata fields.
 *
 * The catalog deliberately does not claim model-level support: providers frequently
 * expose different capabilities for individual models and may change them over time.
 * Values configured by the user are sent using the selected protocol's native field names.
 */
data class MetadataParameterDefinition(
    val key: String,
    val protocols: Set<String>,
    val description: String
)

object MetadataParameterCatalog {
    private val chat = ProviderApiProtocol.CHAT_COMPLETIONS
    private val responses = ProviderApiProtocol.RESPONSES

    val definitions: List<MetadataParameterDefinition> = listOf(
        MetadataParameterDefinition("temperature", setOf(chat, responses), "Sampling temperature"),
        MetadataParameterDefinition("top_p", setOf(chat, responses), "Nucleus sampling"),
        MetadataParameterDefinition("max_tokens", setOf(chat), "Maximum generated tokens (legacy/common Chat Completions field)"),
        MetadataParameterDefinition("max_completion_tokens", setOf(chat), "Maximum completion tokens"),
        MetadataParameterDefinition("max_output_tokens", setOf(responses), "Maximum output tokens"),
        MetadataParameterDefinition("seed", setOf(chat), "Sampling seed where supported"),
        MetadataParameterDefinition("reasoning_effort", setOf(chat), "Reasoning effort on compatible Chat Completions endpoints"),
        MetadataParameterDefinition("reasoning", setOf(responses, chat), "Protocol/provider-specific reasoning configuration"),
        MetadataParameterDefinition("thinking", setOf(chat), "Provider-specific thinking configuration"),
        MetadataParameterDefinition("response_format", setOf(chat), "JSON or structured output format"),
        MetadataParameterDefinition("text", setOf(responses), "Responses output format and verbosity settings"),
        MetadataParameterDefinition("stop", setOf(chat), "Stop sequences"),
        MetadataParameterDefinition("parallel_tool_calls", setOf(chat, responses), "Parallel function-call control"),
        MetadataParameterDefinition("service_tier", setOf(chat, responses), "Provider service tier"),
        MetadataParameterDefinition("store", setOf(responses), "Responses storage behavior"),
        MetadataParameterDefinition("truncation", setOf(responses), "Responses input truncation behavior"),
        MetadataParameterDefinition("verbosity", setOf(chat), "Output verbosity where supported")
    )

    fun forProtocol(protocol: String): List<MetadataParameterDefinition> =
        definitions.filter { protocol in it.protocols }
}
