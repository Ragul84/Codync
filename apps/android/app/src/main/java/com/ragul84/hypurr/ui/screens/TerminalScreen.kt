package com.ragul84.hypurr.ui.screens

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.ui.IconTile
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr

/** Connection / lifecycle for a project shell session. */
enum class TerminalPhase { Connecting, Live, Reconnecting, Ended, Failed }

data class TerminalUiState(
    val title: String = "Terminal",
    val cwd: String = "",
    val phase: TerminalPhase = TerminalPhase.Connecting,
    val output: String = "",
    val exitCode: Int? = null,
    val error: String? = null,
    val draft: String = "",
)

/**
 * Project/task shell on the paired computer.
 * Output is plain scrollback with ANSI stripped for readability (full VT is on iPhone/Mac setup terminals via SwiftTerm).
 * Does not auto-run commands; the user types.
 */
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    offline: Boolean,
    onBack: () -> Unit,
    onDraft: (String) -> Unit,
    onSubmitLine: () -> Unit,
    onControl: (ByteArray) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onDisconnect: () -> Unit,
) {
    val c = Hypurr.colors
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current
    var cols by remember { mutableIntStateOf(80) }
    var rows by remember { mutableIntStateOf(24) }

    LaunchedEffect(state.output) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    DisposableEffect(Unit) {
        onDispose { onDisconnect() }
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
                .border(1.dp, c.border, RoundedCornerShape(16.dp))
                .onSizeChanged { size ->
                    val cell = with(density) { 7.5.sp.toPx() }.coerceAtLeast(1f)
                    val line = with(density) { 16.sp.toPx() }.coerceAtLeast(1f)
                    val nextCols = (size.width / cell).toInt().coerceIn(20, 200)
                    val nextRows = (size.height / line).toInt().coerceIn(4, 120)
                    if (nextCols != cols || nextRows != rows) {
                        cols = nextCols
                        rows = nextRows
                        onResize(nextCols, nextRows)
                    }
                }
                .padding(12.dp),
        ) {
            when {
                state.phase == TerminalPhase.Connecting && state.output.isEmpty() -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.height(10.dp))
                        Text("Starting shell on the computer…", color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
                    Text(
                        state.output.ifEmpty { " " },
                        color = c.text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        ),
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ControlKey("Esc") { onControl(byteArrayOf(0x1b)) }
            ControlKey("Tab") { onControl(byteArrayOf(0x09)) }
            ControlKey("^C") { onControl(byteArrayOf(0x03)) }
            ControlKey("^D") { onControl(byteArrayOf(0x04)) }
            ControlKey("↑") { onControl(byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte())) }
            ControlKey("↓") { onControl(byteArrayOf(0x1b, '['.code.toByte(), 'B'.code.toByte())) }
            ControlKey("←") { onControl(byteArrayOf(0x1b, '['.code.toByte(), 'D'.code.toByte())) }
            ControlKey("→") { onControl(byteArrayOf(0x1b, '['.code.toByte(), 'C'.code.toByte())) }
        }
        val live = state.phase == TerminalPhase.Live || state.phase == TerminalPhase.Reconnecting
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.surface2)
                    .border(1.dp, c.border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (state.draft.isEmpty()) {
                    Text(if (live) "Type a command…" else "Shell ended", color = c.tertiary, style = MaterialTheme.typography.bodyMedium)
                }
                BasicTextField(
                    value = state.draft,
                    onValueChange = onDraft,
                    enabled = live && !offline,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (live) onSubmitLine() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Spacer(Modifier.width(8.dp))
            SoftButton("Send", tint = if (live && !offline && state.draft.isNotEmpty()) c.accent else c.tertiary) {
                if (live && !offline && state.draft.isNotEmpty()) onSubmitLine()
            }
        }
        if (!LocalInspectionMode.current) {
            LaunchedEffect(live) { if (live) runCatching { focus.requestFocus() } }
        }
    }
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

@Composable
private fun ControlKey(label: String, onClick: () -> Unit) {
    val c = Hypurr.colors
    Text(
        label,
        color = c.text,
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface2)
            .pressable(label, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Strip CSI/OSC for a readable phone scrollback. Keeps text and newlines. */
fun stripAnsi(raw: String): String {
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
            // Prefer \n; skip bare CR so lines don't overwrite in Text.
            if (i + 1 >= raw.length || raw[i + 1] != '\n') out.append('\n')
            i++
        } else {
            out.append(ch)
            i++
        }
    }
    return out.toString()
}

fun bytesToBase64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
