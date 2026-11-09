package com.ragul84.hypurr.ui.screens
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.signalIcon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.Color
import com.ragul84.hypurr.model.Actor
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.Attachment
import com.ragul84.hypurr.model.QuickReactions
import com.ragul84.hypurr.model.ThreadSummary
import com.ragul84.hypurr.model.isImage
import com.ragul84.hypurr.model.sizeLabel
import com.ragul84.hypurr.data.PickedFile
import com.ragul84.hypurr.ui.MarkdownText
import com.ragul84.hypurr.ui.Markdown
import com.ragul84.hypurr.ui.avatarColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import com.ragul84.hypurr.model.Checkpoint
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.Integrations
import com.ragul84.hypurr.model.Learning
import com.ragul84.hypurr.model.TaskInfo
import com.ragul84.hypurr.ui.Pill
import com.ragul84.hypurr.ui.relativeTime
import com.ragul84.hypurr.ui.riskColor
import com.ragul84.hypurr.ui.riskLabel
import com.ragul84.hypurr.ui.ApprovalActions
import com.ragul84.hypurr.ui.BotAvatar
import com.ragul84.hypurr.ui.motion.DotCatMark
import com.ragul84.hypurr.ui.DotCat
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.motion.ApprovalDropIn
import com.ragul84.hypurr.ui.motion.StreamCaret
import com.ragul84.hypurr.ui.CardShape
import com.ragul84.hypurr.ui.IconTile
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.RiskTag
import com.ragul84.hypurr.ui.SignalChip
import com.ragul84.hypurr.ui.panel
import com.ragul84.hypurr.ui.theme.SignalType
import androidx.compose.foundation.border
import com.ragul84.hypurr.ui.WorkingPhase
import com.ragul84.hypurr.ui.motion.DoneRule
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion

/** What the composer's + offers. */
enum class AttachKind { Photos, Files, Paste }

