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
