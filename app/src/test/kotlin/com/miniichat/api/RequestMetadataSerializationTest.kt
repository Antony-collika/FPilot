package com.miniichat.api

import com.miniichat.data.AppSettings
import com.miniichat.data.ProviderApiProtocol
import com.miniichat.data.ProviderConfig
import com.miniichat.data.RequestMetadataAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestMetadataSerializationTest {
    private val client = LlmClient()

    @Test
    fun enabledChatControlsAppearInTheActualRequestBody() {
        val provider = ProviderConfig("openai", "OpenAI", "https://api.openai.com/v1", "key")
        val settings = AppSettings(
            temperature = 0.35f, temperatureEnabled = true,
            topP = 0.8f, topPEnabled = true,
            maxOutputTokens = 4096, maxOutputTokensEnabled = true
        )
        val body = Json.parseToJsonElement(
            client.buildRequestBody(
                provider, "gpt-4o", listOf(ChatMessage("user", "hello")), false, settings.temperature,
                requestMetadata = RequestMetadataAdapter.parameters(provider, "gpt-4o", settings)
            )
        ).jsonObject

        assertEquals("0.35", body["temperature"].toString())
        assertEquals("0.8", body["top_p"].toString())
        assertEquals("4096", body["max_completion_tokens"].toString())
        assertFalse("max_tokens" in body)
    }

    @Test
    fun disabledChatControlsAreOmittedEvenIfLegacyProviderValuesExist() {
        val provider = ProviderConfig(
            "openai", "OpenAI", "https://api.openai.com/v1", "key",
            metadataParameters = mapOf("temperature" to "1.5", "top_p" to "0.9", "max_completion_tokens" to "9000")
        )
        val settings = AppSettings(temperature = 1.1f, temperatureEnabled = false, topPEnabled = false)
        val body = Json.parseToJsonElement(
            client.buildRequestBody(
                provider, "gpt-4o", listOf(ChatMessage("user", "hello")), false, settings.temperature,
                requestMetadata = RequestMetadataAdapter.parameters(provider, "gpt-4o", settings)
            )
        ).jsonObject

        assertFalse("temperature" in body)
        assertFalse("top_p" in body)
        assertFalse("max_completion_tokens" in body)
    }

    @Test
    fun responsesControlsUseResponsesNativeFieldNames() {
        val provider = ProviderConfig(
            "openai", "OpenAI", "https://api.openai.com/v1", "key",
            apiProtocol = ProviderApiProtocol.RESPONSES
        )
        val settings = AppSettings(maxOutputTokens = 3072, maxOutputTokensEnabled = true)
        val body = Json.parseToJsonElement(
            client.buildResponsesRequestBody(
                provider, "gpt-4.1", listOf(ChatMessage("user", "hello")), false,
                requestMetadata = RequestMetadataAdapter.parameters(provider, "gpt-4.1", settings)
            )
        ).jsonObject

        assertEquals("3072", body["max_output_tokens"].toString())
        assertFalse("max_tokens" in body)
        assertFalse("max_completion_tokens" in body)
    }
}