/** Files waiting in the composer, and what the chat can do with them. */
data class ComposerFiles(
    val files: List<PickedFile> = emptyList(),
    /** False in group chats: a group has no folder for files. */
    val canAttach: Boolean = true,
    /** Files need the computer online (they don't go through the relay mailbox). */
    val online: Boolean = true,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    bot: Bot,
    entries: List<Entry>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRespond: (Entry, String?) -> Unit,
    onUndo: (Entry) -> Unit = {},
    onRollback: (Checkpoint) -> Unit = {},
    onFinishTask: (FinishOptions) -> Unit = {},
    onSaveCheckpoint: () -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    /** Work tools on the computer: which finish options to offer. */
    integrations: Integrations? = null,
    initialCheckpointsOpen: Boolean = false,
    initialFinishOpen: Boolean = false,
    /** This phone's role: members can't answer cards that need an admin. */
    you: Actor? = null,
    now: Long = System.currentTimeMillis(),
    /** Every bot on the computer: group replies show who wrote them. */
    bots: Map<String, Bot> = emptyMap(),
    /** Set when this is a thread: its root message (entries are then the replies). */
    threadRoot: Entry? = null,
    composer: ComposerFiles = ComposerFiles(),
    onAttach: (AttachKind) -> Unit = {},
    onRemoveFile: (Int) -> Unit = {},
    /** Sent and fetched files by upload id (pictures show inline). */
    images: Map<String, ByteArray> = emptyMap(),
    onLoadAttachment: (Attachment) -> Unit = {},
    onOpenFile: (Attachment) -> Unit = {},
    onOpenThread: (Entry) -> Unit = {},
    onReact: (Entry, String) -> Unit = { _, _ -> },
    /** Opens the bot's (or group's) settings; null hides it. */
    onEdit: (() -> Unit)? = null,
    /** Opens a routine from a chat notice; null ignores taps. */
    onOpenRoutine: ((String) -> Unit)? = null,
    initialActionsFor: String? = null,
    /** Opens the folder picker (a new task in another folder); null hides "Change". */
    onChangeFolder: (() -> Unit)? = null,
    /** Learning mode panel for a task (Teach me cards, quiz); null hides it. */
    learn: (@Composable () -> Unit)? = null,
    /** "Show me it works" proof card for a task; null hides it. */
    proof: (@Composable () -> Unit)? = null,
) {
    val c = Hypurr.colors
    val inThread = threadRoot != null
    val chat = entries.filter { it.isChat }.reversed()
    val list = rememberLazyListState()
    val task = bot.task.takeIf { !inThread }
    val folder = (task?.project?.takeIf { it.isNotBlank() } ?: bot.cwd.takeIf { it.isNotBlank() && !bot.isGroup })?.takeIf { !inThread }
    var checkpointsOpen by remember { mutableStateOf(initialCheckpointsOpen) }
    var actionsFor by remember { mutableStateOf(initialActionsFor?.let { id -> (entries + listOfNotNull(threadRoot)).firstOrNull { it.id == id } }) }
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    val media = AttachmentUi(images, onLoadAttachment, onOpenFile) { viewing = it }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 92.dp + (if (folder != null || task != null) 48.dp else 0.dp),
                bottom = if (composer.files.isEmpty()) 104.dp else 172.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (proof != null && !inThread) item(key = "proof") { Box(Modifier.animateItem()) { proof() } }
            if (learn != null && !inThread) item(key = "learn") { Box(Modifier.animateItem()) { learn() } }
            if (bot.status == "working" && (if (inThread) bot.workingThread == threadRoot?.id else bot.workingThread == null)) {
                item(key = "working") { WorkingRow(bot, bots, Modifier.animateItem()) }
            }
            items(chat, key = { it.data.clientNonce ?: it.id }) { entry ->
                Box(Modifier.animateItem(fadeInSpec = Motion.effects(), placementSpec = Motion.offset)) {
                    Message(entry, bot, bots, task, you, media, inThread, onRespond, onUndo, onOpenLink, onOpenThread, onReact,
                        live = bot.status == "working" && entry.kind == "agent" && entry.data.final != true && entry == chat.firstOrNull(),
                        onOpenRoutine = onOpenRoutine, onActions = { actionsFor = it })
                }
            }
            if (threadRoot != null) {
                item(key = "root-divider") {
                    val n = chat.count { it.kind == "user" || it.kind == "agent" }
                    Text(if (n == 1) "1 reply" else "$n replies", color = c.tertiary, style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
                item(key = "root") {
                    Message(threadRoot, bot, bots, task, you, media, true, onRespond, onUndo, onOpenLink, onOpenThread, onReact,
                        onOpenRoutine = onOpenRoutine, onActions = { actionsFor = it })
                }
            } else if (chat.isEmpty()) item(key = "empty") { EmptyChat(bot, bots) }
        }
        // Signal header: bare back chevron, bot tile, name + agent line, more. Chips row under a hairline.
        Column(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
            Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
                Spacer(Modifier.width(4.dp))
                Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).then(if (onEdit != null && !inThread) Modifier.pressable("${bot.name} settings", onClick = onEdit) else Modifier),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (bot.isGroup) GroupAvatar(bot, bots, 38.dp) else BotAvatar(bot, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (inThread) "Thread" else bot.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        val sub = when {
                            inThread -> "in ${bot.name}"
                            bot.isGroup -> groupMembersLine(bot, bots)
                            else -> listOfNotNull(agentName(bot.backend), when {
                                bot.needsInput -> "waiting on you"
                                bot.status == "working" -> "working"
                                else -> null
                            }).joinToString(" · ").ifEmpty { bot.description.ifEmpty { "Ready" } }
                        }
                        Text(sub, color = if (bot.needsInput && !inThread) c.needsText else c.tertiary, style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (onEdit != null && !inThread) IconBubble(signalIcon(SignalIcons.More), "More", onClick = onEdit)
            }
            if (folder != null || task != null) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (folder != null) FolderChip(folder, onClick = onChangeFolder)
                    if (task != null) TaskChip(task) { checkpointsOpen = true }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        }
        Composer(draft, onDraftChange, onSend, Modifier.align(Alignment.BottomCenter), composer, images, onAttach, onRemoveFile,
            if (inThread) "Reply in thread" else "Message ${bot.name}…", working = bot.status == "working", onStop = onStop)
        if (task != null) {
            CheckpointSheet(checkpointsOpen, task, now, integrations, initialFinishOpen, onClose = { checkpointsOpen = false },
                onRollback = { checkpointsOpen = false; onRollback(it) },
                onFinish = { checkpointsOpen = false; onFinishTask(it) }, onSave = onSaveCheckpoint)
        }
        MessageActions(actionsFor, canThread = !inThread, onClose = { actionsFor = null },
            onReact = { e, emoji -> actionsFor = null; onReact(e, emoji) },
            onThread = { actionsFor = null; onOpenThread(it) })
        viewing?.let { a -> ImageViewer(a, images[a.id]) { viewing = null } }
    }
}

/** How a chat shows files: the cached bytes, and what tapping does. */
internal class AttachmentUi(
    val images: Map<String, ByteArray>,
    val load: (Attachment) -> Unit,
    val open: (Attachment) -> Unit,
    val view: (Attachment) -> Unit,
)

