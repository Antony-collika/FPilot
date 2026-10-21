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

            for ((index, call) in calls.withIndex()) {
                val cancellation = try {
                    val result = executeSafely(call)
                    messages += ChatMessage(
                        role = "tool",
                        content = limitToolResult(result),
                        toolCallId = call.id,
                        name = call.name
                    )
                    executedCalls++
                    null
                } catch (cancelled: AgentLoopUserCancelledException) {
                    messages += ChatMessage(
                        role = "tool",
                        content = "The user cancelled this file-read request. The tool was not executed.",
                        toolCallId = call.id,
                        name = call.name
                    )
                    cancelled
                }

                if (cancellation != null) {
                    calls.drop(index + 1).forEach { remaining ->
                        messages += ChatMessage(
                            role = "tool",
                            content = "Not executed because the user cancelled the preceding file-read request.",
                            toolCallId = remaining.id,
                            name = remaining.name
                        )
                    }
                    return finishAfterUserCancellation(
                        messages = messages,
                        rounds = rounds,
                        executedCalls = executedCalls,
                        reason = cancellation.message ?: "The user cancelled the file-read request."
                    )
                }
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

        return try {
            registry.execute(call.name, arguments)
        } catch (cancelled: AgentLoopUserCancelledException) {
            throw cancelled
        } catch (error: Exception) {
            val path = arguments["path"]?.toString()
                ?.removeSurrounding("\\\"")
                ?.ifBlank { "(workspace root)" }
                ?: "(workspace root)"
            "Tool error for '${call.name}' at path '$path': ${error::class.simpleName}: ${error.message ?: "no error message"}. " +
                "Ask the user how to proceed, stating the exact path attempted and the system error. Do not keep retrying."
        }
    }

    private fun limitToolResult(result: String): String {
        if (result.length <= maxToolResultChars) return result
        val marker = "\n[tool_result_truncated by AgentLoop; original_chars=${result.length}; returned_chars=${maxToolResultChars - 1}. Content is incomplete. Do not trust continuation offsets in this truncated result; request a smaller chunk.]"
        if (marker.length >= maxToolResultChars) return result.take(maxToolResultChars)
        return result.take(maxToolResultChars - marker.length) + marker
    }

    private suspend fun finishAfterUserCancellation(
        messages: List<ChatMessage>,
        rounds: Int,
        executedCalls: Int,
        reason: String
    ): AgentRunResult {
        val instruction = ChatMessage(
            role = "system",
            content = "The user cancelled a file-read request because the application reported that the file exceeded the configured read limit. Reason: $reason. This is a terminal explanation turn. Explain the original task goal, why the requested file(s) were needed if that is clear from the conversation, what the application blocked, and practical next steps. Be explicit about which file contents were not read and do not claim to have inspected them. Do not request or attempt tools. The application will not execute any further tool calls."
        )
        val finalMessages = listOf(instruction) + messages
        val completion = runCatching { complete(finalMessages, emptyList()) }.getOrNull()
        val final = if (completion != null && completion.toolCalls.isNullOrEmpty()) {
            completion
        } else {
            ChatMessage(
                role = "assistant",
                content = "The application stopped the agent because the requested file read exceeded the configured limit. The file contents were not read. Select a smaller section, read the file in bounded sections, or increase the file-read limit in Settings if appropriate. No further tools were executed."
            )
        }
        return AgentRunResult(
            finalMessage = final,
            transcript = messages + final,
            toolRounds = rounds,
            executedToolCalls = executedCalls,
            userCancelled = true
        )
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
    val executedToolCalls: Int,
    val userCancelled: Boolean = false
)

class AgentLoopUserCancelledException(message: String) : IllegalStateException(message)

class AgentLoopLimitException(message: String) : IllegalStateException(message)
