package com.miniichat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSessionStoreTest {
    @Test
    fun sessionIdsAreEightLowercaseHexCharactersAndUniqueAcrossSamples() {
        val ids = List(100) { FileSessionStore.newSessionId() }
        assertTrue(ids.all { it.matches(Regex("[0-9a-f]{8}")) })
        assertEquals(ids.size, ids.toSet().size)
    }
}
