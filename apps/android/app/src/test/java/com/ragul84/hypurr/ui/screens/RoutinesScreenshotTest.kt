package com.ragul84.hypurr.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.Routine
import com.ragul84.hypurr.model.RoutineRun
import com.ragul84.hypurr.model.RoutineScheduleDraft
import com.ragul84.hypurr.model.RoutineSchedulePreview
import com.ragul84.hypurr.model.RoutineTrigger
import com.ragul84.hypurr.model.RoutineWebhook
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Android routines list + editor (host schedules / webhooks already on iPhone and Mac). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class RoutinesScreenshotTest {
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

    private val sample = Routine(
        id = "r1",
        botId = "b1",
        name = "Morning summary",
        instruction = "Summarise open PRs and post to Slack.",
        triggers = listOf(RoutineTrigger(type = "cron", expression = "0 9 * * 1-5", timeZone = "Asia/Calcutta")),
        enabled = true,
        triggerDescriptions = listOf("Weekdays at 9:00 AM"),
        nextRunAt = 1_800_000_000_000L,
    )

    @Test fun listEmpty() = shoot("routines-list-empty") {
        RoutinesScreen(
            botName = "Codex",
            routines = emptyList(),
            runs = emptyList(),
            loaded = true,
            busy = false,
            offline = false,
            error = null,
            onBack = {},
            onOpen = {},
            onToggle = { _, _ -> },
            onAskInChat = {},
        )
    }

    @Test fun listReady() = shoot("routines-list-ready") {
        RoutinesScreen(
            botName = "Codex",
            routines = listOf(
                sample,
                sample.copy(id = "r2", name = "Webhook triage", enabled = false,
                    triggerDescriptions = listOf("When a webhook fires"), lastError = null,
                    triggers = listOf(RoutineTrigger(type = "webhook"))),
            ),
            runs = listOf(RoutineRun(id = "run1", routineId = "r1", botId = "b1", status = "running")),
            loaded = true,
            busy = false,
            offline = false,
            error = null,
            onBack = {},
            onOpen = {},
            onToggle = { _, _ -> },
            onAskInChat = {},
        )
    }

    @Test fun editorSchedule() = shoot("routines-editor-schedule") {
        val draft = RoutineScheduleDraft(
            kind = "cron",
            calendarStyle = "custom",
            expression = "0 9 * * 1-5",
            zone = "Asia/Calcutta",
        )
        RoutineEditorScreen(
            botName = "Codex",
            routine = null,
            schedule = draft,
            preview = RoutineSchedulePreview(draft = draft, summary = "Weekdays at 9:00 AM Asia/Calcutta", nextRunAt = 1_800_000_000_000L),
            previewError = null,
            loaded = true,
            name = "Morning summary",
            instruction = "Summarise open PRs and post to Slack.",
            timeout = "3600",
            busy = false,
            offline = false,
            error = null,
            confirmDelete = false,
            webhook = null,
            webhookBusy = false,
            webhookError = null,
            keyRevealed = false,
            onName = {},
            onInstruction = {},
            onTimeout = {},
            onSchedule = {},
            onBack = {},
            onSave = {},
            onDelete = {},
            onConfirmDelete = {},
            onRunNow = {},
            onLoadWebhook = {},
            onRevealKey = {},
            onCopy = { _, _ -> },
        )
    }

    @Test fun editorWebhook() = shoot("routines-editor-webhook") {
        val draft = RoutineScheduleDraft(kind = "webhook")
        RoutineEditorScreen(
            botName = "Codex",
            routine = sample.copy(triggers = listOf(RoutineTrigger(type = "webhook")), triggerDescriptions = listOf("When a webhook fires")),
            schedule = draft,
            preview = RoutineSchedulePreview(draft = draft, summary = "When a webhook fires"),
            previewError = null,
            loaded = true,
            name = "Webhook triage",
            instruction = "Triage the delivery and open a task if needed.",
            timeout = "1800",
            busy = false,
            offline = false,
            error = null,
            confirmDelete = false,
            webhook = RoutineWebhook(
                url = "https://hooks.example/r/abc",
                localUrl = "http://127.0.0.1:47917/hooks/r/abc",
                key = "hk_test_key_123456",
                connected = false,
            ),
            webhookBusy = false,
            webhookError = null,
            keyRevealed = false,
            onName = {},
            onInstruction = {},
            onTimeout = {},
            onSchedule = {},
            onBack = {},
            onSave = {},
            onDelete = {},
            onConfirmDelete = {},
            onRunNow = {},
            onLoadWebhook = {},
            onRevealKey = {},
            onCopy = { _, _ -> },
        )
    }
}