/** One chat message with its author (groups), files, reactions and thread summary. Long-press for actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Message(entry: Entry, bot: Bot, bots: Map<String, Bot>, task: TaskInfo?, you: Actor?, media: AttachmentUi, inThread: Boolean,
                    onRespond: (Entry, String?) -> Unit, onUndo: (Entry) -> Unit, onOpenLink: (String) -> Unit,
                    onOpenThread: (Entry) -> Unit, onReact: (Entry, String) -> Unit, live: Boolean = false,
                    onOpenRoutine: ((String) -> Unit)? = null, onActions: (Entry) -> Unit) {
    val talk = entry.kind == "user" || entry.kind == "agent"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (entry.kind == "user") Alignment.End else Alignment.Start) {
        when (entry.kind) {
            "user" -> UserBubble(entry, media) { onActions(entry) }
            "agent" -> AgentBubble(entry, if (bot.isGroup) entry.data.author?.let(bots::get) else null, live) { onActions(entry) }
            "permission" -> PermissionCard(entry, task, onRespond, onUndo, you?.isAdmin != false)
            else -> entry.data.learning?.let { LearningCard(it, onOpenLink) } ?: Notice(entry, onOpenRoutine)
        }
        if (talk) {
            val reactions = entry.data.reactions.orEmpty()
            if (reactions.isNotEmpty()) Reactions(reactions) { onReact(entry, it) }
            val thread = entry.data.thread
            if (!inThread && thread != null && thread.count > 0) ThreadSummaryRow(thread, bots) { onOpenThread(entry) }
        }
    }
}

@Composable
private fun Reactions(reactions: List<String>, onTap: (String) -> Unit) {
    val c = Hypurr.colors
    Row(Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        reactions.forEach { emoji ->
            Text(emoji, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(50))
                .pressable("Remove $emoji") { onTap(emoji) }.padding(horizontal = 9.dp, vertical = 3.dp))
        }
    }
}

/** Under a message with replies: who replied, how many, and how many are new. */
@Composable
private fun ThreadSummaryRow(thread: ThreadSummary, bots: Map<String, Bot>, onOpen: () -> Unit) {
    val c = Hypurr.colors
    Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(50)).pressable("Open thread", onClick = onOpen)
        .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width((18 + 12 * (thread.authors.take(3).size - 1).coerceAtLeast(0)).dp).height(18.dp)) {
            thread.authors.take(3).forEachIndexed { i, id ->
                Box(Modifier.padding(start = (12 * i).dp)) {
                    bots[id]?.let { BotAvatar(it, 18.dp) } ?: Box(Modifier.size(18.dp).clip(CircleShape).background(c.bubbleUser))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(if (thread.count == 1) "1 reply" else "${thread.count} replies", color = c.text, style = MaterialTheme.typography.labelLarge)
        if (thread.unread > 0) {
            Spacer(Modifier.width(6.dp))
            Pill("${thread.unread} new", c.text)
        }
    }
}

/** Long-press on a message: quick reactions, reply in thread, copy. */
@Composable
private fun MessageActions(entry: Entry?, canThread: Boolean, onClose: () -> Unit, onReact: (Entry, String) -> Unit, onThread: (Entry) -> Unit) {
    val c = Hypurr.colors
    val clipboard = LocalClipboardManager.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(entry != null, enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects())) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)).pressable("Close", onClick = onClose))
        }
        AnimatedVisibility(entry != null, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Motion.offset) { it } + fadeIn(), exit = slideOutVertically(Motion.offset) { it } + fadeOut()) {
            val e = entry ?: return@AnimatedVisibility
            Column(Modifier.fillMaxWidth().panel(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), c.surface).navigationBarsPadding()
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(Markdown.plain(e.data.text.orEmpty()).ifEmpty { e.data.attachments.orEmpty().joinToString { it.name } }, color = c.secondary,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    QuickReactions.forEach { emoji ->
                        val on = e.data.reactions.orEmpty().contains(emoji)
                        Box(Modifier.size(46.dp).clip(CircleShape).background(if (on) c.surface2 else c.bg).border(1.dp, if (on) c.text else c.border, CircleShape)
                            .pressable(if (on) "Remove $emoji" else "React $emoji") { onReact(e, emoji) }, contentAlignment = Alignment.Center) {
                            Text(emoji, fontSize = 22.sp)
                        }
                    }
                }
                if (canThread && e.threadId == null && e.seq > 0) {
                    ActionRow(signalIcon(SignalIcons.Back), "Reply in thread") { onThread(e) }
                }
                if (!e.data.text.isNullOrEmpty()) ActionRow(signalIcon(SignalIcons.Paste), "Copy text") {
                    clipboard.setText(AnnotatedString(e.data.text))
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = Hypurr.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).pressable(label, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = c.text, style = MaterialTheme.typography.titleSmall)
    }
}

