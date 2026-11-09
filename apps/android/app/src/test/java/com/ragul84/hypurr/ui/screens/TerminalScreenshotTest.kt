package com.ragul84.hypurr.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class TerminalScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        var theme by mutableStateOf(ThemeMode.Light)
        compose.setContent { HypurrTheme(theme) { content() } }
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            theme = mode
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.output.dir")}/android-$name-${mode.name.lowercase()}.png")
        }
    }

    @Test fun connecting() = shoot("terminal-connecting") {
        TerminalScreen(
            state = TerminalUiState(title = "Codex", phase = TerminalPhase.Connecting),
            offline = false, onBack = {}, onDraft = {}, onSubmitLine = {}, onControl = {}, onResize = { _, _ -> }, onDisconnect = {},
        )
    }

    @Test fun live() = shoot("terminal-live") {
        TerminalScreen(
            state = TerminalUiState(
                title = "Codex",
                cwd = "/tmp/hypurr-shell-demo/proj",
                phase = TerminalPhase.Live,
                output = "Hypurr shell · /tmp/hypurr-shell-demo/proj\n$ pwd\n/tmp/hypurr-shell-demo/proj\n$ ",
                draft = "ls",
            ),
            offline = false, onBack = {}, onDraft = {}, onSubmitLine = {}, onControl = {}, onResize = { _, _ -> }, onDisconnect = {},
        )
    }

    @Test fun ended() = shoot("terminal-ended") {
        TerminalScreen(
            state = TerminalUiState(
                title = "Codex",
                cwd = "/tmp/hypurr-shell-demo/proj",
                phase = TerminalPhase.Ended,
                exitCode = 0,
                output = "Hypurr shell · /tmp/hypurr-shell-demo/proj\n$ exit\n",
            ),
            offline = false, onBack = {}, onDraft = {}, onSubmitLine = {}, onControl = {}, onResize = { _, _ -> }, onDisconnect = {},
        )
    }
}
