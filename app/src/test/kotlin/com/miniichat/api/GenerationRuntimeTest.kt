package com.miniichat.api

import com.miniichat.data.ProviderConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationRuntimeTest {
    private val runtime = ProviderGenerationRuntime(LlmClient())

    private fun provider(style: String) = ProviderConfig(
        id = style,
        name = style,
        baseUrl = "https://example.invalid",
        apiKey = "test-key",
        apiStyle = style
    )

    @Test
    fun openAiCompatibleProviderAdvertisesTextStreaming() {
        val capabilities = runtime.capabilitiesFor(provider("openai_compatible"))
        assertTrue(capabilities.streamingText)
        assertTrue(capabilities.toolCalling)
        assertFalse(capabilities.streamingToolCalls)
    }

    @Test
    fun nativeProtocolsAdvertiseToolSupportWithoutClaimingStreaming() {
        for (style in listOf("anthropic", "gemini")) {
            val capabilities = runtime.capabilitiesFor(provider(style))
            assertFalse(capabilities.streamingText)
            assertTrue(capabilities.toolCalling)
            assertFalse(capabilities.streamingToolCalls)
        }
    }

    @Test
    fun unknownProtocolFailsClosed() {
        val capabilities = runtime.capabilitiesFor(provider("unknown"))
        assertFalse(capabilities.streamingText)
        assertFalse(capabilities.toolCalling)
        assertFalse(capabilities.streamingToolCalls)
    }
}
