package com.miniichat.files

import org.junit.Assert.assertTrue
import org.junit.Test

class ExactTextDiffTest {
    @Test
    fun highlightsInsertedAndRemovedLines() {
        val diff = ExactTextDiff.render(
            before = "# Project\nOld decision\nKeep this",
            after = "# Project\nNew decision\nKeep this"
        )

        assertTrue(diff.contains("-Old decision"))
        assertTrue(diff.contains("+New decision"))
        assertTrue(diff.contains(" Keep this"))
    }

    @Test
    fun boundsVeryLargeDiffPreviews() {
        val before = (1..250).joinToString("\n") { "old line $it" }
        val after = (1..250).joinToString("\n") { "new line $it" }

        val diff = ExactTextDiff.render(before, after, maxOutputChars = 1_000)

        assertTrue(diff.length <= 1_050)
        assertTrue(diff.contains("Detailed diff omitted"))
    }

    @Test
    fun unchangedTextProducesNoChangesMarker() {
        assertTrue(ExactTextDiff.render("same", "same").contains("No changes"))
    }
}
