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
        assertEquals(3, issues.count { it.level == MetadataIssueLevel.ERROR })
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
    fun allProviderPresetsResolveToKnownCapabilityIdentity() {
        val supportedKinds = ProviderKinds.choices.map { it.first }.toSet()
        ProviderPresets.all.forEach { preset ->
            assertTrue(ProviderKinds.fromPreset(preset.name) in supportedKinds)
        }
    }
}
