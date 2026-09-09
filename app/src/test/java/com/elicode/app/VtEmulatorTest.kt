package com.elicode.app

import com.elicode.app.ui.components.VtEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VtEmulatorTest {

    private fun vt(cols: Int = 10, rows: Int = 5) = VtEmulator(cols, rows)

    @Test
    fun plainTextAndWrap() {
        val v = vt(cols = 4, rows = 3)
        v.process("abcd" + "ef")
        assertEquals("abcd\nef", v.plainText())
    }

    @Test
    fun cursorPositioning() {
        val v = vt()
        v.process("\u001B[3;5H")
        assertEquals(4, v.cursor().col)
        assertEquals(2, v.cursor().row)
        v.process("X")
        val rows = v.plainText().lines()
        assertEquals("    X", rows[2])
    }

    @Test
    fun cursorMoves() {
        val v = vt()
        v.process("ab\u001B[2D")
        assertEquals(0, v.cursor().col)
        v.process("X")
        assertEquals("Xb", v.plainText().lines()[0].take(2))
        v.process("\u001B[1B\u001B[3C")
        assertEquals(4, v.cursor().col)
        assertEquals(1, v.cursor().row)
    }

    @Test
    fun eraseDisplayAndLine() {
        val v = vt()
        v.process("hello")
        v.process("\u001B[2J")
        assertEquals("", v.plainText())
        v.process("world\u001B[2K")
        assertEquals("", v.plainText())
    }

    @Test
    fun sgrColors() {
        val v = vt()
        v.process("\u001B[31mR\u001B[0mN")
        val runs = v.renderedRows()[0]
        assertEquals("R", runs[0].text)
        assertEquals(0xFFCD0000.toInt(), runs[0].fg)
        assertEquals("N", runs[1].text)
        assertEquals(VtEmulator.FG_DEFAULT, runs[1].fg)
    }

    @Test
    fun sgr256AndTruecolor() {
        val v = vt()
        v.process("\u001B[38;5;196mA")
        assertEquals(VtEmulator.palette256(196), v.renderedRows()[0][0].fg)
        v.process("\u001B[38;2;10;20;30mB")
        val fg = v.renderedRows()[0][1].fg
        assertEquals((0xFF shl 24) or (10 shl 16) or (20 shl 8) or 30, fg)
    }

    @Test
    fun altScreenEnterExit() {
        val v = vt()
        v.process("main")
        v.process("\u001B[?1049h")
        assertTrue(v.usingAltScreen)
        assertEquals("", v.plainText())
        v.process("alt")
        assertEquals("alt", v.plainText())
        v.process("\u001B[?1049l")
        assertFalse(v.usingAltScreen)
        assertEquals("main", v.plainText())
    }

    @Test
    fun cursorVisibility() {
        val v = vt()
        assertTrue(v.cursor().visible)
        v.process("\u001B[?25l")
        assertFalse(v.cursor().visible)
        v.process("\u001B[?25h")
        assertTrue(v.cursor().visible)
    }

    @Test
    fun scrollMargins() {
        val v = vt(cols = 4, rows = 4)
        // LF alone keeps the column: use CRLF like a real line discipline.
        v.process("1111\r\n2222\r\n3333\r\n4444")
        // Scroll region rows 2..3 (1-based), then feed a line: only region scrolls.
        v.process("\u001B[2;3r")
        v.process("\u001B[3;1H" + "\n")
        val lines = v.plainText().lines()
        assertEquals("1111", lines[0])
        assertEquals("3333", lines[1])
        assertEquals("", lines[2])
        assertEquals("4444", lines[3])
    }

    @Test
    fun saveRestoreCursor() {
        val v = vt()
        v.process("\u001B[2;2H" + "\u001B[s" + "\u001B[5;5H" + "\u001B[u")
        assertEquals(1, v.cursor().col)
        assertEquals(1, v.cursor().row)
    }

    @Test
    fun unknownSequencesAreDropped() {
        val v = vt()
        v.process("\u001B[?9999h\u001B]0;title\u0007\u001B(0ok")
        assertEquals("ok", v.plainText())
    }

    @Test
    fun versionBumpsOnInput() {
        val v = vt()
        val before = v.version
        v.process("x")
        assertTrue(v.version > before)
    }

    @Test
    fun insertDeleteLines() {
        val v = vt(cols = 3, rows = 3)
        v.process("aaa\r\nbbb\r\nccc")
        v.process("\u001B[1;1H" + "\u001B[1L")
        assertEquals("", v.plainText().lines()[0])
        assertEquals("aaa", v.plainText().lines()[1])
        v.process("\u001B[1M")
        assertEquals("aaa", v.plainText().lines()[0])
    }
}
