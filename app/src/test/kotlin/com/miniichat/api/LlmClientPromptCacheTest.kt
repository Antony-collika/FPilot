package com.miniichat.api

import com.miniichat.data.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LlmClientPromptCacheTest {
    private val client = LlmClient()

    @Test
    fun addsStableConversationKeyForOfficialMistralApi() {
        val body = client.buildRequestBody(
            provider = provider(baseUrl = "https://api.mistral.ai/v1"),
            modelId = "mistral-small-latest",
            messages = listOf(ChatMessage(role = "user", content = "Hello")),
            stream = false,
            temperature = 0.7f,
            conversationId = "conversation-123"
        )

        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("fpilot-conversation-123", json["prompt_cache_key"]?.jsonPrimitive?.content)
    }

    @Test
    fun doesNotAddMistralKeyForOtherProviders() {
        val body = client.buildRequestBody(
            provider = provider(baseUrl = "https://api.groq.com/openai/v1"),
            modelId = "openai/gpt-oss-20b",
            messages = listOf(ChatMessage(role = "user", content = "Hello")),
            stream = false,
            temperature = 0.7f,
            conversationId = "conversation-123"
        )

        val json = Json.parseToJsonElement(body).jsonObject
        assertFalse(json.containsKey("prompt_cache_key"))
    }

    @Test
    fun omitsMistralKeyWhenConversationIdIsMissing() {
        val body = client.buildRequestBody(
            provider = provider(baseUrl = "https://api.mistral.ai/v1"),
            modelId = "mistral-small-latest",
            messages = listOf(ChatMessage(role = "user", content = "Hello")),
            stream = false,
            temperature = 0.7f
        )

        val json = Json.parseToJsonElement(body).jsonObject
        assertFalse(json.containsKey("prompt_cache_key"))
    }

    private fun provider(baseUrl: String) = ProviderConfig(
        id = "provider-id",
        name = "Test provider",
        baseUrl = baseUrl,
        apiKey = "test-key"
    )
}
