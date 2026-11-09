package com.ragul84.hypurr.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ragul84.hypurr.ui.IconTile
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.Properties

/** Connection / lifecycle for a project shell session. */
enum class TerminalPhase { Connecting, Live, Reconnecting, Ended, Failed }

data class TerminalUiState(
    val title: String = "Terminal",
    val cwd: String = "",
    val phase: TerminalPhase = TerminalPhase.Connecting,
    val exitCode: Int? = null,
    val error: String? = null,
    /**
     * Optional ANSI/VT fixture bytes for previews and screenshot tests
     * (Robolectric / Compose preview). Live sessions feed the bridge instead.
     */
    val fixtureBytes: ByteArray? = null,
)

/**
 * Holds a Termux [TerminalSession] bridged to the host PTY.
 * Main thread only for [append] / [notifyExit].
 */
class TerminalBridge(
    private val onWriteToHost: (ByteArray) -> Unit,
) {
    var session: TerminalSession? = null
        private set
    var view: TerminalView? = null
        private set

    private val pending = ArrayList<ByteArray>()

    fun attach(view: TerminalView, session: TerminalSession) {
        this.view = view
        this.session = session
        if (pending.isNotEmpty()) {
            for (chunk in pending) session.append(chunk)
            pending.clear()
        }
    }

    fun append(bytes: ByteArray) {
        val s = session
        if (s == null) {
            pending.add(bytes.copyOf())
        } else {
            s.append(bytes)
            view?.onScreenUpdated()
        }
    }

    fun notifyExit(code: Int) {
        session?.notifyRemoteExit(code)
        view?.onScreenUpdated()
    }

    fun writeLocal(bytes: ByteArray) {
        onWriteToHost(bytes)
    }

    fun detach() {
        session?.finishIfRunning()
        session = null
        view = null
        pending.clear()
    }
}

/**
 * Project/task shell on the paired computer.
 * Full VT100/xterm via vendored Termux terminal-view (Apache-2.0): colors, cursor,
 * alternate screen, wrap, scroll regions — so vim/htop/less/git pager/Claude CLIs render.
 */
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    offline: Boolean,
    bridge: TerminalBridge,
    darkTerminal: Boolean,
    onBack: () -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onDisconnect: () -> Unit,
) {
    val c = Hypurr.colors
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    var fontSp by remember { mutableFloatStateOf(14f) }
    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }
    var lastCols by remember { mutableStateOf(0) }
    var lastRows by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            bridge.detach()
            onDisconnect()
        }
    }

    LaunchedEffect(darkTerminal) {
        applyHypurrTerminalColors(darkTerminal)
        bridge.session?.getEmulator()?.mColors?.reset()
        bridge.view?.onScreenUpdated()
    }

    LaunchedEffect(state.fixtureBytes) {
        val bytes = state.fixtureBytes ?: return@LaunchedEffect
        // Wait a frame so AndroidView can attach.
        kotlinx.coroutines.delay(16)
        bridge.append(bytes)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.cwd.ifBlank { "Opening…" },
                    color = c.tertiary,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "A−",
                color = c.text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.surface2)
                    .pressable("Font smaller") { fontSp = (fontSp - 1f).coerceIn(10f, 28f) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "A+",
                color = c.text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.surface2)
                    .pressable("Font larger") { fontSp = (fontSp + 1f).coerceIn(10f, 28f) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(6.dp))
            PhaseChip(state.phase, state.exitCode, offline)
        }
        state.error?.let {
            Text(it, color = c.needsText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(16.dp)),
        ) {
            when {
                state.phase == TerminalPhase.Connecting && state.fixtureBytes == null && bridge.session?.getEmulator() == null -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.height(10.dp))
                        Text("Starting shell on the computer…", color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
                    if (!inspection) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize().padding(4.dp),
                            factory = { ctx ->
                                applyHypurrTerminalColors(darkTerminal)
                                val tv = TerminalView(ctx, null)
                                val client = HypurrTerminalClients(
                                    context = ctx,
                                    bridge = bridge,
                                    isCtrl = { ctrlDown },
                                    isAlt = { altDown },
                                    clearModifiers = { ctrlDown = false; altDown = false },
                                    onFontScale = { factor ->
                                        fontSp = (fontSp * factor).coerceIn(10f, 28f)
                                        1f
                                    },
                                )
                                val session = TerminalSession(
                                    /* transcriptRows = */ 2000,
                                    client,
                                ) { data, offset, count ->
                                    bridge.writeLocal(data.copyOfRange(offset, offset + count))
                                }
                                tv.setTerminalViewClient(client)
                                tv.attachSession(session)
                                tv.setTextSize(spToPx(ctx, fontSp))
                                bridge.attach(tv, session)
                                tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                                    val emu = session.getEmulator() ?: return@addOnLayoutChangeListener
                                    val cols = emu.mColumns
                                    val rows = emu.mRows
                                    if (cols > 0 && rows > 0 && (cols != lastCols || rows != lastRows)) {
                                        lastCols = cols
                                        lastRows = rows
                                        onResize(cols, rows)
                                    }
                                }
                                tv
                            },
                            update = { tv ->
                                tv.setTextSize(spToPx(tv.context, fontSp))
                                applyHypurrTerminalColors(darkTerminal)
                                tv.mEmulator?.mColors?.reset()
                                tv.onScreenUpdated()
                            },
                        )
                    } else {
                        // Preview / screenshot without full View attach: show a monospace stand-in.
                        Text(
                            state.fixtureBytes?.toString(Charsets.UTF_8)?.let { roughStripForPreview(it) }
                                ?: " ",
                            color = c.text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                        )
                    }
                }
            }
        }
        ExtraKeysRow(
            ctrlDown = ctrlDown,
            altDown = altDown,
            onCtrl = { ctrlDown = !ctrlDown },
            onAlt = { altDown = !altDown },
            onKey = { keyCode, mod ->
                val session = bridge.session ?: return@ExtraKeysRow
                val code = KeyHandler.getCode(keyCode, mod, false, false) ?: return@ExtraKeysRow
                session.write(code)
            },
            onBytes = { bridge.session?.write(it, 0, it.size) },
            onPaste = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return@ExtraKeysRow
                bridge.session?.getEmulator()?.paste(text)
                    ?: bridge.session?.write(text.toByteArray(Charsets.UTF_8), 0, text.toByteArray(Charsets.UTF_8).size)
            },
            enabled = (state.phase == TerminalPhase.Live || state.phase == TerminalPhase.Reconnecting) && !offline,
        )
    }
}

