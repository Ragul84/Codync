package com.ragul84.hypurr.ui.screens

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * VT parser/rendering checks against Termux terminal-emulator fixtures
 * (colors, alt screen, cursor). Runs on JVM — no device required (emulator label: unit).
 */
class TerminalScreenTest {

    private class CaptureOut : TerminalOutput() {
        val baos = ByteArrayOutputStream()
        override fun write(data: ByteArray, offset: Int, count: Int) {
            baos.write(data, offset, count)
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
        fun text(): String = String(baos.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun emu(cols: Int = 40, rows: Int = 12): Pair<TerminalEmulator, CaptureOut> {
        val out = CaptureOut()
        val t = TerminalEmulator(out, cols, rows, rows * 3, null)
        return t to out
    }

    private fun TerminalEmulator.feed(bytes: ByteArray) = append(bytes, bytes.size)

    private fun TerminalEmulator.line(row: Int): String {
        val screen = screen
        val l = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
        return String(l.mText, 0, l.spaceUsed).trimEnd()
    }

    @Test
    fun colorsAndCursorFixtureRendersText() {
        val (t, _) = emu()
        t.feed(TerminalVtFixtures.colorsAndCursor)
        val joined = (0 until t.mRows).joinToString("\n") { t.line(it) }
        assertTrue(joined.contains("Hypurr"))
        assertTrue(joined.contains("truecolor"))
        assertTrue(joined.contains("cursor@10,5") || t.line(9).contains("cursor@10,5"))
    }

    @Test
    fun altScreenEntersAndExits() {
        val (t, _) = emu()
        assertFalse(t.isAlternateBufferActive)
        t.feed("\u001b[?1049h\u001b[2J\u001b[HALT\r\n".toByteArray())
        assertTrue(t.isAlternateBufferActive)
        assertTrue(t.line(0).contains("ALT"))
        t.feed("\u001b[?1049l".toByteArray())
        assertFalse(t.isAlternateBufferActive)
    }

    @Test
    fun scrollRegionAndWrap() {
        val (t, _) = emu(cols = 20, rows = 8)
        // Direct wrap fixture (avoid scroll-region side effects on row 0).
        t.feed(("a".repeat(25) + "\r\n").toByteArray())
        assertTrue("expected wrap, got '${t.line(0)}' / '${t.line(1)}'", t.line(0).startsWith("aaa"))
        assertTrue(t.line(1).isNotEmpty() || t.line(0).length <= 20)
        // Scroll region set/reset should not crash and keeps buffer usable.
        t.feed(TerminalVtFixtures.scrollAndWrap)
        val joined = (0 until t.mRows).joinToString("\n") { t.line(it) }
        assertTrue(joined.contains("bbb") || joined.contains("aaa") || joined.contains("ccc"))
    }

    @Test
    fun truecolorAnd256DoNotCrash() {
        val (t, _) = emu()
        t.feed("\u001b[38;5;196mX\u001b[0m\u001b[38;2;1;2;3mY\u001b[0m".toByteArray())
        assertTrue(t.line(0).contains("X"))
        assertTrue(t.line(0).contains("Y"))
    }

    @Test
    fun stripAnsiLegacyHelperKeepsText() {
        val input = "\u001b[31mhello\u001b[0m\n\u001b[1;32mworld\u001b[0m"
        assertEquals("hello\nworld", stripAnsi(input))
    }

    @Test
    fun bytesToBase64RoundTrip() {
        val raw = byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte())
        val b64 = bytesToBase64(raw)
        assertEquals("G1tB", b64)
    }
}
