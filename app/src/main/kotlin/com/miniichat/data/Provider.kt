package com.miniichat.data

import kotlinx.serialization.Serializable
import java.net.URI

object ProviderApiProtocol {
    const val CHAT_COMPLETIONS = "chat_completions"
    const val RESPONSES = "responses"
}

object ProviderKinds {
    const val AUTO = "auto"
    const val CUSTOM = "custom"
    const val OPENAI = "openai"
    const val OPENROUTER = "openrouter"
    const val DEEPSEEK = "deepseek"
    const val GROQ = "groq"
    const val MISTRAL = "mistral"
    const val TOGETHER = "together"
    const val GEMINI = "gemini"
    const val SILICONFLOW = "siliconflow"
    const val OLLAMA = "ollama"
    const val LM_STUDIO = "lmstudio"

    val choices: List<Pair<String, String>> = listOf(
        AUTO to "Automatic (match API host)",
        OPENAI to "OpenAI",
        OPENROUTER to "OpenRouter",
        DEEPSEEK to "DeepSeek",
        GROQ to "Groq",
        MISTRAL to "Mistral",
        TOGETHER to "Together AI",
        GEMINI to "Gemini OpenAI-compatible",
        SILICONFLOW to "SiliconFlow",
        OLLAMA to "Ollama",
        LM_STUDIO to "LM Studio",
        CUSTOM to "Custom / Other"
    )

    fun fromPreset(name: String): String = when {
        name.startsWith("OpenAI", true) -> OPENAI
        name.startsWith("OpenRouter", true) -> OPENROUTER
        name.startsWith("DeepSeek", true) -> DEEPSEEK
        name.startsWith("Groq", true) -> GROQ
        name.startsWith("Mistral", true) -> MISTRAL
        name.startsWith("Together", true) -> TOGETHER
        name.startsWith("Gemini", true) -> GEMINI
        name.startsWith("SiliconFlow", true) -> SILICONFLOW
        name.startsWith("Ollama", true) -> OLLAMA
        name.startsWith("LM Studio", true) -> LM_STUDIO
        else -> CUSTOM
    }

    fun infer(baseUrl: String): String {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val port = uri?.port ?: -1
        return when {
            host == "api.openai.com" -> OPENAI
            host == "openrouter.ai" || host.endsWith(".openrouter.ai") -> OPENROUTER
            host == "api.deepseek.com" -> DEEPSEEK
            host == "api.groq.com" -> GROQ
            host == "api.mistral.ai" -> MISTRAL
            host == "api.together.xyz" -> TOGETHER
            host == "generativelanguage.googleapis.com" -> GEMINI
            host == "api.siliconflow.cn" -> SILICONFLOW
            host == "ollama.com" || port == 11434 -> OLLAMA
            port == 1234 -> LM_STUDIO
            else -> CUSTOM
        }
    }

    fun resolve(provider: ProviderConfig): String =
        provider.providerType.takeIf { selected -> selected != AUTO && choices.any { it.first == selected } }
            ?: infer(provider.baseUrl)
}

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String> = emptyList(),
    val customHeaders: Map<String, String> = emptyMap(),
    val extraBody: Map<String, String> = emptyMap(),
    val apiProtocol: String = ProviderApiProtocol.CHAT_COMPLETIONS,
    /** Stable provider identity; AUTO uses an exact known API hostname, not the editable display name. */
    val providerType: String = ProviderKinds.AUTO,
    /** Provider-level API metadata overrides. Values are JSON literals; absent keys inherit defaults. */
    val metadataParameters: Map<String, String> = emptyMap(),
    /** JSON object overrides keyed by exact model ID; these override provider-level values. */
    val metadataParametersByModel: Map<String, String> = emptyMap(),
    /** User-declared capability status by native parameter key. */
    val metadataCapabilityOverrides: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

object ProviderPresets {
    data class Preset(
        val name: String,
        val baseUrl: String,
        val sampleModel: String,
        val hint: String
    )

    val all: List<Preset> = listOf(
        Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini",
            "Official OpenAI API"),
        Preset("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto",
            "Aggregator for OpenAI / Anthropic / Gemini / open-source"),
        Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-v4-pro",
            "DeepSeek V4"),
        Preset("Groq", "https://api.groq.com/openai/v1", "llama-3.1-70b-versatile",
            "Fast inference, free tier"),
        Preset("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest",
            "Mistral models"),
        Preset("Together AI", "https://api.together.xyz/v1",
            "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo",
            "Open-source models hosting"),
        Preset("Gemini (OpenAI shim)",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-1.5-flash",
            "Google Gemini via OpenAI-compatible endpoint"),
        Preset("SiliconFlow",
            "https://api.siliconflow.cn/v1",
            "Qwen/Qwen2.5-7B-Instruct",
            "国内聚合，速度快"),
        Preset("Ollama (local)", "http://10.0.2.2:11434/v1", "llama3.2",
            "Local Ollama via emulator (10.0.2.2)"),
        Preset("LM Studio (local)", "http://10.0.2.2:1234/v1", "local-model",
            "Local LM Studio via emulator"),
        Preset("Custom", "https://", "",
            "Any OpenAI-compatible endpoint"),
    )
}
