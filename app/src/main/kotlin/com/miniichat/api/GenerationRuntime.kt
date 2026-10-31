package com.miniichat.api

import com.miniichat.data.AppSettings
import com.miniichat.data.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * Provider-neutral input to a generation runtime. Provider credentials are needed only by the
 * adapter and are never copied into request snapshots or workspace documents.
 */
data class GenerationRequest(
    val provider: ProviderConfig,
    val settings: AppSettings,
    val modelId: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val conversationId: String? = null
)

data class GenerationCapabilities(
    val streamingText: Boolean,
    val toolCalling: Boolean,
    val streamingToolCalls: Boolean
)

sealed interface GenerationEvent {
    data class TextDelta(val text: String) : GenerationEvent

    /** Snapshot of one in-progress tool call. Adapters may emit multiple updates for one call. */
    data class ToolCallUpdate(
        val streamId: String,
        val id: String?,
        val name: String,
        val arguments: String,
        val complete: Boolean = false
    ) : GenerationEvent

    /** Final normalized assistant message, including provider metadata needed for replay. */
    data class Completed(val message: ChatMessage) : GenerationEvent
}

/**
 * Application-owned boundary between orchestration and provider wire protocols.
 * New provider adapters must normalize their response into the same event and message models.
 */
interface GenerationRuntime {
    val capabilities: GenerationCapabilities

    fun capabilitiesFor(provider: ProviderConfig): GenerationCapabilities = when (provider.apiStyle) {
        "anthropic", "gemini" -> GenerationCapabilities(
            streamingText = false,
            toolCalling = true,
            streamingToolCalls = false
        )
        "openai_compatible", "" -> capabilities
        else -> GenerationCapabilities(
            streamingText = false,
            toolCalling = false,
            streamingToolCalls = false
        )
    }

    suspend fun complete(request: GenerationRequest): ChatMessage

    fun stream(request: GenerationRequest): Flow<GenerationEvent>
}

/**
 * Provider adapter facade. LlmClient owns the OpenAI-compatible, native Anthropic, and native
 * Gemini protocol codecs while the agent and UI depend only on this normalized runtime contract.
 */
class ProviderGenerationRuntime(
    private val client: LlmClient
) : GenerationRuntime {
    override val capabilities = GenerationCapabilities(
        streamingText = true,
        toolCalling = true,
        streamingToolCalls = false
    )

    override suspend fun complete(request: GenerationRequest): ChatMessage =
        client.completeTurn(
            provider = request.provider,
            settings = request.settings,
            modelId = request.modelId,
            messages = request.messages,
            tools = request.tools,
            conversationId = request.conversationId
        )

    override fun stream(request: GenerationRequest): Flow<GenerationEvent> = flow {
        var extraContent: JsonObject? = null
        val fullText = StringBuilder()
        client.chatStream(
            provider = request.provider,
            settings = request.settings,
            modelId = request.modelId,
            messages = request.messages,
            conversationId = request.conversationId,
            onExtraContent = { extraContent = it }
        ).collect { delta ->
            fullText.append(delta)
            emit(GenerationEvent.TextDelta(delta))
        }
        emit(
            GenerationEvent.Completed(
                ChatMessage(
                    role = "assistant",
                    content = fullText.toString(),
                    extraContent = extraContent
                )
            )
        )
    }
}
