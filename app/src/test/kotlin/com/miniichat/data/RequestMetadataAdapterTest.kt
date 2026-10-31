package com.miniichat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestMetadataAdapterTest {
    @Test
    fun chatControlsMapToProviderFieldsAndRespectIndependentSwitches() {
        val provider = ProviderConfig(
            id = "openai",
            name = "OpenAI",
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
        assertEquals("@omit", values["reasoning_effort"])
    }

    @Test
    fun disabledControlsRemainOmittedWithoutLosingTheirValues() {
        val provider = ProviderConfig(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "test"
        )
        val settings = AppSettings(temperature = 1.2f, temperatureEnabled = false)

        val values = RequestMetadataAdapter.parameters(provider, "gpt-4o", settings)

        assertEquals("@omit", values["temperature"])
        assertEquals(1.2f, settings.temperature, 0.0001f)
    }

    @Test
    fun responsesReasoningUsesNestedEffortField() {
        val provider = ProviderConfig(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "test",
            apiProtocol = ProviderApiProtocol.RESPONSES
        )
        val settings = AppSettings(reasoningEnabled = true, reasoningEffort = "high")

        val values = RequestMetadataAdapter.parameters(provider, "o3", settings)

        assertEquals("{\"effort\":\"high\"}", values["reasoning"])
        assertEquals("@omit", values["temperature"])
        assertEquals("@omit", values["max_output_tokens"])
    }

    @Test
    fun unknownProvidersDoNotExposeOrSendUnverifiedControls() {
        val provider = ProviderConfig(
            id = "custom",
            name = "My Custom Endpoint",
            baseUrl = "https://example.invalid/v1",
            apiKey = "test"
        )

        assertTrue(RequestMetadataAdapter.supportedOptions(provider, "model-x").isEmpty())
        val values = RequestMetadataAdapter.parameters(provider, "model-x", AppSettings())
        assertTrue(values.values.all { it == "@omit" })
        assertFalse("temperature" in RequestMetadataAdapter.supportedOptions(provider, "model-x"))
    }
}
