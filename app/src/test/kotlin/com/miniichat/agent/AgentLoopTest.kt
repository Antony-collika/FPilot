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
        val loop = AgentLoop(registry, complete = { messages, tools ->
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
        })

        val result = loop.run(listOf(ChatMessage(role = "user", content = "Run the tool.")))

        assertEquals("Finished.", result.finalMessage.content)
        assertEquals(1, result.toolRounds)
        assertEquals(1, result.executedToolCalls)
        assertEquals(2, completionCount)
    }
    @Test
    fun userCancellationStopsRemainingToolCallsAndUsesToolFreeFinalCompletion() = runBlocking {
        var completionCount = 0
        var executed = 0
        val readTool = object : AgentTool {
            override val definition = ToolDefinition(
                name = "read_file",
                description = "Read a file.",
                parameters = Json.parseToJsonElement("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""").jsonObject
            )
            override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject): String {
                executed++
                return "should not execute"
            }
        }
        val otherTool = object : AgentTool {
            override val definition = ToolDefinition(
                name = "other",
                description = "Another tool.",
                parameters = Json.parseToJsonElement("""{"type":"object","properties":{} }""").jsonObject
            )
            override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject): String {
                executed++
                return "also should not execute"
            }
        }
        val loop = AgentLoop(
            registry = AgentToolRegistry(listOf(readTool, otherTool)),
            complete = { messages, tools ->
                completionCount++
                when (completionCount) {
                    1 -> ChatMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(
                            ToolCall("read-1", "read_file", """{"path":"large.md"}"""),
                            ToolCall("other-1", "other", "{}")
                        )
                    )
                    2 -> {
                        assertTrue(tools.isEmpty())
                        assertTrue(messages.any { it.role == "tool" && it.content.contains("cancelled") })
                        assertTrue(messages.any { it.role == "tool" && it.content.contains("Not executed") })
                        ChatMessage(role = "assistant", content = "The application blocked the read.")
                    }
                    else -> error("The loop must not resume after cancellation.")
                }
            },
            confirmToolCall = { call, _ ->
                if (call.name == "read_file") throw AgentLoopUserCancelledException("oversized file")
                true
            }
        )

        val result = loop.run(listOf(ChatMessage(role = "user", content = "Analyze this file.")))

        assertEquals(0, executed)
        assertEquals(2, completionCount)
        assertTrue(result.userCancelled)
        assertEquals("The application blocked the read.", result.finalMessage.content)
    }

    @Test
    fun largeToolResultIsPassedToModelWithoutTruncation() = runBlocking {
        var completionCount = 0
        val expectedResult = "x".repeat(1_000)
        val tool = object : AgentTool {
            override val definition = ToolDefinition(
                name = "large",
                description = "Return a large result.",
                parameters = Json.parseToJsonElement("""{"type":"object","properties":{} }""").jsonObject
            )
            override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject): String = expectedResult
        }
        val loop = AgentLoop(
            registry = AgentToolRegistry(listOf(tool)),
            complete = { messages, _ ->
                completionCount++
                when (completionCount) {
                    1 -> ChatMessage(role = "assistant", content = "", toolCalls = listOf(ToolCall("large-1", "large", "{}")))
                    2 -> {
                        assertEquals(expectedResult, messages.last { it.role == "tool" }.content)
                        ChatMessage(role = "assistant", content = "Done.")
                    }
                    else -> error("Unexpected extra completion.")
                }
            }
        )

        loop.run(listOf(ChatMessage(role = "user", content = "Run.")))
        assertEquals(2, completionCount)
    }

    @Test
    fun terminalExplanationIgnoresUnexpectedToolCalls() = runBlocking {
        var completionCount = 0
        var executed = 0
        val tool = object : AgentTool {
            override val definition = ToolDefinition(
                name = "read_file",
                description = "Read a file.",
                parameters = Json.parseToJsonElement("""{"type":"object","properties":{} }""").jsonObject
            )
            override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject): String {
                executed++
                return "unexpected"
            }
        }
        val loop = AgentLoop(
            registry = AgentToolRegistry(listOf(tool)),
            complete = { _, tools ->
                completionCount++
                if (completionCount == 1) {
                    ChatMessage(role = "assistant", content = "", toolCalls = listOf(ToolCall("read-1", "read_file", "{}")))
                } else {
                    assertTrue(tools.isEmpty())
                    ChatMessage(role = "assistant", content = "", toolCalls = listOf(ToolCall("read-2", "read_file", "{}")))
                }
            },
            confirmToolCall = { _, _ -> throw AgentLoopUserCancelledException("user cancelled") }
        )

        val result = loop.run(listOf(ChatMessage(role = "user", content = "Inspect the file.")))

        assertEquals(2, completionCount)
        assertEquals(0, executed)
        assertTrue(result.userCancelled)
        assertTrue(result.finalMessage.content.contains("No further tools were executed"))
    }