/** A picture, full size; tap anywhere to close. */
@Composable
private fun ImageViewer(a: Attachment, bytes: ByteArray?, onClose: () -> Unit) {
    val bitmap = rememberBitmap(bytes)
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.92f)).pressable("Close picture", onClick = onClose)
        .statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, a.name, Modifier.fillMaxWidth().padding(12.dp), contentScale = ContentScale.Fit)
        else DotCatMark(40.dp)
        Text(a.name, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))
    }
}

/** Decodes picture bytes once, scaled down for the screen. */
@Composable
internal fun rememberBitmap(bytes: ByteArray?, maxSide: Int = 1600): ImageBitmap? = remember(bytes) {
    bytes?.let { b ->
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        }.getOrNull()
    }
}

/** A group's avatar: its first members, overlapping. */
@Composable
fun GroupAvatar(group: Bot, bots: Map<String, Bot>, size: Dp = 48.dp) {
    val c = Hypurr.colors
    val members = group.members.mapNotNull(bots::get).take(3)
    if (members.isEmpty()) {
        BotAvatar(group, size)
        return
    }
    val small = size * 0.62f
    Box(Modifier.size(size)) {
        members.forEachIndexed { i, m ->
            val align = when (i) { 0 -> Alignment.TopStart; 1 -> Alignment.BottomEnd; else -> Alignment.BottomStart }
            Box(Modifier.align(align).clip(CircleShape).background(c.bg).padding(1.5.dp)) { BotAvatar(m.copy(status = "idle"), small) }
        }
    }
}

fun groupMembersLine(group: Bot, bots: Map<String, Bot>): String {
    val names = group.members.mapNotNull { bots[it]?.name }
    return when {
        group.description.isNotEmpty() -> group.description
        names.isEmpty() -> "Group"
        else -> "You, " + names.joinToString(", ")
    }
}