@Composable
private fun ExtraKeysRow(
    ctrlDown: Boolean,
    altDown: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (keyCode: Int, mod: Int) -> Unit,
    onBytes: (ByteArray) -> Unit,
    onPaste: () -> Unit,
    enabled: Boolean,
) {
    val c = Hypurr.colors
    fun mod(): Int {
        var m = 0
        if (ctrlDown) m = m or KeyHandler.KEYMOD_CTRL
        if (altDown) m = m or KeyHandler.KEYMOD_ALT
        return m
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .padding(bottom = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtraKey("Esc", enabled) { onBytes(byteArrayOf(0x1b)) }
        ExtraKey("Tab", enabled) { onBytes(byteArrayOf(0x09)) }
        ExtraKey("Ctrl", enabled, active = ctrlDown, onClick = onCtrl)
        ExtraKey("Alt", enabled, active = altDown, onClick = onAlt)
        ExtraKey("↑", enabled) { onKey(KeyEvent.KEYCODE_DPAD_UP, mod()) }
        ExtraKey("↓", enabled) { onKey(KeyEvent.KEYCODE_DPAD_DOWN, mod()) }
        ExtraKey("←", enabled) { onKey(KeyEvent.KEYCODE_DPAD_LEFT, mod()) }
        ExtraKey("→", enabled) { onKey(KeyEvent.KEYCODE_DPAD_RIGHT, mod()) }
        ExtraKey("PgUp", enabled) { onKey(KeyEvent.KEYCODE_PAGE_UP, mod()) }
        ExtraKey("PgDn", enabled) { onKey(KeyEvent.KEYCODE_PAGE_DOWN, mod()) }
        ExtraKey("Home", enabled) { onKey(KeyEvent.KEYCODE_MOVE_HOME, mod()) }
        ExtraKey("End", enabled) { onKey(KeyEvent.KEYCODE_MOVE_END, mod()) }
        ExtraKey("Paste", enabled, onClick = onPaste)
        ExtraKey("^C", enabled) { onBytes(byteArrayOf(0x03)) }
        ExtraKey("^D", enabled) { onBytes(byteArrayOf(0x04)) }
        ExtraKey("^Z", enabled) { onBytes(byteArrayOf(0x1a)) }
    }
}

@Composable
private fun ExtraKey(label: String, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    val c = Hypurr.colors
    val bg = when {
        active -> c.accent
        else -> c.surface2
    }
    val fg = when {
        active -> c.onAccent
        enabled -> c.text
        else -> c.tertiary
    }
    Text(
        label,
        color = fg,
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .pressable(label, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun PhaseChip(phase: TerminalPhase, exitCode: Int?, offline: Boolean) {
    val c = Hypurr.colors
    val (label, color) = when {
        offline && phase == TerminalPhase.Live -> "Offline" to c.needs
        phase == TerminalPhase.Connecting -> "Connecting" to c.tertiary
        phase == TerminalPhase.Reconnecting -> "Reconnecting" to c.needs
        phase == TerminalPhase.Failed -> "Failed" to c.needsText
        phase == TerminalPhase.Ended -> "Ended${exitCode?.let { " · $it" } ?: ""}" to c.secondary
        else -> "Live" to c.success
    }
    Text(
        label,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.surface2)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun spToPx(context: Context, sp: Float): Int {
    return (sp * context.resources.displayMetrics.scaledDensity).toInt().coerceAtLeast(8)
}

/** Match Hypurr light/dark surfaces for default FG/BG/cursor. */
fun applyHypurrTerminalColors(dark: Boolean) {
    val props = Properties()
    if (dark) {
        props.setProperty("foreground", "#F5F5F2")
        props.setProperty("background", "#111111")
        props.setProperty("cursor", "#F5F5F2")
    } else {
        props.setProperty("foreground", "#0E0E0E")
        props.setProperty("background", "#FFFFFF")
        props.setProperty("cursor", "#0E0E0E")
    }
    TerminalColors.COLOR_SCHEME.updateWith(props)
}

/** Rough preview stripper for inspection mode only (full VT is in Termux emulator). */
fun roughStripForPreview(raw: String): String {
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '\u001b') {
            if (i + 1 >= raw.length) break
            when (raw[i + 1]) {
                '[' -> {
                    i += 2
                    while (i < raw.length && !raw[i].isLetter()) i++
                    if (i < raw.length) i++
                }
                ']' -> {
                    i += 2
                    while (i < raw.length && raw[i] != '\u0007' && !(raw[i] == '\\' && i > 0 && raw[i - 1] == '\u001b')) i++
                    if (i < raw.length) i++
                }
                else -> i += 2
            }
        } else if (ch == '\r') {
            if (i + 1 >= raw.length || raw[i + 1] != '\n') out.append('\n')
            i++
        } else {
            out.append(ch)
            i++
        }
    }
    return out.toString()
}

@Deprecated("Use Termux VT; kept for older call sites / tests naming")
fun stripAnsi(raw: String): String = roughStripForPreview(raw)

fun bytesToBase64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

/**
 * Sample VT fixture: colors, cursor move, alt screen banner — for unit/screenshot tests.
 */
object TerminalVtFixtures {
    /** 16-color + cursor positioning + wrap. */
    val colorsAndCursor: ByteArray = (
        "\u001b[2J\u001b[H" +
            "\u001b[1;31mHypurr\u001b[0m \u001b[32mVT\u001b[0m \u001b[34mfixture\u001b[0m\r\n" +
            "\u001b[1;33m256:\u001b[0m \u001b[38;5;208morange\u001b[0m \u001b[38;5;39mblue\u001b[0m\r\n" +
            "\u001b[38;2;255;100;50mtruecolor\u001b[0m OK\r\n" +
            "\u001b[10;5Hcursor@10,5\r\n"
        ).toByteArray(Charsets.UTF_8)

    /** Alternate screen (like less/vim). */
    val altScreen: ByteArray = (
        "\u001b[?1049h\u001b[2J\u001b[H" +
            "\u001b[7m ALT SCREEN \u001b[0m  (vim/less/htop)\r\n" +
            "line 2\r\nline 3\r\n" +
            "\u001b[?1049l"
        ).toByteArray(Charsets.UTF_8)

    /** Scroll region + wrapped long line. */
    val scrollAndWrap: ByteArray = (
        "\u001b[2J\u001b[H\u001b[1;5r" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaEXTRA\r\n" +
            "bbbb\r\ncccc\r\ndddd\r\neeee\r\nffff\r\n"
        ).toByteArray(Charsets.UTF_8)
}

private class HypurrTerminalClients(
    private val context: Context,
    private val bridge: TerminalBridge,
    private val isCtrl: () -> Boolean,
    private val isAlt: () -> Boolean,
    private val clearModifiers: () -> Unit,
    private val onFontScale: (Float) -> Float,
) : TerminalSessionClient, TerminalViewClient {

    override fun onTextChanged(changedSession: TerminalSession) {
        bridge.view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        session.getEmulator()?.paste(text)
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {
        bridge.view?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}

    override fun onScale(scale: Float): Float = onFontScale(scale)

    override fun onSingleTapUp(e: MotionEvent) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        bridge.view?.requestFocus()
        imm.showSoftInput(bridge.view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = isCtrl()
    override fun readAltKey(): Boolean = isAlt()
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (isCtrl() || isAlt()) clearModifiers()
        return false
    }

    override fun onEmulatorSet() {}
}
