package com.elicode.app

import com.elicode.app.ui.components.splitInput
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalInputTest {

    @Test
    fun singleLinePassesThrough() {
        assertEquals(listOf("ls -la"), splitInput("ls -la"))
    }

    @Test
    fun pastedBatchSplits() {
        assertEquals(
            listOf("id", "df -h /"),
            splitInput("id\ndf -h /\n")
        )
    }

    @Test
    fun blanksDropped() {
        assertEquals(
            listOf("a", "b"),
            splitInput("\n  \na\n\nb\n")
        )
    }

    @Test
    fun emptyGivesNothing() {
        assertEquals(emptyList<String>(), splitInput("  \n "))
    }
}
