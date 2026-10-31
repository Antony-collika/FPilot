package com.miniichat.api

import com.miniichat.data.ProviderApiProtocol
import com.miniichat.data.ProviderConfig
import com.miniichat.data.ProviderKinds
import com.miniichat.data.MetadataParameterCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataParametersTest {
    private val client = LlmClient()
    private val message = listOf(ChatMessage("user", "hello"))

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
            client.buildRequestBody(provider, "model-x", message, false, 0.7f)
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
            metadataParameters = mapOf("max_output_tokens" to "2048", "reasoning.effort" to "\"high\"")
        )
        val body = Json.parseToJsonElement(
            client.buildResponsesRequestBody(provider, "model-x", message, false)
        ).jsonObject

        assertEquals("2048", body["max_output_tokens"].toString())
        assertEquals("\"high\"", body["reasoning"]!!.jsonObject["effort"].toString())
        assertFalse("temperature" in body)
        assertFalse("top_p" in body)
    }

    @Test
    fun chatBodyDoesNotSendDefaultTemperatureWhenNoLayerConfiguresIt() {
        val provider = ProviderConfig(
            id = "test",
            name = "Custom",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test"
        )
        val body = Json.parseToJsonElement(
            client.buildRequestBody(provider, "model-x", message, false, 0.7f)
        ).jsonObject
        assertFalse("temperature" in body)
    }

    @Test
    fun invalidJsonLiteralIsRetainedAsAString() {
        val provider = ProviderConfig(
            id = "test",
            name = "Custom",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test",
            metadataParameters = mapOf("vendor_option" to "custom-value")
        )
        val body = Json.parseToJsonElement(
            client.buildRequestBody(provider, "model-x", message, false, 0.7f)
        ).jsonObject
        assertEquals("\"custom-value\"", body["vendor_option"].toString())
    }

    @Test
    fun oldProviderJsonDefaultsToNoMetadataOverrides() {
        val oldJson = """{"id":"old","name":"OpenAI","baseUrl":"https://example.invalid/v1","apiKey":"key"}"""
        val provider = Json.decodeFromString(ProviderConfig.serializer(), oldJson)
        assertTrue(provider.metadataParameters.isEmpty())
        assertEquals(ProviderKinds.AUTO, provider.providerType)
    }

    @Test
    fun catalogSeparatesProtocolSpecificFieldsAndCanRoundTripTypedOverrides() {
        val chatKeys = MetadataParameterCatalog.forProtocol(ProviderApiProtocol.CHAT_COMPLETIONS).map { it.key }.toSet()
        val responsesKeys = MetadataParameterCatalog.forProtocol(ProviderApiProtocol.RESPONSES).map { it.key }.toSet()
        assertFalse("max_output_tokens" in chatKeys)
        assertTrue("max_output_tokens" in responsesKeys)
        assertTrue("max_tokens" in chatKeys)
        assertFalse("max_tokens" in responsesKeys)

        val values = mapOf("temperature" to "0.5", "tool_choice" to "\"auto\"", "enabled" to "false")
        assertEquals(values, MetadataParameterCatalog.parseOverrides(MetadataParameterCatalog.formatOverrides(values)))
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
            client.buildRequestBody(provider, "model-x", message, false, 0.7f)
        ).jsonObject
        assertEquals("0.4", body["top_p"].toString())
        assertEquals("0.2", body["temperature"].toString())
    }

    @Test
    fun metadataResolutionUsesExplicitLayerPrecedenceAndMergesNestedObjects() {
        val provider = ProviderConfig(
            id = "test",
            name = "Custom",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test",
            extraBody = mapOf("top_p" to "0.1", "reasoning.keep" to "true"),
            metadataParameters = mapOf("top_p" to "0.3", "reasoning.max_tokens" to "512"),
            metadataParametersByModel = mapOf(
                "model-x" to """{"top_p":0.4,"reasoning":{"effort":"high"}}"""
            )
        )
        val body = Json.parseToJsonElement(
            client.buildRequestBody(
                provider, "model-x", message, false, 0.7f,
                requestMetadata = mapOf("top_p" to "0.5", "reasoning.effort" to "\"low\""),
                globalMetadata = mapOf("top_p" to "0.2", "global_only" to "true", "reasoning.effort" to "\"medium\"")
            )
        ).jsonObject

        assertEquals("0.5", body["top_p"].toString())
        assertEquals("true", body["global_only"].toString())
        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("\"low\"", reasoning["effort"].toString())
        assertEquals("512", reasoning["max_tokens"].toString())
        assertEquals("true", reasoning["keep"].toString())
    }

    @Test
    fun explicitDisabledOverrideRemovesOnlyTheInheritedPathAndNullRemainsAValue() {
        val provider = ProviderConfig(
            id = "test",
            name = "Custom",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test",
            metadataParameters = mapOf("temperature" to "0.7", "feature_flag" to "true"),
            metadataParametersByModel = mapOf("model-x" to """{"temperature":"@disabled","nullable":null}""")
        )
        val body = Json.parseToJsonElement(
            client.buildRequestBody(provider, "model-x", message, false, 0.7f)
        ).jsonObject

        assertFalse("temperature" in body)
        assertEquals("true", body["feature_flag"].toString())
        assertEquals("null", body["nullable"].toString())
    }

    @Test
    fun legacyOmitSentinelStillRemovesInheritedDefaults() {
        val provider = ProviderConfig(
            id = "test",
            name = "OpenAI",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test",
            metadataParameters = mapOf("temperature" to "0.7"),
            metadataParametersByModel = mapOf("model-x" to """{"temperature":"@omit"}""")
        )
        val body = Json.parseToJsonElement(
            client.buildRequestBody(provider, "model-x", message, false, 0.7f)
        ).jsonObject
        assertFalse("temperature" in body)
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
            client.buildRequestBody(provider, "model-x", message, false, 0.7f)
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("valid JSON object") == true)
    }

    @Test
    fun customMetadataDoesNotOverwriteTransportFields() {
        val provider = ProviderConfig(
            id = "test",
            name = "Custom",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test",
            metadataParameters = mapOf("model" to "\"wrong\"", "stream" to "false", "temperature" to "0.25")
        )
        val body = Json.parseToJsonElement(
            client.buildRequestBody(provider, "model-x", message, true, 0.7f)
        ).jsonObject
        assertEquals("\"model-x\"", body["model"].toString())
        assertEquals("true", body["stream"].toString())
        assertEquals("0.25", body["temperature"].toString())
    }
}