/** The task's safety net as a chip: the safe branch (tap for checkpoints), or "No safety net". */
@Composable
private fun TaskChip(task: TaskInfo, onOpen: () -> Unit) {
    val c = Hypurr.colors
    val label = when {
        task.isFinishing -> "Finishing…"
        !task.isActive -> task.pr?.let { "PR #${it.number}" } ?: "Finished"
        task.hasSafetyNet -> (task.branch ?: "Safe branch").let { b -> if (b.length > 22) b.take(21) + "…" else b }
        else -> "No safety net"
    }
    SignalChip(signalIcon(if (task.hasSafetyNet) SignalIcons.Branch else SignalIcons.NeedsYou), label, quiet = true, onClick = onOpen)
    task.usage?.takeIf { it.turns > 0 || it.cost > 0 }?.let {
        Text(it.label, color = c.tertiary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** How to wrap up a task; unset options follow the computer's Work tools settings. */
data class FinishOptions(val learning: Boolean, val openPr: Boolean?, val notify: Boolean?)

/** One-tap rollback: every checkpoint, newest first. Going back asks once, inline. */
@Composable
private fun CheckpointSheet(open: Boolean, task: TaskInfo, now: Long, integrations: Integrations?, initialFinish: Boolean,
                            onClose: () -> Unit, onRollback: (Checkpoint) -> Unit, onFinish: (FinishOptions) -> Unit, onSave: () -> Unit) {
    val c = Hypurr.colors
    var confirm by remember { mutableStateOf<String?>(null) }
    var finishing by remember { mutableStateOf(initialFinish) }
    val work = integrations ?: Integrations()
    var learning by remember(work) { mutableStateOf(work.learning) }
    var openPr by remember(work) { mutableStateOf(work.autoPr && work.github.configured) }
    var notify by remember(work) { mutableStateOf(work.notify && work.anyChat) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects())) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)).pressable("Close", onClick = onClose))
        }
        AnimatedVisibility(open, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Motion.offset) { it } + fadeIn(), exit = slideOutVertically(Motion.offset) { it } + fadeOut()) {
            Column(Modifier.fillMaxWidth().panel(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), c.surface)
                .navigationBarsPadding().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Checkpoints", style = MaterialTheme.typography.titleLarge, color = c.text)
                        Text("Go back to any point. What's there now is saved first, so nothing is lost.", color = c.secondary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconBubble(signalIcon(SignalIcons.Close), "Close", onClick = onClose)
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val list = task.checkpoints.reversed()
                    items(list, key = { it.id }) { cp ->
                        val latest = cp == list.first()
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface2).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(cp.label, color = c.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val files = if (cp.files.isEmpty()) "" else " · ${cp.files.size} file${if (cp.files.size == 1) "" else "s"}"
                                    Text("${relativeTime(cp.at, now)}$files", color = c.tertiary, style = MaterialTheme.typography.labelSmall)
                                }
                                if (latest) Pill("Now", c.text)
                                else if (task.isActive) Text("Go back", color = c.text, style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).pressable("Go back to ${cp.label}") { confirm = cp.id }
                                        .padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                            AnimatedVisibility(confirm == cp.id) {
                                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PrimaryButton("Go back here") { confirm = null; onRollback(cp) }
                                    SoftButton("Cancel", tint = c.secondary) { confirm = null }
                                }
                            }
                        }
                    }
                }
                if (task.isActive) {
                    AnimatedVisibility(finishing) {
                        Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface2)
                            .padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Finish the task", color = c.text, style = MaterialTheme.typography.titleSmall)
                            ToggleRow("Explain what changed", "Learning mode: the agent sums up what it changed, why, and what to check",
                                learning, true) { learning = it }
                            ToggleRow("Open a pull request", if (work.github.configured) "Uploads the task branch and opens a PR for review"
                                else "Set up GitHub in Settings › Work tools", openPr, work.github.configured) { openPr = it }
                            ToggleRow("Post the result", if (work.anyChat) listOfNotNull("Slack".takeIf { work.slack.configured },
                                "Teams".takeIf { work.teams.configured }).joinToString(" and ")
                                else "Set up Slack or Teams in Settings › Work tools", notify, work.anyChat) { notify = it }
                            PrimaryButton("Finish", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Check)) {
                                finishing = false
                                onFinish(FinishOptions(learning, openPr, notify))
                            }
                        }
                    }
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("Save now", Modifier.weight(1f), icon = signalIcon(SignalIcons.Docs), onClick = onSave)
                        if (!finishing) {
                            PrimaryButton("Finish task", Modifier.weight(1f), icon = signalIcon(SignalIcons.Check)) { finishing = true }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChat(bot: Bot, bots: Map<String, Bot>) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (bot.isGroup) GroupAvatar(bot, bots, 72.dp) else BotAvatar(bot, 72.dp)
        Text(bot.name, style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(top = 12.dp))
        Text(if (bot.isGroup) "${groupMembersLine(bot, bots)}\nEveryone answers; @mention someone to ask just them."
            else bot.description.ifEmpty { bot.folderName }, color = c.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(entry: Entry, media: AttachmentUi, onLongPress: () -> Unit) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        entry.data.attachments.orEmpty().forEach { a ->
            AttachmentView(a, media, Modifier.padding(start = 56.dp, bottom = 4.dp), onLongPress)
        }
        if (!entry.data.text.isNullOrEmpty()) {
            // Ink bubble, white text (light); white bubble, ink text (dark). Tail corner bottom-right.
            Text(entry.data.text, color = c.onBubbleUser, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
                modifier = Modifier.padding(start = 56.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(c.bubbleUser).combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = "Message actions")
                    .padding(horizontal = 16.dp, vertical = 11.dp))
        }
        when (entry.data.status) {
            "queued" -> Meta(signalIcon(SignalIcons.Activity), if (entry.data.attachments.isNullOrEmpty()) "Queued" else "Sending…", c.tertiary)
            "failed" -> Meta(signalIcon(SignalIcons.Deny), "Not sent", c.danger)
            "cancelled" -> Meta(signalIcon(SignalIcons.Deny), "Cancelled", c.tertiary)
        }
    }
}

