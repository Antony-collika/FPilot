package com.miniichat.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageSummaryTest {
    @Test
    fun readsUsageFromANormalResponse() {
        val body = """{"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":50,"prompt_tokens_details":{"cached_tokens":1000}}}"""
        assertEquals("prompt 1200 · cached 1000 (83%) · completion 50", summarizeUsage(body))
    }

    @Test
    fun readsUsageFromAStreamedResponse() {
        val body = "data: {\"choices\":[]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}\n\ndata: [DONE]\n"
        assertEquals("prompt 10 · cached: not reported by the provider · completion 2", summarizeUsage(body))
    }

    @Test
    fun returnsNullWhenThereIsNoUsage() {
        assertNull(summarizeUsage("""{"choices":[]}"""))
        assertNull(summarizeUsage("not json"))
    }
}
