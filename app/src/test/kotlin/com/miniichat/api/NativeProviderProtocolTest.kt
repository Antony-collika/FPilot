package com.miniichat.api

import com.miniichat.data.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProviderProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val readTool = ToolDefinition(
        name = "read_file",
        description = "Read one workspace file.",
        parameters = json.parseToJsonElement(
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
        ).jsonObject
    )

    @Test
    fun buildsAnthropicMessagesAndToolSchemasFromNormalizedConversation() {
        val messages = listOf(
            ChatMessage(role = "system", content = "Follow the workspace policy."),
            ChatMessage(role = "user", content = "Read README.md."),
            ChatMessage(
                role = "assistant",
                content = "I will read it.",
                toolCalls = listOf(ToolCall("call-1", "read_file", """{"path":"README.md"}"""))
            ),
            ChatMessage(role = "tool", content = "# README", toolCallId = "call-1", name = "read_file")
        )

        val body = json.parseToJsonElement(
            NativeProviderProtocol.anthropicRequestBody("claude-example", AppSettings(temperature = 0.2f), messages, listOf(readTool))
        ).jsonObject

        assertEquals("claude-example", body["model"]!!.jsonPrimitive.content)
        assertEquals("Follow the workspace policy.", body["system"]!!.jsonPrimitive.content)
        val turns = body["messages"]!!.jsonArray
        assertEquals(3, turns.size)
        assertEquals("assistant", turns[1].jsonObject["role"]!!.jsonPrimitive.content)
        val assistantBlocks = turns[1].jsonObject["content"]!!.jsonArray
        assertEquals("tool_use", assistantBlocks.last().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("README.md", assistantBlocks.last().jsonObject["input"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("tool_result", turns[2].jsonObject["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("read_file", body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesAnthropicTextAndToolUseIntoNormalizedAssistantMessage() {
        val parsed = NativeProviderProtocol.parseAnthropicResponse(
            """
            {
              "content": [
                {"type":"text","text":"I found the file."},
                {"type":"tool_use","id":"toolu_123","name":"read_file","input":{"path":"README.md"}}
              ],
              "stop_reason":"tool_use"
            }
            """.trimIndent()
        )

        assertEquals("assistant", parsed.role)
        assertEquals("I found the file.", parsed.content)
        assertEquals("toolu_123", parsed.toolCalls!!.single().id)
        assertEquals("read_file", parsed.toolCalls!!.single().name)
        assertEquals("""{"path":"README.md"}""", parsed.toolCalls!!.single().arguments)
    }

    @Test
    fun buildsGeminiSystemInstructionFunctionDeclarationsAndFunctionResponses() {
        val messages = listOf(
            ChatMessage(role = "system", content = "Keep the answer concise."),
            ChatMessage(role = "user", content = "Read the project file."),
            ChatMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(ToolCall("call-1", "read_file", """{"path":"README.md"}"""))
            ),
            ChatMessage(role = "tool", content = "project contents", toolCallId = "call-1", name = "read_file")
        )
        val body = json.parseToJsonElement(
            NativeProviderProtocol.geminiRequestBody(AppSettings(temperature = 0.4f), messages, listOf(readTool))
        ).jsonObject

        assertEquals(
            "Keep the answer concise.",
            body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        )
        val functionDeclarations = body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals("read_file", functionDeclarations.single().jsonObject["name"]!!.jsonPrimitive.content)
        val functionResponse = body["contents"]!!.jsonArray.last().jsonObject["parts"]!!.jsonArray.single().jsonObject["functionResponse"]!!.jsonObject
        assertEquals("read_file", functionResponse["name"]!!.jsonPrimitive.content)
        assertEquals("project contents", functionResponse["response"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesGeminiFunctionCallsAndPreservesThoughtSignature() {
        val parsed = NativeProviderProtocol.parseGeminiResponse(
            """
            {
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [
                    {"text":"I will inspect the file."},
                    {
                      "functionCall": {
                        "id":"call-2",
                        "name":"read_file",
                        "args":{"path":"README.md"}
                      },
                      "thoughtSignature":"signed-payload"
                    }
                  ]
                },
                "finishReason":"STOP"
              }]
            }
            """.trimIndent()
        )

        assertEquals("I will inspect the file.", parsed.content)
        val call = parsed.toolCalls!!.single()
        assertEquals("call-2", call.id)
        assertEquals("read_file", call.name)
        assertTrue(call.arguments.contains("README.md"))
        assertNotNull(call.extraContent)
        assertEquals(
            "signed-payload",
            call.extraContent!!["thoughtSignature"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun modelDiscoveryFiltersGeminiModelsWithoutGenerateContentSupport() {
        val models = NativeProviderProtocol.parseGeminiModels(
            """
            {
              "models": [
                {"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]},
                {"name":"models/embedding-a","supportedGenerationMethods":["embedContent"]},
                {"name":"models/gemini-b","supportedGenerationMethods":["generateContent"]}
              ]
            }
            """.trimIndent()
        )
        assertEquals(listOf("gemini-a", "gemini-b"), models)
        assertEquals(
            listOf("claude-a", "claude-b"),
            NativeProviderProtocol.parseAnthropicModels(
                """{"data":[{"id":"claude-b"},{"id":"claude-a"}]}"""
            )
        )
    }

    @Test
    fun forwardsCustomBodyParametersWithoutAllowingCoreRequestOverrides() {
        val anthropic = json.parseToJsonElement(
            NativeProviderProtocol.anthropicRequestBody(
                "claude-example",
                AppSettings(),
                listOf(ChatMessage(role = "user", content = "Hi")),
                emptyList(),
                mapOf(
                    "metadata" to """{"user_id":"workspace-user"}""",
                    "max_tokens" to "1"
                )
            )
        ).jsonObject
        assertEquals(
            "workspace-user",
            anthropic["metadata"]!!.jsonObject["user_id"]!!.jsonPrimitive.content
        )
        assertEquals(8192, anthropic["max_tokens"]!!.jsonPrimitive.content.toInt())

        val gemini = json.parseToJsonElement(
            NativeProviderProtocol.geminiRequestBody(
                AppSettings(),
                listOf(ChatMessage(role = "user", content = "Hi")),
                emptyList(),
                mapOf(
                    "safetySettings" to """[{"category":"HARM_CATEGORY_HARASSMENT","threshold":"BLOCK_ONLY_HIGH"}]""",
                    "contents" to "[]"
                )
            )
        ).jsonObject
        assertTrue(gemini["safetySettings"]!!.jsonArray.isNotEmpty())
        assertTrue(gemini["contents"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun excludesGeminiInternalThoughtTextFromVisibleAssistantContent() {
        val parsed = NativeProviderProtocol.parseGeminiResponse(
            """
            {
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [
                    {"text":"internal reasoning","thought":true},
                    {"text":"Visible answer","thought":false}
                  ]
                }
              }]
            }
            """.trimIndent()
        )
        assertEquals("Visible answer", parsed.content)
    }
}
