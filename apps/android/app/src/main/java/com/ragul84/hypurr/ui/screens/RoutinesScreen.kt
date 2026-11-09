package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.Routine
import com.ragul84.hypurr.model.RoutineRun
import com.ragul84.hypurr.model.RoutineScheduleDraft
import com.ragul84.hypurr.model.RoutineSchedulePreview
import com.ragul84.hypurr.model.RoutineWebhook
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.IconTile
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.panel
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * List of schedules and webhooks for one agent bot (kit RoutinesView).
 * Pause/resume with the switch; tap a row to edit; + sets one up by hand;
 * "Ask in chat" seeds the composer.
 */
@Composable
fun RoutinesScreen(
    botName: String,
    routines: List<Routine>,
    runs: List<RoutineRun>,
    loaded: Boolean,
    busy: Boolean,
    offline: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onOpen: (Routine?) -> Unit,
    onToggle: (Routine, Boolean) -> Unit,
    onAskInChat: () -> Unit,
) {
    val c = Hypurr.colors
    // Keep the list fresh while this screen is open (same cadence as iPhone).
    if (!LocalInspectionMode.current) {
        LaunchedEffect(Unit) {
            while (true) {
                onRefresh()
                kotlinx.coroutines.delay(3_000)
            }
        }
    }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Routines", style = MaterialTheme.typography.headlineSmall, color = c.text)
                Text(botName, color = c.tertiary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBubble(
                signalIcon(SignalIcons.Docs), "Ask the bot for a routine", size = 40.dp,
                tint = if (offline) c.tertiary else c.text,
            ) { if (!offline) onAskInChat() }
            Spacer(Modifier.width(8.dp))
            IconBubble(
                signalIcon(SignalIcons.Plus), "Set up a routine", size = 40.dp,
                tint = if (offline || busy) c.tertiary else c.text,
            ) { if (!offline && !busy) onOpen(null) }
        }
        error?.let {
            Text(it, color = c.needsText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Run this bot on a schedule or when a webhook fires. Results show up in the chat.",
                color = c.secondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (!loaded && routines.isEmpty()) {
                Text("Loading routines…", color = c.tertiary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            } else if (routines.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().panel(RoundedCornerShape(18.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("No routines yet", color = c.text, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Ask the bot for one in chat, or set it up yourself with +.",
                        color = c.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SoftButton("Ask in chat", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Docs), onClick = onAskInChat)
                }
            } else {
                Column(Modifier.fillMaxWidth().panel(RoundedCornerShape(18.dp))) {
                    routines.forEachIndexed { i, routine ->
                        if (i > 0) HorizontalDivider(color = c.border)
                        RoutineRow(
                            routine = routine,
                            runs = runs,
                            enabledToggle = !busy && !offline,
                            onOpen = { onOpen(routine) },
                            onToggle = { onToggle(routine, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineRow(
    routine: Routine,
    runs: List<RoutineRun>,
    enabledToggle: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val c = Hypurr.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).pressable(routine.name, onClick = onOpen),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(routine.name, color = c.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                routineSummary(routine, runs),
                color = if (routine.lastError != null) c.needsText else c.secondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        RoutineSwitch(on = routine.enabled, enabled = enabledToggle, label = "${routine.name} on or off", onChange = onToggle)
    }
}

@Composable
private fun RoutineSwitch(on: Boolean, enabled: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val c = Hypurr.colors
    val knob by androidx.compose.animation.core.animateDpAsState(if (on) 22.dp else 2.dp, com.ragul84.hypurr.ui.theme.Motion.spatialFast(), label = "routine-knob")
    Box(
        Modifier
            .size(50.dp, 30.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) c.accent else c.borderStrong)
            .pressable(label, role = Role.Switch, enabled = enabled) { onChange(!on) },
    ) {
        Box(
            Modifier
                .padding(start = knob, top = 2.dp)
                .size(26.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (on) c.onAccent else androidx.compose.ui.graphics.Color.White),
        )
    }
}

internal fun routineSummary(routine: Routine, runs: List<RoutineRun>): String {
    runs.firstOrNull { it.routineId == routine.id && it.isActive }?.let { return runLabel(it) }
    if (routine.lastError != null) return "Schedule needs attention"
    runs.firstOrNull { it.routineId == routine.id }?.takeIf { it.status in setOf("failed", "interrupted") }?.let {
        return "Last run ${runLabel(it).lowercase()}"
    }
    return routine.triggerDescriptions.joinToString(" · ").ifBlank { "No schedule yet" }
}

internal fun runLabel(run: RoutineRun): String = when (run.status) {
    "pending" -> "Queued"
    "starting" -> "Starting"
    "running" -> "Running"
    "recovering" -> "Resuming after restart"
    "failed" -> "Failed"
    "interrupted" -> "Interrupted"
    "cancelled" -> "Cancelled"
    "succeeded" -> "Finished"
    else -> run.status.replaceFirstChar { it.uppercase() }
}

/**
 * Create or edit one routine. Schedule (cron + time zone) or webhook;
 * anything else the bot set up stays until you replace it.
 */
@Composable
fun RoutineEditorScreen(
    botName: String,
    routine: Routine?,
    schedule: RoutineScheduleDraft,
    preview: RoutineSchedulePreview?,
    previewError: String?,
    loaded: Boolean,
    name: String,
    instruction: String,
    timeout: String,
    busy: Boolean,
    offline: Boolean,
    error: String?,
    confirmDelete: Boolean,
    webhook: RoutineWebhook?,
    webhookBusy: Boolean,
    webhookError: String?,
    keyRevealed: Boolean,
    onName: (String) -> Unit,
    onInstruction: (String) -> Unit,
    onTimeout: (String) -> Unit,
    onSchedule: (RoutineScheduleDraft) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onLoadWebhook: (rotate: Boolean) -> Unit,
    onRevealKey: (Boolean) -> Unit,
    onCopy: (label: String, text: String) -> Unit,
) {
    val c = Hypurr.colors
    val keepsOriginal = routine != null && (
        routine.triggers.size != 1 || routine.triggers.firstOrNull()?.type !in setOf("cron", "webhook")
    )
    val canSave = name.isNotBlank() && instruction.isNotBlank() && loaded && !busy && !offline &&
        previewError == null && preview != null && schedule == preview.draft
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (routine == null) "Set up a routine" else "Edit routine", style = MaterialTheme.typography.headlineSmall, color = c.text)
                Text(botName, color = c.tertiary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FieldCard("Name") {
                RoutineField(name, onName, "e.g. Morning summary", singleLine = true)
            }
            FieldCard("Instruction") {
                RoutineField(instruction, onInstruction, "Describe what this bot should do each time it runs.", minLines = 3)
            }
            FieldCard("When to run") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (keepsOriginal) {
                            KindChip("Keep existing", schedule.kind == "keep") {
                                onSchedule(schedule.copy(kind = "keep"))
                            }
                        }
                        KindChip("Schedule", schedule.kind == "cron") { onSchedule(schedule.copy(kind = "cron", calendarStyle = "custom")) }
                        KindChip("Webhook", schedule.kind == "webhook") { onSchedule(schedule.copy(kind = "webhook")) }
                    }
                    if (!loaded) {
                        Text("Loading schedule…", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                    } else when (schedule.kind) {
                        "keep" -> Text(
                            routine?.triggerDescriptions?.joinToString(" · ").orEmpty().ifBlank { "Keeps the current triggers." },
                            color = c.secondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        "webhook" -> Text(
                            "Runs whenever an authenticated JSON delivery hits its webhook URL.",
                            color = c.secondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> {
                            Text("Cron (five fields)", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                            RoutineField(schedule.expression, { onSchedule(schedule.copy(expression = it, calendarStyle = "custom")) }, "0 9 * * 1-5", singleLine = true, mono = true)
                            Text("Time zone (IANA)", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                            RoutineField(schedule.zone, { onSchedule(schedule.copy(zone = it)) }, "Asia/Calcutta", singleLine = true, mono = true)
                        }
                    }
                    when {
                        previewError != null -> Text(previewError, color = c.needsText, style = MaterialTheme.typography.bodySmall)
                        preview != null && schedule == preview.draft -> {
                            Text(preview.summary.ifBlank { "Ready" }, color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                            preview.nextRunAt?.let {
                                Text("Next run ${formatWhen(it)}", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                            }
                            preview.warning?.let { Text(it, color = c.needsText, style = MaterialTheme.typography.bodySmall) }
                        }
                        loaded && schedule.kind == "cron" -> Text("Checking schedule…", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (routine != null && schedule.kind == "webhook") {
                WebhookCard(
                    webhook = webhook,
                    busy = webhookBusy,
                    error = webhookError,
                    keyRevealed = keyRevealed,
                    onLoad = onLoadWebhook,
                    onReveal = onRevealKey,
                    onCopy = onCopy,
                )
            }
            FieldCard("Run settings") {
                Text("Timeout (seconds)", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                RoutineField(timeout, onTimeout, "3600", singleLine = true, mono = true)
                Text("Stops a hung run so the next one can start.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
            }
            if (routine != null) {
                SoftButton("Run now", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Play), tint = c.accent, onClick = onRunNow)
                if (!confirmDelete) {
                    SoftButton("Delete routine", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Trash), tint = c.needsText) {
                        onConfirmDelete(true)
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().panel(RoundedCornerShape(16.dp), c.surface2).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("This deletes the routine and stops its future runs. This can't be undone.", color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SoftButton("Delete", Modifier.weight(1f), tint = c.needsText, onClick = onDelete)
                            SoftButton("Keep", Modifier.weight(1f), tint = c.secondary) { onConfirmDelete(false) }
                        }
                    }
                }
            }
            error?.let { Text(it, color = c.needsText, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            PrimaryButton(
                when {
                    busy -> "Saving…"
                    routine == null -> "Create routine"
                    else -> "Save changes"
                },
                Modifier.fillMaxWidth(),
                enabled = canSave,
                icon = signalIcon(SignalIcons.Check),
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun WebhookCard(
    webhook: RoutineWebhook?,
    busy: Boolean,
    error: String?,
    keyRevealed: Boolean,
    onLoad: (Boolean) -> Unit,
    onReveal: (Boolean) -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val c = Hypurr.colors
    LaunchedEffect(Unit) { if (webhook == null && !busy) onLoad(false) }
    FieldCard("Webhook") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                busy && webhook == null -> Text("Loading webhook…", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                error != null && webhook == null -> {
                    Text(error, color = c.needsText, style = MaterialTheme.typography.bodySmall)
                    SoftButton("Try again", Modifier.fillMaxWidth()) { onLoad(false) }
                }
                webhook != null -> {
                    val publicUrl = webhook.url
                    Text(
                        if (publicUrl != null) "Public URL (works while the computer is off too)"
                        else "Local URL (only on this computer; turn on the cloud relay for a public one)",
                        color = c.tertiary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    val shownUrl = publicUrl ?: webhook.localUrl
                    MonoLine(shownUrl)
                    SoftButton("Copy URL", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Paste)) { onCopy("Webhook URL", shownUrl) }
                    Text("Key", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                    MonoLine(if (keyRevealed) webhook.key else "•".repeat(webhook.key.length.coerceAtMost(24).coerceAtLeast(8)))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(if (keyRevealed) "Hide" else "Reveal", Modifier.weight(1f)) { onReveal(!keyRevealed) }
                        SoftButton("Copy key", Modifier.weight(1f), icon = signalIcon(SignalIcons.Paste)) { onCopy("Webhook key", webhook.key) }
                    }
                    Text(
                        if (webhook.connected) "A sender has connected." else "Waiting for the first authenticated delivery.",
                        color = c.secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SoftButton("Replace key", Modifier.fillMaxWidth(), tint = c.needsText) { onLoad(true) }
                    error?.let { Text(it, color = c.needsText, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun FieldCard(title: String, content: @Composable () -> Unit) {
    val c = Hypurr.colors
    Column(
        Modifier.fillMaxWidth().panel(RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = c.text, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun KindChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Hypurr.colors
    Text(
        label,
        color = if (on) c.onAccent else c.text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) c.accent else c.surface2)
            .pressable(label, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun RoutineField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    mono: Boolean = false,
) {
    val c = Hypurr.colors
    val style = (if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    else MaterialTheme.typography.bodyMedium).copy(color = c.text)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2)
            .border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = c.tertiary, style = style)
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            textStyle = style,
            cursorBrush = SolidColor(c.accent),
            singleLine = singleLine,
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MonoLine(text: String) {
    val c = Hypurr.colors
    Text(
        text,
        color = c.text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface2)
            .padding(12.dp),
    )
}

internal fun formatWhen(ms: Long): String {
    val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
    fmt.timeZone = TimeZone.getDefault()
    return fmt.format(Date(ms))
}