/** A sent file: a picture inline (tap for full size) or a card with its name and size (tap to open). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentView(a: Attachment, media: AttachmentUi, modifier: Modifier, onLongPress: () -> Unit) {
    val c = Hypurr.colors
    if (a.isImage) {
        LaunchedEffect(a.id) { media.load(a) }
        val bitmap = rememberBitmap(media.images[a.id], 900)
        if (bitmap != null) {
            Image(bitmap, a.name, modifier.widthIn(max = 240.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(18.dp))
                .combinedClickable(onClickLabel = "View ${a.name}", onClick = { media.view(a) }, onLongClick = onLongPress),
                contentScale = ContentScale.Fit)
            return
        }
    }
    Row(modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(18.dp))
        .combinedClickable(onClickLabel = "Open ${a.name}", onClick = { if (a.isImage) media.view(a) else media.open(a) }, onLongClick = onLongPress)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
            Icon(if (a.isImage) signalIcon(SignalIcons.Image) else signalIcon(SignalIcons.File), null, tint = c.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(a.name, color = c.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sizeLabel(a.size), color = c.tertiary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(top = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** An agent's reply: unboxed Markdown on the ground, like the mockup. A live reply ends in the red caret. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentBubble(entry: Entry, author: Bot?, live: Boolean = false, onLongPress: () -> Unit) {
    Column(Modifier.padding(end = 24.dp)) {
        if (author != null) Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(author.copy(status = "idle"), 20.dp)
            Spacer(Modifier.width(6.dp))
            Text(author.name, color = Hypurr.colors.text, style = MaterialTheme.typography.labelLarge)
        }
        MarkdownText(entry.data.text.orEmpty(), Modifier
            .combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = "Message actions"))
        if (live) StreamCaret(Modifier.padding(top = 4.dp))
    }
}

/**
 * An explained approval card: one plain sentence, a risk level from the host's rules, the technical detail
 * folded away. Choices are text (an icon would be ambiguous). A task's card offers Undo after approving.
 */
@Composable
private fun PermissionCard(entry: Entry, task: TaskInfo?, onRespond: (Entry, String?) -> Unit, onUndo: (Entry) -> Unit,
                           isAdmin: Boolean = true) {
    val c = Hypurr.colors
    val d = entry.data
    val pending = d.status == null || d.status == "pending"
    val blocked = d.blocked != null
    val risk = riskColor(d.risk)
    var details by remember(entry.id) { mutableStateOf(d.explain == null) }
    val headline = when {
        blocked -> d.blocked ?: "Blocked by the safety net"
        !d.explain.isNullOrBlank() -> d.explain
        !d.title.isNullOrBlank() -> d.title
        else -> "Allow this action?"
    }
    val meta = listOfNotNull(
        d.riskReasons?.firstOrNull(),
        d.detail?.takeIf { it.isNotBlank() && it != d.command },
    ).firstOrNull()
    val detail = d.command ?: d.detail
    val canAnswer = pending && (d.needsAdmin != true || isAdmin) && !d.options.isNullOrEmpty()
    ApprovalDropIn(Modifier.fillMaxWidth()) {
    SignalCard(Modifier.fillMaxWidth().animateContentSize(Motion.spatialDefault()), emphasis = pending && !blocked) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (label, ink) = when {
                    blocked -> "Blocked" to c.needsText
                    pending && d.needsAdmin == true && !isAdmin -> "Needs admin" to c.needsText
                    pending -> "Needs you" to c.needsText
                    else -> "Answered" to c.tertiary
                }
                Icon(signalIcon(if (blocked || pending) SignalIcons.NeedsYou else SignalIcons.Check), null, tint = ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(label.uppercase(), color = ink, style = SignalType.eyebrow, modifier = Modifier.weight(1f))
                if (d.risk != null && (pending || blocked)) RiskTag(d.risk)
            }
            Text(headline, color = c.text, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp), modifier = Modifier.padding(top = 10.dp))
            if (!detail.isNullOrBlank()) {
                if (d.explain != null && !pending) {
                    Text(if (details) "Hide command" else "Show command", color = c.text, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp).pressable(if (details) "Hide command" else "Show command") { details = !details }
                            .padding(vertical = 2.dp))
                }
                if (details || d.explain == null || pending) {
                    Text("$ $detail", color = c.text, style = SignalType.mono, maxLines = 6, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(c.surface2).padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
            if (meta != null) {
                Text(meta, color = c.secondary, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    modifier = Modifier.padding(top = 10.dp))
            }
            if (pending && d.checkpoint != null && task?.hasSafetyNet == true) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(signalIcon(SignalIcons.Undo), null, tint = c.tertiary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("You can undo this from a checkpoint", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (pending && d.needsAdmin == true) {
                Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2)
                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(signalIcon(SignalIcons.Shield), null, tint = c.text, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isAdmin) "Your team's rules: an admin approves this" else "Needs an admin: your team's rules say an admin approves this",
                        color = c.text, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (canAnswer) {
                ApprovalActions(d.options.orEmpty(), Modifier.padding(top = 14.dp)) { onRespond(entry, it) }
                VoiceApproval(entry.id, d.risk, d.options.orEmpty(), Modifier.padding(top = 12.dp)) { onRespond(entry, it) }
            } else if (!blocked && !pending) {
                val option = d.options?.firstOrNull { it.optionId == d.selected }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val approved = option != null && !option.kind.startsWith("reject")
                    Text((if (approved) "Allowed · " else if (option != null) "Denied · " else "") + (option?.name ?: d.status.orEmpty().replaceFirstChar(Char::uppercase)),
                        color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (approved && d.checkpoint != null && task?.hasSafetyNet == true && task.isActive) {
                        SoftButton("Undo", icon = signalIcon(SignalIcons.Undo)) { onUndo(entry) }
                    }
                }
            }
        }
    }
    }
}

/** Learning mode: what a finished task changed and why, its files, PR, cost and where it was posted. */
@Composable
private fun LearningCard(l: Learning, onOpenLink: (String) -> Unit) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxWidth().panel(CardShape, c.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(signalIcon(SignalIcons.Explain), null, tint = c.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("WHAT CHANGED AND WHY", color = c.text, style = SignalType.eyebrow, modifier = Modifier.weight(1f))
            l.cost?.let { Pill(it.label, c.text) }
        }
        SelectionContainer { Text(l.summary, color = c.text, style = MaterialTheme.typography.bodyLarge) }
        ReadAloud(l.summary)
        DoneRule(visible = true, Modifier.padding(top = 8.dp))
        if (l.files.isNotEmpty()) WhatChangedFiles(l.files)
        val posted = l.posted.map { when (it) { "slack" -> "Slack"; "teams" -> "Teams"; "jira" -> "Jira"; else -> it } }
        if (posted.isNotEmpty()) Text("Posted to ${posted.joinToString(", ")}", color = c.secondary, style = MaterialTheme.typography.bodySmall)
        l.errors.forEach { Text(it, color = c.needsText, style = MaterialTheme.typography.bodySmall) }
        l.pr?.let { pr ->
            SoftButton("Open pull request #${pr.number}", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Link)) { onOpenLink(pr.url) }
        }
    }
}

