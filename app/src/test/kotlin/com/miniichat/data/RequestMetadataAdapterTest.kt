package com.miniichat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestMetadataAdapterTest {
    @Test
    fun mapsCommonControlsUsingProviderNativeChatFieldNames() {
        val provider = ProviderConfig(
            id = "openai",
            name = "Renamed endpoint",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "test"
        )
        val settings = AppSettings(
            temperature = 0.35f,
            temperatureEnabled = true,
            topP = 0.8f,
            topPEnabled = true,
            maxOutputTokens = 4096,
            maxOutputTokensEnabled = true
        )

        val values = RequestMetadataAdapter.parameters(provider, "gpt-4o", settings)

        assertEquals("0.35", values["temperature"])
        assertEquals("0.8", values["top_p"])
        assertEquals("4096", values["max_completion_tokens"])
        assertFalse("max_tokens" in values)
        assertFalse("reasoning_effort" in values)
    }

    @Test
    fun disabledControlsAreAbsentInsteadOfWritingOmitTombstones() {
        val provider = ProviderConfig(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "test"
        )
        val settings = AppSettings(
            temperature = 1.2f,
            temperatureEnabled = false,
            topPEnabled = false,
            maxOutputTokensEnabled = false,
            reasoningEnabled = false
        )

        val values = RequestMetadataAdapter.parameters(provider, "gpt-4o", settings)

        assertTrue(values.isEmpty())
        assertEquals(1.2f, settings.temperature, 0.0001f)
    }

    @Test
    fun responsesUsesNestedReasoningAndOutputTokenFields() {
        val provider = ProviderConfig(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "test",
            apiProtocol = ProviderApiProtocol.RESPONSES
        )
        val settings = AppSettings(
            maxOutputTokens = 8192,
            maxOutputTokensEnabled = true,
            reasoningEnabled = true,
            reasoningEffort = "high",
            allowUnverifiedMetadata = true
        )

        val values = RequestMetadataAdapter.parameters(provider, "o3", settings)

        assertEquals("8192", values["max_output_tokens"])
        assertEquals("\"high\"", values["reasoning.effort"])
        assertFalse("reasoning_effort" in values)
        assertFalse("temperature" in values)
    }

    @Test
    fun unknownProviderNeedsExplicitPermissionForCommonControls() {
        val provider = ProviderConfig(
            id = "custom",
            name = "My Custom Endpoint",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test"
        )
        val settings = AppSettings(temperature = 0.4f, temperatureEnabled = true)

        assertTrue(RequestMetadataAdapter.parameters(provider, "model-x", settings).isEmpty())
        assertEquals(
            CapabilityStatus.UNKNOWN,
            ProviderCapabilityRegistry.capability(provider, "model-x", "temperature").status
        )

        val optedIn = settings.copy(allowUnverifiedMetadata = true)
        assertEquals("0.4", RequestMetadataAdapter.parameters(provider, "model-x", optedIn)["temperature"])
    }

    @Test
    fun conditionalModelCapabilitiesRequireGlobalOptIn() {
        val provider = ProviderConfig(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "test"
        )
        val settings = AppSettings(temperature = 0.5f, temperatureEnabled = true)

        assertTrue(RequestMetadataAdapter.parameters(provider, "o3", settings).isEmpty())
        assertEquals(
            "0.5",
            RequestMetadataAdapter.parameters(
                provider, "o3", settings.copy(allowUnverifiedMetadata = true)
            )["temperature"]
        )
    }

    @Test
    fun providerIdentityCanBeExplicitForAnOpenAiCompatibleCustomDomain() {
        val provider = ProviderConfig(
            id = "proxy",
            name = "My Gateway",
            baseUrl = "https://gateway.example.com/v1",
            apiKey = "test",
            providerType = ProviderKinds.OPENAI
        )
        val settings = AppSettings(temperature = 0.6f, temperatureEnabled = true)

        assertEquals("0.6", RequestMetadataAdapter.parameters(provider, "gpt-4o", settings)["temperature"])
    }
}
