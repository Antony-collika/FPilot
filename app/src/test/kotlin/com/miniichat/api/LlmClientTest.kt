package com.miniichat.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import com.miniichat.data.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LlmClientTest {
    private val client = LlmClient()


    @Test
    fun buildsResponsesRequestWithInputFunctionToolsAndToolOutputs() {
        val provider = ProviderConfig(
            id = "test",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "test",
            apiProtocol = com.miniichat.data.ProviderApiProtocol.RESPONSES
        )
        val tool = ToolDefinition(
            name = "read_file",
            description = "Read a file.",
            parameters = Json.parseToJsonElement(
                """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
            ).jsonObject
        )
        val body = Json.parseToJsonElement(
            client.buildResponsesRequestBody(
                provider = provider,
                modelId = "gpt-5.6-sol",
                messages = listOf(
                    ChatMessage(role = "system", content = "You are useful."),
                    ChatMessage(role = "user", content = "Read README.md."),
                    ChatMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(ToolCall("call_123", "read_file", """{"path":"README.md"}"""))
                    ),
                    ChatMessage(role = "tool", content = "file contents", toolCallId = "call_123", name = "read_file")
                ),
                stream = false,
                tools = listOf(tool)
            )
        ).jsonObject

        assertEquals("gpt-5.6-sol", body["model"].toString().trim('"'))
        assertEquals("false", body["stream"].toString())
        val input = body["input"]!!.jsonArray
        assertEquals("system", input[0].jsonObject["role"].toString().trim('"'))
        assertEquals("function_call", input[2].jsonObject["type"].toString().trim('"'))
        assertEquals("call_123", input[2].jsonObject["call_id"].toString().trim('"'))
        assertEquals("function_call_output", input[3].jsonObject["type"].toString().trim('"'))
        assertEquals("call_123", input[3].jsonObject["call_id"].toString().trim('"'))
        val function = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("read_file", function["name"].toString().trim('"'))
        assertEquals("false", function["strict"].toString())
        assertEquals("auto", body["tool_choice"].toString().trim('"'))
    }

    @Test
    fun parsesResponsesAndReplaysOpaqueReasoningAndFunctionCallItems() {
        val response = """
            {
              "id": "resp_123",
              "output": [
                {
                  "type": "reasoning",
                  "id": "rs_123",
                  "encrypted_content": "opaque-reasoning-payload",
                  "summary": []
                },
                {
                  "type": "message",
                  "id": "msg_123",
                  "role": "assistant",
                  "content": [{"type":"output_text","text":"Inspecting the file."}]
                },
                {
                  "type": "function_call",
                  "id": "fc_123",
                  "call_id": "call_123",
                  "name": "read_file",
                  "arguments": "{\"path\":\"README.md\"}"
                }
              ]
            }
        """.trimIndent()
        val parsed = client.parseResponsesCompletionResponse(response)
        assertEquals("Inspecting the file.", parsed.content)
        assertEquals("call_123", parsed.toolCalls!!.single().id)
        assertEquals("read_file", parsed.toolCalls!!.single().name)

        val provider = ProviderConfig(
            id = "test",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "test",
            apiProtocol = com.miniichat.data.ProviderApiProtocol.RESPONSES
        )
        val input = Json.parseToJsonElement(
            client.buildResponsesRequestBody(
                provider = provider,
                modelId = "gpt-5.6-sol",
                messages = listOf(
                    parsed,
                    ChatMessage(role = "tool", content = "contents", toolCallId = "call_123")
                ),
                stream = false
            )
        ).jsonObject["input"]!!.jsonArray

        assertEquals("reasoning", input[0].jsonObject["type"].toString().trim('"'))
        assertEquals("opaque-reasoning-payload", input[0].jsonObject["encrypted_content"].toString().trim('"'))
        assertEquals("function_call", input[2].jsonObject["type"].toString().trim('"'))
        assertEquals("function_call_output", input[3].jsonObject["type"].toString().trim('"'))
    }

    @Test
    fun mapsImagesToResponsesInputImageParts() {
        val provider = ProviderConfig(
            id = "test",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "test",
            apiProtocol = com.miniichat.data.ProviderApiProtocol.RESPONSES
        )
        val body = Json.parseToJsonElement(
            client.buildResponsesRequestBody(
                provider = provider,
                modelId = "gpt-5.6-sol",
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        content = "Describe this image.",
                        parts = listOf(
                            ChatPart(type = "text", text = "Describe this image."),
                            ChatPart(type = "image_url", imageUrl = ChatPart.ImageUrl("data:image/png;base64,AAAA", "high"))
                        )
                    )
                ),
                stream = false
            )
        ).jsonObject
        val parts = body["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("input_text", parts[0].jsonObject["type"].toString().trim('"'))
        assertEquals("input_image", parts[1].jsonObject["type"].toString().trim('"'))
        assertEquals("high", parts[1].jsonObject["detail"].toString().trim('"'))
    }

    @Test
    fun parsesNullToolCallsAsNoCalls() {
        val message = client.parseCompletionResponse(
            """{"choices":[{"message":{"role":"assistant","content":"done","tool_calls":null}}]}"""
        )

        assertEquals("done", message.content)
        assertNull(message.toolCalls)
    }

    @Test
    fun parsesMissingToolCallsAsNoCalls() {
        val message = client.parseCompletionResponse(
            """{"choices":[{"message":{"role":"assistant","content":"done"}}]}"""
        )

        assertEquals("done", message.content)
        assertNull(message.toolCalls)
    }


    @Test
    fun preservesMessageLevelThoughtSignatureWithoutToolCallsAndInRequestHistory() {
        val response = """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": "The answer is 42.",
                  "extra_content": {
                    "google": {"thought_signature": "message-signature"}
                  }
                }
              }]
            }
        """.trimIndent()
        val expected = Json.parseToJsonElement(
            """{"google":{"thought_signature":"message-signature"}}"""
        ).jsonObject

        val parsed = client.parseCompletionResponse(response)
        assertEquals("The answer is 42.", parsed.content)
        assertNull(parsed.toolCalls)
        assertEquals(expected, parsed.extraContent)

        val provider = ProviderConfig(
            id = "test",
            name = "Gemini",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test"
        )
        val request = client.buildRequestBody(
            provider = provider,
            modelId = "gemini-test",
            messages = listOf(parsed),
            stream = false,
            temperature = 0f
        )
        val serializedMessage = Json.parseToJsonElement(request)
            .jsonObject["messages"]!!.jsonArray.single().jsonObject
        assertEquals(expected, serializedMessage["extra_content"]?.jsonObject)
    }

    @Test
    fun parsesMessageLevelAndToolCallLevelMetadataIndependently() {
        val response = """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": null,
                  "extra_content": {"google": {"thought_signature": "message-sig"}},
                  "tool_calls": [{
                    "id": "call_1",
                    "function": {"name": "read_file", "arguments": "{}"},
                    "extra_content": {"google": {"thought_signature": "tool-sig"}}
                  }]
                }
              }]
            }
        """.trimIndent()
        val parsed = client.parseCompletionResponse(response)
        assertEquals(
            "message-sig",
            parsed.extraContent?.get("google")?.jsonObject?.get("thought_signature")?.toString()?.trim('"')
        )
        assertEquals(
            "tool-sig",
            parsed.toolCalls!!.single().extraContent
                ?.get("google")?.jsonObject?.get("thought_signature")?.toString()?.trim('"')
        )
    }

    @Test
    fun preservesGeminiThoughtSignatureWhenParsingAndSerializingToolCall() {
        val response = """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "read_file", "arguments": "{\"path\":\"README.md\"}"},
                    "extra_content": {
                      "google": {"thought_signature": "signature-must-be-preserved"}
                    }
                  }]
                }
              }]
            }
        """.trimIndent()

        val parsed = client.parseCompletionResponse(response)
        val call = parsed.toolCalls!!.single()
        val expectedExtraContent = Json.parseToJsonElement(
            """{"google":{"thought_signature":"signature-must-be-preserved"}}"""
        ).jsonObject

        assertEquals(expectedExtraContent, call.extraContent)

        val serialized = client.serializeToolCall(call)
        assertEquals(expectedExtraContent, serialized["extra_content"]?.jsonObject)
        assertEquals("call_1", serialized["id"].toString().trim('"'))
    }

    @Test
    fun preservesSignatureOnOriginalCallWhenMultipleCallsAreReturned() {
        val response = """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [
                    {
                      "id": "call_1",
                      "function": {"name": "read_file", "arguments": "{}"},
                      "extra_content": {"google": {"thought_signature": "sig_first"}}
                    },
                    {
                      "id": "call_2",
                      "function": {"name": "list_files", "arguments": "{}"}
                    }
                  ]
                }
              }]
            }
        """.trimIndent()

        val calls = client.parseCompletionResponse(response).toolCalls!!

        assertNotNull(calls[0].extraContent)
        assertNull(calls[1].extraContent)
        assertEquals(
            calls[0].extraContent,
            client.serializeToolCall(calls[0])["extra_content"]?.jsonObject
        )
    }
}