@Composable
private fun Notice(entry: Entry, onOpenRoutine: ((String) -> Unit)? = null) {
    val c = Hypurr.colors
    val error = entry.data.style == "error"
    val routineId = entry.data.routineId
    val base = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp)
    val mod = if (routineId != null && onOpenRoutine != null) base.pressable("Open routine") { onOpenRoutine(routineId) } else base
    Text(entry.data.text.orEmpty(), color = if (error) c.danger else c.tertiary, style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center, modifier = mod)
    if (error) com.ragul84.hypurr.ui.StartFreeAction(entry.data.text.orEmpty(), Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp))
}

@Composable
private fun WorkingRow(bot: Bot, bots: Map<String, Bot>, modifier: Modifier = Modifier) {
    WorkingPhase(bot.activity.ifEmpty { "Thinking…" }, modifier.padding(top = 4.dp, end = 24.dp).fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit, modifier: Modifier, composer: ComposerFiles,
                     images: Map<String, ByteArray>, onAttach: (AttachKind) -> Unit, onRemoveFile: (Int) -> Unit, hint: String,
                     working: Boolean = false, onStop: () -> Unit = {}) {
    val c = Hypurr.colors
    val files = composer.files
    val canSend = (draft.isNotBlank() || files.isNotEmpty()) && (files.isEmpty() || composer.online)
    val stop = working && !canSend
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
        AnimatedVisibility(menu && composer.canAttach) {
            Row(Modifier.padding(bottom = 8.dp).panel(RoundedCornerShape(24.dp), c.surface).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Triple(AttachKind.Photos, "Photos", signalIcon(SignalIcons.Image)), Triple(AttachKind.Files, "Files", signalIcon(SignalIcons.File)),
                    Triple(AttachKind.Paste, "Paste", signalIcon(SignalIcons.Paste))).forEach { (kind, label, icon) ->
                    SoftButton(label, icon = icon) { menu = false; onAttach(kind) }
                }
            }
        }
        if (files.isNotEmpty()) {
            FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                files.forEachIndexed { i, f ->
                    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(16.dp))
                        .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val thumb = if (f.isImage) rememberBitmap(f.bytes, 200) else null
                        if (thumb != null) Image(thumb, null, Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        else Icon(signalIcon(SignalIcons.File), null, tint = c.text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(f.name, color = c.text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp))
                        IconBubble(signalIcon(SignalIcons.Close), "Remove ${f.name}", size = 30.dp) { onRemoveFile(i) }
                    }
                }
            }
            if (!composer.online) Text("Files send when your computer is online.", color = c.needsText, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(28.dp))
                .padding(start = if (composer.canAttach) 4.dp else 18.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (composer.canAttach) {
                IconBubble(if (menu) signalIcon(SignalIcons.Close) else signalIcon(SignalIcons.Plus), if (menu) "Close" else "Add files",
                    tint = c.secondary, size = 42.dp) { menu = !menu }
                Spacer(Modifier.width(2.dp))
            }
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (draft.isEmpty()) Text(hint, color = c.tertiary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(draft, onDraftChange, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                    cursorBrush = SolidColor(c.text), maxLines = 6, modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp))
            }
            Spacer(Modifier.width(8.dp))
            val fill by animateColorAsState(when { stop -> c.needs; canSend -> c.accent; else -> c.surface2 }, Motion.effects(), label = "send")
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(fill)
                    .pressable(if (stop) "Stop" else "Send", enabled = stop || canSend, onClick = { if (stop) onStop() else onSend() }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(signalIcon(if (stop) SignalIcons.Stop else SignalIcons.Send), null,
                    tint = when { stop -> c.onNeeds; canSend -> c.onAccent; else -> c.tertiary }, modifier = Modifier.size(if (stop) 18.dp else 20.dp))
            }
        }
    }
}

