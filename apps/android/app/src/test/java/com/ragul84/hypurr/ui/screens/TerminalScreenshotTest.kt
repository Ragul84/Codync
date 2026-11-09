package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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

    private fun shoot(name: String, darkForced: Boolean? = null, content: @androidx.compose.runtime.Composable (dark: Boolean) -> Unit) {
        var theme by mutableStateOf(ThemeMode.Light)
        compose.setContent {
            HypurrTheme(theme) {
                content(theme == ThemeMode.Dark)
            }
        }
        val modes = if (darkForced != null) {
            listOf(if (darkForced) ThemeMode.Dark else ThemeMode.Light)
        } else listOf(ThemeMode.Light, ThemeMode.Dark)
        for (mode in modes) {
            theme = mode
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.output.dir")}/android-$name-${mode.name.lowercase()}.png")
        }
    }

    private fun bridge() = TerminalBridge(onWriteToHost = {})

    @Test fun connecting() = shoot("terminal-connecting") { dark ->
        TerminalScreen(
            state = TerminalUiState(title = "Codex", phase = TerminalPhase.Connecting),
            offline = false,
            bridge = bridge(),
            darkTerminal = dark,
            onBack = {},
            onResize = { _, _ -> },
            onDisconnect = {},
        )
    }

    @Test fun tuiColorsLive() = shoot("terminal-tui-colors") { dark ->
        TerminalScreen(
            state = TerminalUiState(
                title = "Codex",
                cwd = "/tmp/hypurr-shell-demo/proj",
                phase = TerminalPhase.Live,
                fixtureBytes = TerminalVtFixtures.colorsAndCursor,
            ),
            offline = false,
            bridge = bridge(),
            darkTerminal = dark,
            onBack = {},
            onResize = { _, _ -> },
            onDisconnect = {},
        )
    }

    @Test fun tuiAltScreen() = shoot("terminal-tui-alt") { dark ->
        TerminalScreen(
            state = TerminalUiState(
                title = "vim",
                cwd = "/tmp/hypurr-shell-demo/proj",
                phase = TerminalPhase.Live,
                fixtureBytes = TerminalVtFixtures.altScreen,
            ),
            offline = false,
            bridge = bridge(),
            darkTerminal = dark,
            onBack = {},
            onResize = { _, _ -> },
            onDisconnect = {},
        )
    }

    @Test fun ended() = shoot("terminal-ended") { dark ->
        TerminalScreen(
            state = TerminalUiState(
                title = "Codex",
                cwd = "/tmp/hypurr-shell-demo/proj",
                phase = TerminalPhase.Ended,
                exitCode = 0,
                fixtureBytes = "shell exit\r\n".toByteArray(),
            ),
            offline = false,
            bridge = bridge(),
            darkTerminal = dark,
            onBack = {},
            onResize = { _, _ -> },
            onDisconnect = {},
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h568dp-xhdpi", application = android.app.Application::class)
class TerminalScreenshotSmallTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tuiSmallPhone() {
        var theme by mutableStateOf(ThemeMode.Dark)
        compose.setContent {
            HypurrTheme(theme) {
                TerminalScreen(
                    state = TerminalUiState(
                        title = "htop",
                        cwd = "/tmp/hypurr-shell-demo/proj",
                        phase = TerminalPhase.Live,
                        fixtureBytes = TerminalVtFixtures.colorsAndCursor,
                    ),
                    offline = false,
                    bridge = TerminalBridge {},
                    darkTerminal = true,
                    onBack = {},
                    onResize = { _, _ -> },
                    onDisconnect = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.output.dir")}/android-terminal-tui-small-dark.png")
        theme = ThemeMode.Light
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.output.dir")}/android-terminal-tui-small-light.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h700dp-xxhdpi", application = android.app.Application::class)
class TerminalScreenshotKeyboardTest {
    @get:Rule val compose = createComposeRule()

    /** Shorter viewport approximates IME open (safe-area + imePadding still applied). */
    @Test fun tuiKeyboardOpen() {
        var theme by mutableStateOf(ThemeMode.Light)
        compose.setContent {
            HypurrTheme(theme) {
                Box(Modifier.fillMaxSize().padding(bottom = 280.dp)) {
                    TerminalScreen(
                        state = TerminalUiState(
                            title = "Codex",
                            cwd = "/tmp/hypurr-shell-demo/proj",
                            phase = TerminalPhase.Live,
                            fixtureBytes = TerminalVtFixtures.colorsAndCursor,
                        ),
                        offline = false,
                        bridge = TerminalBridge {},
                        darkTerminal = theme == ThemeMode.Dark,
                        onBack = {},
                        onResize = { _, _ -> },
                        onDisconnect = {},
                    )
                }
            }
        }
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            theme = mode
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(
                "${System.getProperty("roborazzi.output.dir")}/android-terminal-tui-keyboard-${mode.name.lowercase()}.png",
            )
        }
    }
}
