package com.miniichat.agent

import com.miniichat.api.ChatMessage
import com.miniichat.api.ToolCall
import com.miniichat.api.ToolDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    @Test
    fun executesToolAndReturnsFinalAssistantMessage() = runBlocking {
        var completionCount = 0
        val tool = object : AgentTool {
            override val definition = ToolDefinition(
                name = "echo",
                description = "Return the provided value.",
                parameters = Json.parseToJsonElement(
                    """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}"""
                ).jsonObject
            )

            override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject): String =
                "ok:${arguments["value"]?.jsonPrimitive?.contentOrNull}"
        }
        val registry = AgentToolRegistry(listOf(tool))
        val loop = AgentLoop(registry) { messages, tools ->
            completionCount++
            assertEquals("echo", tools.single().name)
            when (completionCount) {
                1 -> ChatMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call-1",
                            name = "echo",
                            arguments = """{"value":"first"}"""
                        )
                    )
                )
                2 -> {
                    assertTrue(messages.any { it.role == "tool" && it.content == "ok:first" })
                    ChatMessage(role = "assistant", content = "Finished.")
                }
                else -> error("Unexpected extra model completion.")
            }
        }

        val result = loop.run(listOf(ChatMessage(role = "user", content = "Run the tool.")))

        assertEquals("Finished.", result.finalMessage.content)
        assertEquals(1, result.toolRounds)
        assertEquals(1, result.executedToolCalls)
        assertEquals(2, completionCount)
    }
}