/**
 * Answer an approval by voice: "haan / yes / aamaa / allow" or "no / nahi / illa / stop".
 * "No" sends at once. "Yes" sends only for a low-risk action; anything else asks for a tap on
 * screen, so voice alone never approves a risky action. "Always" options are never picked by voice.
 */
@Composable
internal fun VoiceApproval(entryId: String, risk: String?, options: List<com.ragul84.hypurr.model.PermissionOption>,
                           modifier: Modifier = Modifier, initialConfirm: String? = null, onChoose: (String?) -> Unit) {
    val c = Hypurr.colors
    val voice = com.ragul84.hypurr.voice.LocalVoiceSettings.current
    val deny = options.firstOrNull { it.kind.startsWith("reject") }
    val allow = options.firstOrNull { !it.kind.startsWith("reject") && !it.kind.contains("always", true) && !it.name.contains("always", true) }
    var confirm by remember(entryId) { mutableStateOf(initialConfirm) }
    var hint by remember(entryId) { mutableStateOf<String?>(null) }
    val mic = com.ragul84.hypurr.voice.rememberVoiceInput(voice.language) { said ->
        when (com.ragul84.hypurr.voice.voiceApprovalStep(com.ragul84.hypurr.voice.parseVoiceAnswer(said), risk)) {
            com.ragul84.hypurr.voice.VoiceStep.SendDeny -> deny?.let { onChoose(it.optionId) }
            com.ragul84.hypurr.voice.VoiceStep.SendAllow -> allow?.let { onChoose(it.optionId) }
            com.ragul84.hypurr.voice.VoiceStep.ConfirmOnScreen -> { confirm = said; hint = null }
            com.ragul84.hypurr.voice.VoiceStep.AskAgain -> hint = "Heard “$said”. Say yes or no, or tap a button."
        }
    }
    Column(modifier.fillMaxWidth().animateContentSize(Motion.spatialDefault())) {
        val heard = confirm
        if (heard != null && allow != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("You said “$heard”. This one is ${if (risk == null) "not rated" else "$risk risk"}, so tap to confirm.",
                    color = c.text, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.ragul84.hypurr.ui.OutlineButton("Not now", Modifier.weight(1f)) { confirm = null }
                    com.ragul84.hypurr.ui.SignalAllowButton("Yes, ${allow.name.lowercase()}", Modifier.weight(1f)) { onChoose(allow.optionId) }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.ragul84.hypurr.ui.MicButton(mic.listening, voice.language, size = 40.dp) { if (mic.listening) mic.stop() else mic.start() }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (mic.listening) "Listening…" else "Or say yes or no", color = c.secondary, style = MaterialTheme.typography.labelLarge)
                    com.ragul84.hypurr.ui.VoiceHint(mic.listening, mic.partial, mic.error ?: hint, voice.language)
                }
            }
        }
    }
}

/** Read the agent's summary aloud in the chosen language (falls back to English with a note). */
@Composable
private fun ReadAloud(text: String) {
    if (text.isBlank()) return
    val c = Hypurr.colors
    val voice = com.ragul84.hypurr.voice.LocalVoiceSettings.current
    val speaker = com.ragul84.hypurr.voice.rememberSpeaker()
    Row(verticalAlignment = Alignment.CenterVertically) {
        SoftButton(if (speaker.speaking) "Stop reading" else "Read aloud", icon = signalIcon(SignalIcons.Speaker)) {
            if (speaker.speaking) speaker.stop() else speaker.speak(text, voice.language)
        }
    }
    speaker.notice?.let { Text(it, color = c.secondary, style = MaterialTheme.typography.bodySmall) }
}
