package com.miniichat.api

import com.miniichat.data.ProviderApiProtocol
import com.miniichat.data.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataParametersTest {
    private val client = LlmClient()

    @Test
    fun chatCompletionsIncludesConfiguredMetadataAsJsonValues() {
        val provider = ProviderConfig(
            id = "test",
            name = "OpenAI",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test",
            metadataParameters = mapOf(
                "top_p" to "0.85",
                "reasoning_effort" to "\"high\"",
                "parallel_tool_calls" to "false"
            )
        )
        val body = Json.parseToJsonElement(
            client.buildRequestBody(provider, "model-x", listOf(ChatMessage("user", "hello")), false, 0.7f)
        ).jsonObject

        assertEquals("0.85", body["top_p"].toString())
        assertEquals("\"high\"", body["reasoning_effort"].toString())
        assertEquals("false", body["parallel_tool_calls"].toString())
    }

    @Test
    fun responsesIncludesConfiguredNativeMetadataAndOmitsUnconfiguredFields() {
        val provider = ProviderConfig(
            id = "test",
            name = "OpenAI",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test",
            apiProtocol = ProviderApiProtocol.RESPONSES,
            metadataParameters = mapOf("max_output_tokens" to "2048", "reasoning" to "{\"effort\":\"high\"}")
        )
        val body = Json.parseToJsonElement(
            client.buildResponsesRequestBody(provider, "model-x", listOf(ChatMessage("user", "hello")), false)
        ).jsonObject

        assertEquals("2048", body["max_output_tokens"].toString())
        assertEquals("{\"effort\":\"high\"}", body["reasoning"].toString())
        assertTrue("temperature" !in provider.metadataParameters)
        assertTrue("top_p" !in provider.metadataParameters)
    }

    @Test
    fun invalidJsonValueIsSafelySentAsString() {
        val provider = ProviderConfig(
            id = "test",
            name = "Custom",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test",
            metadataParameters = mapOf("vendor_option" to "custom-value")
        )
        val body = Json.parseToJsonElement(
            client.buildRequestBody(provider, "model-x", listOf(ChatMessage("user", "hello")), false, 0.7f)
        ).jsonObject
        assertEquals("\"custom-value\"", body["vendor_option"].toString())
    }

    @Test
    fun oldProviderJsonDefaultsToNoMetadataOverrides() {
        val oldJson = """{"id":"old","name":"OpenAI","baseUrl":"https://example.invalid/v1","apiKey":"key"}"""
        val provider = kotlinx.serialization.json.Json.decodeFromString(
            com.miniichat.data.ProviderConfig.serializer(), oldJson
        )
        assertTrue(provider.metadataParameters.isEmpty())
    }

    @Test
    fun catalogSeparatesProtocolSpecificFields() {
        val chatKeys = com.miniichat.data.MetadataParameterCatalog
            .forProtocol(ProviderApiProtocol.CHAT_COMPLETIONS).map { it.key }.toSet()
        val responsesKeys = com.miniichat.data.MetadataParameterCatalog
            .forProtocol(ProviderApiProtocol.RESPONSES).map { it.key }.toSet()
        assertTrue("max_output_tokens" !in chatKeys)
        assertTrue("max_output_tokens" in responsesKeys)
        assertTrue("max_tokens" in chatKeys)
        assertTrue("max_tokens" !in responsesKeys)
    }
    @Test
    fun modelOverridesTakePrecedenceOverProviderMetadata() {
        val provider = ProviderConfig(
            id = "test",
            name = "OpenAI",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test",
            metadataParameters = mapOf("top_p" to "0.8", "temperature" to "0.7"),
            metadataParametersByModel = mapOf("model-x" to """{"top_p":0.4,"temperature":0.2}""")
        )
        val body = Json.parseToJsonElement(
            client.buildRequestBody(provider, "model-x", listOf(ChatMessage("user", "hello")), false, 0.7f)
        ).jsonObject
        assertEquals("0.4", body["top_p"].toString())
        assertEquals("0.2", body["temperature"].toString())
    }

    @Test
    fun omitSentinelRemovesLegacyDefaultFields() {
        val provider = ProviderConfig(
            id = "test",
            name = "OpenAI",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test",
            metadataParameters = mapOf("temperature" to "@omit")
        )
        val body = Json.parseToJsonElement(
            client.buildRequestBody(provider, "model-x", listOf(ChatMessage("user", "hello")), false, 0.7f)
        ).jsonObject
        assertTrue("temperature" !in body)
    }

    @Test
    fun invalidModelOverrideJsonFailsClearly() {
        val provider = ProviderConfig(
            id = "test",
            name = "OpenAI",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test",
            metadataParametersByModel = mapOf("model-x" to "not-json")
        )
        val error = runCatching {
            client.buildRequestBody(provider, "model-x", listOf(ChatMessage("user", "hello")), false, 0.7f)
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("valid JSON object") == true)
    }

}
