package com.ragul84.hypurr.ui.screens
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.motion.DotShimmer
import com.ragul84.hypurr.ui.signalIcon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.AvatarColors
import com.ragul84.hypurr.model.Backend
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.DirListing
import com.ragul84.hypurr.ui.BotAvatar
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.Pill
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.avatarColor
import com.ragul84.hypurr.ui.motion.CatAssemble
import com.ragul84.hypurr.ui.panel
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** New bot, bot settings, new group or group settings: one form (the iPhone's bot and group sheets). */
data class BotEditorState(
    val group: Boolean = false,
    /** Null for a new one. */
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val avatarColor: String = "blue",
    val backend: String? = null,
    /** Hypurr free model id (`hypurr/hypurr-free`), when the built-in agent is selected. */
    val model: String? = null,
    /** Null = a personal workspace Hypurr makes for the bot. */
    val folder: String? = null,
    /** ask | auto */
    val permission: String = "ask",
    val members: List<String> = emptyList(),
    val pinned: Boolean = false,
    val browsing: DirListing? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val confirmDelete: Boolean = false,
) {
    val isNew: Boolean get() = id == null
    val canSave: Boolean get() = !busy && if (group) name.isNotBlank() && members.isNotEmpty() else !isNew || backend != null

    /** `createBot` for a new one; the changed fields for `updateBot`. */
    fun toJson(original: Bot? = null): JsonObject = buildJsonObject {
        id?.let { put("id", it) }
        if (group) {
            if (isNew) put("kind", "group")
            put("name", name.trim())
            put("description", description.trim())
            put("members", JsonArray(members.map(::JsonPrimitive)))
        } else {
            // A new bot without a name gets one from its first conversations.
            if (isNew || name.trim() != original?.name) put("name", name.trim())
            put("description", description.trim())
            put("avatarColor", avatarColor)
            backend?.takeIf { isNew || it != original?.backend }?.let { put("backend", it) }
            model?.takeIf { it.isNotBlank() && (isNew || it != original?.model) }?.let { put("model", it) }
            if (isNew) put("cwd", folder.orEmpty())
            put("permission", permission)
        }
        if (!isNew) put("pinned", pinned)
    }

    companion object {
        fun newBot(backends: List<Backend>): BotEditorState {
            // Hypurr Agent first only when its free models are live; otherwise an agent that's installed and signed in.
            val runnable = backends.filter { it.available && it.installed && it.signedIn != false }
            val pick = backends.firstOrNull { it.builtin && it.gatewayReady == true && it.installed }
                ?: runnable.firstOrNull { !it.builtin } ?: runnable.firstOrNull()
                ?: backends.singleOrNull() ?: backends.firstOrNull()
            return BotEditorState(backend = pick?.id, model = pick?.let(::safeDefaultModel))
        }
        fun newGroup(members: List<String> = emptyList()) = BotEditorState(group = true, members = members)
        fun of(bot: Bot) = BotEditorState(
            group = bot.isGroup, id = bot.id, name = bot.name, description = bot.description, avatarColor = bot.avatarColor,
            backend = bot.backend.ifEmpty { null }, model = bot.model, folder = if (bot.managedWorkspace) null else bot.cwd, permission = bot.permission,
            members = bot.members, pinned = bot.pinned,
        )
    }
}

/** Agents a new bot can use: installed and runnable, and allowed by the team's rules. */
fun usableBackends(backends: List<Backend>, allowed: List<String>): List<Backend> =
    backends.filter { it.available && (allowed.isEmpty() || it.id in allowed) }
        .sortedByDescending { it.builtin }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotEditorScreen(
    state: BotEditorState,
    backends: List<Backend>,
    /** Agent bots that can join a group. */
    agentBots: List<Bot>,
    onChange: (BotEditorState) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    /** Lists a folder on the computer (null = home). */
    onBrowse: (String?) -> Unit,
    /** Opens a project shell in this bot's folder; null hides the row. */
    onTerminal: (() -> Unit)? = null,
) {
    val c = Hypurr.colors
    val title = when {
        state.group && state.isNew -> "New group"
        state.group -> "Group settings"
        state.isNew -> "New bot"
        else -> "Bot settings"
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = 120.dp)) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBubble(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text, modifier = Modifier.weight(1f))
            }
            if (!state.group) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                    if (state.isNew && !state.group) {
                        CatAssemble(playing = true, size = 96.dp, name = state.name.ifBlank { "New bot" })
                    } else {
                        BotAvatar(Bot(id = "preview", name = state.name.ifBlank { "?" }, avatarColor = state.avatarColor), 72.dp)
                    }
                }
            }
            Section(if (state.group) "Group" else "About") {
                SmallField(state.name, if (state.group) "Name, like “Shop team”" else if (state.isNew) "Name (optional: Hypurr names it after a few chats)" else "Name") {
                    onChange(state.copy(name = it))
                }
                SmallField(state.description, if (state.group) "What the room is for (its bots read this)" else "What it helps with (optional)", minLines = 2) {
                    onChange(state.copy(description = it))
                }
                if (!state.group) {
                    Text("Colour", color = c.secondary, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AvatarColors.forEach { id ->
                            val on = state.avatarColor == id
                            Box(Modifier.size(34.dp).clip(CircleShape).background(avatarColor(id))
                                .border(if (on) 2.dp else 1.dp, if (on) c.text else c.borderStrong, CircleShape)
                                .pressable("Colour $id", role = Role.RadioButton) { onChange(state.copy(avatarColor = id)) },
                                contentAlignment = Alignment.Center) {
                                if (on) Icon(signalIcon(SignalIcons.Check), null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            if (state.group) {
                Section("Bots in the group") {
                    if (agentBots.isEmpty()) Text("Make a bot first: a group is a room for your bots.", color = c.secondary,
                        style = MaterialTheme.typography.bodyMedium)
                    agentBots.forEach { bot ->
                        val on = bot.id in state.members
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).pressable(bot.name, role = Role.Checkbox) {
                            onChange(state.copy(members = if (on) state.members - bot.id else state.members + bot.id))
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            BotAvatar(bot.copy(status = "idle"), 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(bot.name, color = c.text, style = MaterialTheme.typography.titleSmall)
                                Text(bot.description.ifEmpty { bot.folderName }, color = c.secondary, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) c.accent else c.border), contentAlignment = Alignment.Center) {
                                if (on) Icon(signalIcon(SignalIcons.Check), null, tint = c.onAccent, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Text("Everyone answers a message; @mention a bot to ask just them.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Section("Agent") {
                    if (backends.isEmpty()) {
                        Text("No agent your team allows is installed on the computer. Install Hypurr Agent (free models) or Claude Code / Codex from the computer.",
                            color = c.warning, style = MaterialTheme.typography.bodyMedium)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val shown = backends.ifEmpty { listOfNotNull(state.backend?.let { Backend(it, it) }) }
                        shown.forEach { b ->
                            Choice(
                                label = b.name.ifEmpty { b.id },
                                on = state.backend == b.id,
                                badge = when {
                                    b.free -> "Free"
                                    else -> null
                                },
                            ) {
                                onChange(state.copy(backend = b.id, model = safeDefaultModel(b)))
                            }
                        }
                    }
                    val selected = backends.firstOrNull { it.id == state.backend }
                    if (selected != null && (selected.free || selected.builtin) && selected.freeModels.isNotEmpty()) {
                        Text("Free model", color = c.secondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        Text(selected.subtitle.ifEmpty { "Hypurr Free · for trying out, not for company code" }, color = c.tertiary,
                            style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp)) {
                            selected.freeModels.forEach { m ->
                                Choice(m.name.ifEmpty { m.id.substringAfterLast('/') }, state.model == m.id, badge = "Free") {
                                    onChange(state.copy(model = m.id))
                                }
                            }
                        }
                    }
                }
                Section("Folder") {
                    if (state.isNew) {
                        Choice("Personal workspace", state.folder == null, Modifier.fillMaxWidth(), signalIcon(SignalIcons.Host)) {
                            onChange(state.copy(folder = null, browsing = null))
                        }
                        Choice(state.folder?.substringAfterLast('/')?.let { "Folder: $it" } ?: "A folder on the computer…", state.folder != null,
                            Modifier.fillMaxWidth(), signalIcon(SignalIcons.Folder)) {
                            onBrowse(state.folder ?: state.browsing?.path)
                        }
                        state.browsing?.let { FolderBrowser(it, state.folder, onPick = { onChange(state.copy(folder = it)) }, onBrowse = onBrowse) }
                    } else {
                        Text(state.folder ?: "Personal workspace", color = c.text, fontFamily = if (state.folder != null) FontFamily.Monospace else null,
                            style = MaterialTheme.typography.bodyMedium)
                        Text("A bot keeps its folder. Make a new bot to work somewhere else.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Section("Approvals") {
                    Choice("Ask me every time", state.permission == "ask", Modifier.fillMaxWidth()) { onChange(state.copy(permission = "ask")) }
                    Choice("Approve automatically", state.permission == "auto", Modifier.fillMaxWidth()) { onChange(state.copy(permission = "auto")) }
                    Text("Your team's rules and the safety net still apply: risky actions ask, protected branches stay blocked.",
                        color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!state.isNew) {
                Section("More") {
                    if (!state.group && onTerminal != null) {
                        SoftButton("Terminal", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Terminal)) { onTerminal() }
                        Text("Open a shell in this bot's project folder on the computer.", color = c.tertiary, style = MaterialTheme.typography.bodyMedium)
                    }
                    ToggleRow("Pin to the top", "Keeps it first in the list", state.pinned, true) { onChange(state.copy(pinned = it)) }
                    AnimatedVisibility(!state.confirmDelete) {
                        SoftButton(if (state.group) "Delete group" else "Delete bot", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Close), tint = c.needsText) {
                            onChange(state.copy(confirmDelete = true))
                        }
                    }
                    AnimatedVisibility(state.confirmDelete) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (state.group) "The group and its chat go away. Its bots stay." else "The bot and its chat go away. Its folder stays on the computer.",
                                color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SoftButton("Delete", Modifier.weight(1f), tint = c.needsText, onClick = onDelete)
                                SoftButton("Keep", Modifier.weight(1f), tint = c.secondary) { onChange(state.copy(confirmDelete = false)) }
                            }
                        }
                    }
                }
            }
            state.error?.let { Text(it, color = c.needsText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp, start = 8.dp)) }
        }
        if (state.isNew && !state.group && state.busy) {
            Box(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(c.surface)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                CatAssemble(playing = true, size = 64.dp, name = state.name.ifBlank { "New bot" })
            }
        }
        PrimaryButton(when {
            state.busy -> "Saving…"
            state.isNew && state.group -> "Create group"
            state.isNew -> "Create bot"
            else -> "Save"
        }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp).fillMaxWidth(), enabled = state.canSave,
            icon = signalIcon(SignalIcons.Check), onClick = onSave)
    }
}

@Composable
private fun Choice(label: String, on: Boolean, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, badge: String? = null, onClick: () -> Unit) {
    val c = Hypurr.colors
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(if (on) c.accent else c.surface2)
        .pressable(label, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = if (on) c.onAccent else c.secondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = if (on) c.onAccent else c.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Pill(badge, c.success)
        }
    }
}

/** Folders on the computer: go into one, up, or pick the one you're in. */
@Composable
private fun FolderBrowser(listing: DirListing, picked: String?, onPick: (String) -> Unit, onBrowse: (String?) -> Unit) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxWidth().panel(RoundedCornerShape(18.dp), c.surface2).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            listing.parent?.let { IconBubble(signalIcon(SignalIcons.Back), "Up one folder", size = 34.dp) { onBrowse(it) } }
            Spacer(Modifier.width(8.dp))
            Text(listing.path, color = c.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (listing.isGit) Pill("git", c.success)
        }
        SoftButton(if (picked == listing.path) "Using this folder" else "Use this folder", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Check),
            tint = if (picked == listing.path) c.success else c.accent) { onPick(listing.path) }
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            listing.dirs.forEach { d ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(d.name) { onBrowse(d.path) }.padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(signalIcon(SignalIcons.Folder), null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(d.name, color = c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (d.isGit) Pill("git", c.success)
                }
            }
            if (listing.dirs.isEmpty()) Text("No folders here.", color = c.tertiary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp))
        }
    }
}

/** A model to start with that something actually serves: never a Hypurr gateway model without the gateway. */
fun safeDefaultModel(b: com.ragul84.hypurr.model.Backend): String? =
    (b.defaultModel ?: b.freeModels.firstOrNull()?.id)?.takeIf { !it.startsWith("hypurr/") || b.gatewayReady == true }
