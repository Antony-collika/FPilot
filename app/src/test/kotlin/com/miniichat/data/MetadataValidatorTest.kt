package com.miniichat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataValidatorTest {
    @Test
    fun acceptsTypedKnownFieldsAndNestedPaths() {
        val issues = MetadataValidator.validate(
            mapOf(
                "temperature" to "0.7",
                "max_completion_tokens" to "2048",
                "response_format" to """{"type":"json_object"}""",
                "reasoning" to """{"effort":"high"}"""
            ),
            ProviderApiProtocol.CHAT_COMPLETIONS
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun rejectsMalformedJsonReservedKeysAndKnownTypeMismatch() {
        val issues = MetadataValidator.validate(
            mapOf(
                "temperature" to "\"hot\"",
                "top_p" to "not-json",
                "messages" to "[]"
            ),
            ProviderApiProtocol.CHAT_COMPLETIONS
        )
        assertEquals(2, issues.count { it.level == MetadataIssueLevel.ERROR })
        assertEquals(MetadataIssueLevel.WARNING, issues.first { it.key == "messages" }.level)
    }

    @Test
    fun rejectsKnownFieldOnWrongProtocolButWarnsForProviderExtensions() {
        val issues = MetadataValidator.validate(
            mapOf("max_tokens" to "1024", "vendor_custom_mode" to "true"),
            ProviderApiProtocol.RESPONSES
        )
        assertEquals(MetadataIssueLevel.ERROR, issues.first { it.key == "max_tokens" }.level)
        assertEquals(MetadataIssueLevel.WARNING, issues.first { it.key == "vendor_custom_mode" }.level)
    }

    @Test
    fun ignoresEmptyEditorRows() {
        assertTrue(
            MetadataValidator.validate(
                mapOf("temperature" to "", "" to "true"),
                ProviderApiProtocol.CHAT_COMPLETIONS
            ).isEmpty()
        )
    }

    @Test
    fun explicitDisabledMarkerIsAccepted() {
        assertTrue(
            MetadataValidator.validate(
                mapOf("temperature" to "@disabled"),
                ProviderApiProtocol.CHAT_COMPLETIONS
            ).isEmpty()
        )
    }

    @Test
    fun modelOverridesMustBeAnObjectOfObjects() {
        assertTrue(MetadataValidator.validateModelOverrides("""{"gpt-4o":{"temperature":0.5}}""").isEmpty())
        assertEquals(1, MetadataValidator.validateModelOverrides("""{"gpt-4o":"temperature=0.5"}""").size)
    }

    @Test
    fun providerDeclaredCapabilityOverridesBuiltInRegistry() {
        val provider = ProviderConfig(
            id = "openai", name = "OpenAI", baseUrl = "https://api.openai.com/v1",
            apiKey = "test", metadataCapabilityOverrides = mapOf("temperature" to "unsupported")
        )
        val capability = ProviderCapabilityRegistry.capability(provider, "gpt-4o", "temperature")
        assertEquals(CapabilityStatus.UNSUPPORTED, capability.status)
        assertTrue(capability.explanation.contains("explicitly declared"))
    }

    @Test
    fun responsesReasoningPathIsRegisteredForOpenAi() {
        val provider = ProviderConfig(
            id = "openai", name = "OpenAI", baseUrl = "https://api.openai.com/v1",
            apiKey = "test", apiProtocol = ProviderApiProtocol.RESPONSES
        )
        val capability = ProviderCapabilityRegistry.capability(provider, "o3", "reasoning.effort")
        assertEquals(CapabilityStatus.CONDITIONAL, capability.status)
        assertTrue(capability.sourceUrl.orEmpty().contains("openai.com"))
        assertTrue(
            MetadataValidator.validate(
                mapOf("reasoning.effort" to "\"high\""),
                ProviderApiProtocol.RESPONSES
            ).isEmpty()
        )
    }

    @Test
    fun builtInProviderIdentitiesHaveProtocolDocumentation() {
        ProviderPresets.all.filter { it.name != "Custom" }.forEach { preset ->
            val provider = ProviderConfig(
                id = preset.name, name = preset.name, baseUrl = preset.baseUrl, apiKey = "test",
                providerType = ProviderKinds.fromPreset(preset.name)
            )
            val capability = ProviderCapabilityRegistry.capability(provider, preset.sampleModel, "temperature")
            assertTrue("${preset.name} should have a documentation reference", capability.sourceUrl != null)
        }
    }

    @Test
    fun allProviderPresetsResolveToKnownCapabilityIdentity() {
        val supportedKinds = ProviderKinds.choices.map { it.first }.toSet()
        ProviderPresets.all.forEach { preset ->
            assertTrue(ProviderKinds.fromPreset(preset.name) in supportedKinds)
        }
    }
}
