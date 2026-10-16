package com.miniichat.agent

import com.miniichat.api.ChatMessage
import com.miniichat.api.ToolDefinition
import com.miniichat.api.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Runs a model/tool cycle until the model returns a normal assistant answer.
 *
 * The completion callback is deliberately injected so the loop can be tested
 * independently of networking and Android APIs.
 */
class AgentLoop(
    private val registry: AgentToolRegistry,
    private val complete: suspend (
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ) -> ChatMessage,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    private val maxToolResultChars: Int = DEFAULT_MAX_TOOL_RESULT_CHARS,
    private val confirmToolCall: suspend (ToolCall, JsonObject) -> Boolean = { _, _ -> true }
) {
    init {
        require(maxToolRounds > 0) { "maxToolRounds must be positive." }
        require(maxToolResultChars > 0) { "maxToolResultChars must be positive." }
    }

    suspend fun run(initialMessages: List<ChatMessage>): AgentRunResult {
        val messages = initialMessages.toMutableList()
        var rounds = 0
        var executedCalls = 0

        while (true) {
            val assistant = complete(messages.toList(), registry.definitions())
            val calls = assistant.toolCalls.orEmpty()

            if (calls.isEmpty()) {
                messages += assistant
                return AgentRunResult(
                    finalMessage = assistant,
                    transcript = messages.toList(),
                    toolRounds = rounds,
                    executedToolCalls = executedCalls
                )
            }

            if (rounds >= maxToolRounds) {
                throw AgentLoopLimitException(
                    "Agent stopped after $maxToolRounds tool rounds to prevent an endless loop."
                )
            }

            // Preserve the assistant tool-call message before appending tool results.
            messages += assistant
            rounds++

            for (call in calls) {
                val result = executeSafely(call)
                messages += ChatMessage(
                    role = "tool",
                    content = result.take(maxToolResultChars),
                    toolCallId = call.id,
                    name = call.name
                )
                executedCalls++
            }
        }
    }

    private suspend fun executeSafely(call: ToolCall): String {
        if (!registry.contains(call.name)) {
            return "Tool error: unknown tool '${call.name}'."
        }

        val arguments = runCatching {
            Json.parseToJsonElement(call.arguments).jsonObject
        }.getOrElse {
            return "Tool error: invalid JSON arguments for '${call.name}'."
        }

        if (!confirmToolCall(call, arguments)) {
            return "Tool call denied by user; no tool was executed."
        }

        return runCatching { registry.execute(call.name, arguments) }
            .getOrElse { error ->
                "Tool error for '${call.name}': ${error.message ?: error::class.simpleName}"
            }
    }

    companion object {
        const val DEFAULT_MAX_TOOL_ROUNDS = 8
        const val DEFAULT_MAX_TOOL_RESULT_CHARS = 24_000
    }
}

data class AgentRunResult(
    val finalMessage: ChatMessage,
    val transcript: List<ChatMessage>,
    val toolRounds: Int,
    val executedToolCalls: Int
)

class AgentLoopLimitException(message: String) : IllegalStateException(message)
