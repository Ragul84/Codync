package com.ragul84.hypurr.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenTest {
    @Test fun stripAnsiRemovesColorsAndKeepsText() {
        val esc = 0x1b.toChar()
        val input = esc + "[32mhello" + esc + "[0m\r\nworld"
        assertEquals("hello\nworld", stripAnsi(input))
    }

    @Test fun stripAnsiHandlesCursorQueriesNoise() {
        val esc = 0x1b.toChar()
        val raw = esc + "[1;1Rproj" + esc + "[K"
        val out = stripAnsi(raw)
        assertTrue(out.contains("proj"))
        assertFalse(out.contains(esc.toString()))
    }

    @Test fun bytesToBase64RoundTrip() {
        val src = "pwd\n".toByteArray()
        val b64 = bytesToBase64(src)
        val back = java.util.Base64.getDecoder().decode(b64)
        assertEquals("pwd\n", String(back, Charsets.UTF_8))
    }
}
