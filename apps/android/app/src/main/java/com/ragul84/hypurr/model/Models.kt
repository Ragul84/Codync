package com.ragul84.hypurr.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lenient like the iOS decoder: small host additions never break the app. */
val HypurrJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/** A paired computer (kit Computer.swift). */
@Serializable
data class Computer(
    val id: String,
    val name: String,
    /** Pinned host signing key: the QR's `sk`. */
    val signKey: String,
    /** Pinned mailbox key from the QR's `bk` or the E2E `hello`. */
    val boxKey: String? = null,
    /** Direct candidates (`http://ip:port`), tried before the relay. */
    val urls: List<String> = emptyList(),
    /** The cloud relay base URL; null = the host has the cloud off. */
    val cloud: String? = null,
    val device: String? = null,
)

@Serializable
data class Bot(
    val id: String,
    val kind: String = "bot",
    val members: List<String> = emptyList(),
    val name: String = "",
    val description: String = "",
    val avatarColor: String = "blue",
    val avatarShape: String = "blob",
    val backend: String = "",
    val cwd: String = "",
    val managedWorkspace: Boolean = false,
    val permission: String = "ask",
    val model: String? = null,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val notify: Boolean? = null,
    val createdAt: Long = 0,
    val rev: Long = 0,
    /** idle | working | needsInput | error */
    val status: String = "idle",
    val activity: String = "",
    val startedAt: Long? = null,
    val unread: Int = 0,
    val lastMessage: String? = null,
    val lastAt: Long = 0,
    val deleted: Boolean? = null,
    /** Set when this bot runs a beginner task (host `tasks`). */
    val task: TaskInfo? = null,
    /** Where the running turn talks: a chat (bot or group id) and maybe a thread root. */
    val workingChat: String? = null,
    val workingThread: String? = null,
) {
    val isGroup: Boolean get() = kind == "group"
    val isWorking: Boolean get() = status == "working" || status == "needsInput"
    val needsInput: Boolean get() = status == "needsInput"
    val failed: Boolean get() = status == "error"
    val folderName: String get() = if (managedWorkspace) "Personal workspace" else cwd.substringAfterLast('/')
}

@Serializable
data class PermissionOption(val optionId: String, val name: String, val kind: String = "")

@Serializable
data class EntryData(
    val text: String? = null,
    val final: Boolean? = null,
    /** user: queued | sent | cancelled | failed · permission: pending | answered | cancelled | expired */
    val status: String? = null,
    val clientNonce: String? = null,
    val title: String? = null,
    val toolKind: String? = null,
    val options: List<PermissionOption>? = null,
    val selected: String? = null,
    val command: String? = null,
    val detail: String? = null,
    /** notice: info | error | divider */
    val style: String? = null,
    val author: String? = null,
    val reactions: List<String>? = null,
    /** permission: low | medium | high, from the host's risk rules. */
    val risk: String? = null,
    /** permission: one plain sentence ("The agent wants to delete build and everything inside."). */
    val explain: String? = null,
    val riskReasons: List<String>? = null,
    /** permission: the safety net refused it without asking; why, in plain words. */
    val blocked: String? = null,
    /** permission: the task checkpoint saved just before it (Undo goes back here). */
    val checkpoint: String? = null,
    /** user: files sent with the message. */
    val attachments: List<Attachment>? = null,
    /** notice: a finished task's "What changed" card (learning mode, PR, cost). */
    val learning: Learning? = null,
    /** permission: the team's rules say an admin approves this one. */
    val needsAdmin: Boolean? = null,
    /** A main-chat message with replies in its thread (host-maintained summary). */
    val thread: ThreadSummary? = null,
)

/** `authors`: bot ids and `"user"`, first reply first; `unread`: replies newer than the thread was last read. */
@Serializable
data class ThreadSummary(val count: Int = 0, val lastAt: Long = 0, val authors: List<String> = emptyList(), val unread: Int = 0)

/** A folder on the computer (`listDirs`), for a new bot's working folder. */
@Serializable
data class DirEntry(val name: String, val path: String, val isGit: Boolean = false, val isProject: Boolean = false, val isRoot: Boolean = false, val kind: String? = null)

@Serializable
data class DirListing(val path: String = "", val parent: String? = null, val isGit: Boolean = false, val dirs: List<DirEntry> = emptyList(), val drives: Boolean = false, val isProject: Boolean = false, val isRoot: Boolean = false)

/** Quick reactions under a message (the iPhone's long-press row). */
val QuickReactions = listOf("👍", "❤️", "😂", "🎉", "👀", "✅")

/** Avatar palette ids, in the order the iPhone's picker shows them. */
val AvatarColors = listOf("teal", "cyan", "asphalt", "green", "gray", "ink", "black", "attention", "red", "brown", "blue")

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp")

val Attachment.isImage: Boolean get() = name.substringAfterLast('.', "").lowercase() in imageExtensions

/** 1.2 MB · 340 KB · 12 B */
fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

@Serializable
data class Attachment(val id: String, val name: String = "", val size: Long = 0)

@Serializable
/** One changed file; [patch] is its unified diff (host-capped), empty for binary or older hosts. */
data class FileChange(val path: String, val added: Long = 0, val removed: Long = 0, val patch: String = "")

@Serializable
data class PullRequest(val number: Long = 0, val url: String = "", val repo: String? = null)

/** Per-task tokens and cost (host `tasks::cost`). `estimated`: from list prices, not the agent. */
@Serializable
data class TaskUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val cost: Double = 0.0,
    val currency: String = "USD",
    val estimated: Boolean = true,
    val turns: Long = 0,
) {
    val label: String get() = costLabel(cost, estimated)
}

fun costLabel(cost: Double, estimated: Boolean): String {
    val s = if (cost > 0 && cost < 0.01) "less than $0.01" else "$" + String.format(java.util.Locale.US, "%.2f", cost)
    return if (estimated) "≈ $s" else s
}

@Serializable
data class Learning(
    val taskId: String = "",
    val title: String = "",
    val summary: String = "",
    val files: List<FileChange> = emptyList(),
    val added: Long = 0,
    val removed: Long = 0,
    val branch: String? = null,
    val base: String? = null,
    val pr: PullRequest? = null,
    val cost: TaskUsage? = null,
    /** slack | teams | jira */
    val posted: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

/** A GitHub issue or Jira ticket a task can start from. */
@Serializable
data class Issue(
    /** github | jira */
    val source: String,
    val key: String,
    val title: String = "",
    val url: String = "",
    val body: String = "",
    val project: String? = null,
)

@Serializable
data class IssueList(val issues: List<Issue> = emptyList(), val errors: List<IssueError> = emptyList())

@Serializable
data class IssueError(val source: String = "", val message: String = "")

@Serializable
data class GitHubStatus(val configured: Boolean = false, val tokenHint: String = "", val usesCli: Boolean = false, val apiUrl: String = "")

@Serializable
data class JiraStatus(
    val configured: Boolean = false,
    val baseUrl: String = "",
    val email: String = "",
    val tokenHint: String = "",
    val jql: String = "",
)

@Serializable
data class WebhookStatus(val configured: Boolean = false, val urlHint: String = "")

/** Work tools as the host shows them: never a token, only whether it's set and its last characters. */
@Serializable
data class Integrations(
    val github: GitHubStatus = GitHubStatus(),
    val jira: JiraStatus = JiraStatus(),
    val slack: WebhookStatus = WebhookStatus(),
    val teams: WebhookStatus = WebhookStatus(),
    val learning: Boolean = true,
    val autoPr: Boolean = true,
    val notify: Boolean = true,
) {
    val anyIssues: Boolean get() = github.configured || jira.configured
    val anyChat: Boolean get() = slack.configured || teams.configured
}

@Serializable
data class CostRow(val taskId: String, val botId: String = "", val title: String = "", val createdAt: Long = 0, val usage: TaskUsage = TaskUsage())

@Serializable
data class CostTotal(val cost: Double = 0.0, val tokens: Long = 0, val tasks: Int = 0, val estimated: Boolean = false)

@Serializable
data class TaskCosts(
    val tasks: List<CostRow> = emptyList(),
    val total: CostTotal = CostTotal(),
    val week: Double = 0.0,
    val today: Double = 0.0,
    val currency: String = "USD",
    /** Hypurr gateway free tokens remaining today (formatted). */
    val gatewayFreeRemaining: String? = null,
    /** Credits balance label, e.g. "$5.00". */
    val gatewayCreditsLabel: String? = null,
    /** Web checkout URL (Razorpay later); null: buy in the app via Google Play. */
    val buyCreditsUrl: String? = null,
    /** RevenueCat app user id (the computer's gateway subject); null until the gateway is live. */
    val gatewayAppUserId: String? = null,
    /** "Start free with your own account": Hypurr Free runs on the user's own account. */
    val ownFree: Boolean = false,
    /** e.g. "Resets at midnight IST". */
    val freeResetLabel: String? = null,
)

@Serializable
data class Entry(
    val id: String,
    val seq: Long = 0,
    val botId: String,
    val threadId: String? = null,
    val rev: Long = 0,
    /** user | agent | thought | tool | plan | permission | notice */
    val kind: String,
    val turn: Long = 0,
    val data: EntryData = EntryData(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** Chat-visible: user messages, final replies, approval cards, notices (CLAUDE.md "Chat shows only"). */
    val isChat: Boolean
        get() = when (kind) {
            "user", "permission", "notice" -> true
            "agent" -> data.final == true
            else -> false
        }
}

@Serializable
data class Backend(
    val id: String,
    val name: String = "",
    val available: Boolean = true,
    val installed: Boolean = true,
    val description: String = "",
    val builtin: Boolean = false,
    val free: Boolean = false,
    val subtitle: String = "",
    val defaultModel: String? = null,
    val freeModels: List<AgentModel> = emptyList(),
    val needsInstall: Boolean = false,
    val version: String? = null,
    val consent: String? = null,
    /** Host's own setup line for this agent (how to install it there). */
    val installHint: String? = null,
    /** Whether the agent looks signed in on the computer; null when the host can't tell. */
    val signedIn: Boolean? = null,
    /** Built-in only: whether the Hypurr gateway (paid from credits) is configured on the computer. */
    val gatewayReady: Boolean? = null,
    /** Built-in only: Hypurr Free runs on the user's own connected account. */
    val ownFree: Boolean = false,
)

/** A model an agent can run (Hypurr free gateway models, or whatever `agentModels` returns). */
@Serializable
data class AgentModel(val id: String, val name: String = "", val description: String = "", val free: Boolean = false)

@Serializable
data class Hello(
    val hostId: String = "",
    val name: String = "",
    val version: String = "",
    val os: String = "",
    val device: String? = null,
    val rev: Long = 0,
    val urls: List<String>? = null,
    val computerId: String? = null,
    val signKey: String? = null,
    val boxKey: String? = null,
    val cloud: String? = null,
    /** Who this phone is on the computer (team admin). */
    val you: Actor? = null,
    /** Agents the computer knows; `available` ones can run a new bot. */
    val backends: List<Backend> = emptyList(),
    val builtinAgent: Backend? = null,
    val home: String? = null,
    val screen: ScreenState? = null,
)

/** The computer's remote screen (`screenStatus`, `hello.screen`). */
@Serializable
data class ScreenState(
    val enabled: Boolean = false,
    /** The screen helper is running. */
    val connected: Boolean = false,
    val platform: String = "",
    /** Screen recording is permitted. */
    val capture: Boolean = false,
    /** Input injection is permitted. */
    val input: Boolean = false,
    val displays: List<ScreenDisplay> = emptyList(),
    val userControl: Boolean = false,
    val viewers: Int = 0,
) {
    val ready: Boolean get() = enabled && connected && capture
}

/** A screen session the host reserved, with its short-lived ICE servers (never stored). */
@Serializable
data class ScreenConnection(val session: String, val iceServers: List<IceServer> = emptyList(), val expiresAt: Long = 0)

@Serializable
data class IceServer(val urls: List<String> = emptyList(), val username: String? = null, val credential: String? = null)

@Serializable
data class ScreenDisplay(val id: Long = 0, val name: String = "", val width: Double = 0.0, val height: Double = 0.0, val main: Boolean = false)

@Serializable
data class SyncResponse(
    val hostId: String = "",
    val rev: Long = 0,
    val bots: List<Bot> = emptyList(),
    val entries: List<Entry> = emptyList(),
)

/** A saved point on a task's branch. */
@Serializable
data class Checkpoint(val id: String, val label: String = "", val at: Long = 0, val files: List<String> = emptyList())

/** A beginner task (host `tasks::Task`), carried on its bot. */
@Serializable
data class TaskInfo(
    val id: String,
    /** Set when the task came from Ticket to PR: the approved plan and, later, the pull request. */
    val ticket: TicketExtra? = null,
    val botId: String = "",
    val goal: String = "",
    val title: String = "",
    val template: String? = null,
    val project: String = "",
    val projectName: String = "",
    val backend: String = "",
    /** worktree | none */
    val safety: String = "worktree",
    val branch: String? = null,
    val base: String? = null,
    val worktree: String? = null,
    /** active | finishing | finished */
    val status: String = "active",
    val checkpoints: List<Checkpoint> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val issue: Issue? = null,
    val usage: TaskUsage? = null,
    /** The learning-mode summary, once finished. */
    val summary: String? = null,
    val pr: PullRequest? = null,
    /** Learning mode: the agent explains each step; the phone offers Teach me cards and a quiz. */
    val learning: Boolean = false,
) {
    val hasSafetyNet: Boolean get() = safety == "worktree" && branch != null && worktree != null
    val isActive: Boolean get() = status == "active"
    val isFinishing: Boolean get() = status == "finishing"
}

@Serializable
data class TaskTemplate(
    val id: String = "",
    val title: String,
    val summary: String = "",
    /** test | bug | review | deps | explain | custom */
    val icon: String = "custom",
    val prompt: String = "",
    val inputLabel: String? = null,
    val goalHint: String = "",
    val readOnly: Boolean = false,
    val builtin: Boolean = false,
)

@Serializable
data class Project(val name: String, val path: String, val keywords: List<String> = emptyList(), val saved: Boolean = false)

@Serializable
data class Agent(val id: String, val name: String)

@Serializable
data class SafetySettings(
    val protectedBranches: List<String> = listOf("main", "master", "production", "prod", "release/*", "develop"),
    val blockProtected: Boolean = true,
    val alwaysAskHigh: Boolean = true,
    val requireGit: Boolean = false,
)

@Serializable
data class TaskSetup(
    val templates: List<TaskTemplate> = emptyList(),
    val projects: List<Project> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val safety: SafetySettings = SafetySettings(),
    val integrations: Integrations = Integrations(),
)

@Serializable
data class TaskRoute(val project: Project? = null, val agent: Agent? = null, val reason: String = "")

// MARK: team admin (host `admin`)

/** A person (device) and their role: admin | member | viewer. */
@Serializable
data class Actor(val key: String = "", val name: String = "", val role: String = "admin") {
    val isAdmin get() = role == "admin"
    val canAct get() = role != "viewer"
}

@Serializable
data class TeamPerson(
    val key: String = "",
    val name: String = "",
    val platform: String = "",
    val role: String = "member",
    /** This computer itself: always an admin. */
    val fixed: Boolean = false,
    /** Has its own role (otherwise the default). */
    val ownRole: Boolean = false,
    val lastSeenAt: Long? = null,
    val you: Boolean = false,
)

@Serializable
data class TeamInfo(val you: Actor = Actor(), val defaultRole: String = "admin", val people: List<TeamPerson> = emptyList())

@Serializable
data class Policies(
    val allowedAgents: List<String> = emptyList(),
    val dailyLimit: Double = 0.0,
    val taskLimit: Double = 0.0,
    /** low | medium | high | never: at or above, even auto-approve bots ask. */
    val askFrom: String = "high",
    /** low | medium | high | off: at or above, only an admin approves. */
    val adminApprovesFrom: String = "off",
    /** ₹ per person per day / month; 0 means no limit. */
    val personDailyInr: Double = 0.0,
    val personMonthlyInr: Double = 0.0,
    /** Overrides by device key. */
    val personLimits: Map<String, PersonLimit> = emptyMap(),
    val teamMonthlyInr: Double = 0.0,
    /** Enforced on the computer: no Hypurr cloud, LAN pairing, approved model endpoints only. */
    val localOnly: Boolean = false,
    val modelEndpoints: List<String> = emptyList(),
    /** Folder rules: tasks and bots only inside these. Empty: any folder. */
    val allowedFolders: List<String> = emptyList(),
    val usdToInr: Double = 84.0,
)

@Serializable
data class PersonLimit(val dailyInr: Double = 0.0, val monthlyInr: Double = 0.0)

/** `spendByPerson`: this month's and today's spend in rupees, per person. */
@Serializable
data class PersonSpend(
    val key: String = "",
    val name: String = "",
    val todayInr: Double = 0.0,
    val monthInr: Double = 0.0,
    val dailyInr: Double = 0.0,
    val monthlyInr: Double = 0.0,
    val custom: Boolean = false,
)

@Serializable
data class SpendByPerson(
    val people: List<PersonSpend> = emptyList(),
    val teamMonthInr: Double = 0.0,
    val teamMonthlyInr: Double = 0.0,
    val usdToInr: Double = 84.0,
)

/** `auditExport`: a file to share. */
@Serializable
data class AuditExport(
    val text: String = "",
    val mime: String = "text/csv",
    val fileName: String = "hypurr-audit.csv",
    val rows: Int = 0,
    val total: Int = 0,
    val intact: Boolean = true,
    val brokenAt: Long? = null,
)

@Serializable
data class ItKitPage(val id: String = "", val title: String = "", val markdown: String = "")

/** `itKit`: the IT approval kit with this computer's live values. */
@Serializable
data class ItKit(
    val facts: List<String> = emptyList(),
    val pages: List<ItKitPage> = emptyList(),
    val text: String = "",
    val port: Int = 19222,
    val cloud: String? = null,
)

@Serializable
data class Plan(
    val id: String = "",
    val name: String = "",
    /** Rupees; null: not decided yet. */
    val priceInr: Double? = null,
    val per: String = "",
    val tasksPerDay: Int? = null,
    val summary: String = "",
    val features: List<String> = emptyList(),
)

@Serializable
data class Payments(val provider: String = "", val ready: Boolean = false)

/** `pricing`: plans from pricing/plans.json, in rupees. */
@Serializable
data class Pricing(
    val currency: String = "INR",
    val symbol: String = "₹",
    val gstNote: String = "",
    val plans: List<Plan> = emptyList(),
    val payments: Payments = Payments(),
    val creditPacks: CreditPacks = CreditPacks(),
)

/** Hypurr credits sold as Google Play consumables via RevenueCat (pricing/plans.json). */
@Serializable
data class CreditPack(
    val id: String = "",
    val productId: String = "",
    val priceInr: Double = 0.0,
    val creditsCents: Int = 0,
    /** Rough messages this pack buys once the free ones are used (plans.json). */
    val approxMessages: Int? = null,
    val label: String = "",
)

@Serializable
data class CreditPacks(val packs: List<CreditPack> = emptyList())

/** ₹1,23,456 (Indian grouping), whole rupees. */
fun rupees(amount: Double): String {
    val n = Math.round(amount).coerceAtLeast(0).toString()
    if (n.length <= 3) return "₹$n"
    val head = n.dropLast(3)
    val grouped = head.reversed().chunked(2).joinToString(",").reversed()
    return "₹$grouped,${n.takeLast(3)}"
}

fun planPrice(p: Plan): String = when {
    p.priceInr == null -> "Price to be announced"
    p.priceInr <= 0.0 -> "Free"
    else -> "${rupees(p.priceInr)} / ${p.per}"
}

@Serializable
data class PolicyInfo(
    val policies: Policies = Policies(),
    val safety: SafetySettings = SafetySettings(),
    val spentToday: Double = 0.0,
    val agents: List<Agent> = emptyList(),
)

@Serializable
data class AuditEntry(
    val seq: Long = 0,
    val at: Long = 0,
    val actor: String = "",
    val actorName: String = "",
    val role: String = "",
    val action: String = "",
    val target: String = "",
    val detail: kotlinx.serialization.json.JsonElement? = null,
    val hash: String = "",
) {
    /** One plain line: "started a task", "was blocked: setPolicies". */
    val summary: String get() = when (action) {
        "task.start" -> "started a task"
        "task.finish" -> "finished a task"
        "task.rollback" -> "went back to a checkpoint"
        "task.checkpoint" -> "saved a checkpoint"
        "approval.answer" -> "answered an approval"
        "approval.auto" -> "approved automatically"
        "approval.blocked" -> "blocked a request"
        "policy.limit" -> "stopped a task at its spending limit"
        "policy.change" -> "changed the team rules"
        "role.change" -> "changed a role"
        "role.default" -> "changed the role for new devices"
        "integrations.change" -> "changed work tools"
        "bot.create" -> "created a bot"
        "bot.update" -> "changed a bot"
        "bot.delete" -> "deleted a bot"
        "bot.stop" -> "stopped an agent"
        "blocked" -> "was blocked"
        else -> action.replace('.', ' ')
    }

    /** What it was about, in plain words (a refused method becomes what the person tried to do). */
    val targetLabel: String get() = if (action != "blocked") target else when (target) {
        "respondPermission" -> "Answering an approval"
        "setPolicies", "setSafetySettings" -> "Changing the team rules"
        "setRole", "setTeam" -> "Changing roles"
        "send" -> "Sending a message"
        "startTask" -> "Starting a task"
        "createBot" -> "Creating a bot"
        "updateBot" -> "Changing a bot"
        "auditLog" -> "Opening the audit log"
        "activity" -> "Opening the activity view"
        "setIntegrations" -> "Changing work tools"
        else -> target
    }

    val reason: String? get() = (detail as? kotlinx.serialization.json.JsonObject)?.get("reason")
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    val risk: String? get() = (detail as? kotlinx.serialization.json.JsonObject)?.get("risk")
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
}

@Serializable
data class AuditLog(
    val entries: List<AuditEntry> = emptyList(),
    val count: Long = 0,
    /** The hash chain checks out: nothing was edited or removed. */
    val intact: Boolean = true,
    val brokenAt: Long? = null,
)

@Serializable
data class ActivityPerson(
    val key: String = "",
    val name: String = "",
    val role: String = "member",
    val lastSeenAt: Long? = null,
    val tasks: Int = 0,
    val spend: Double = 0.0,
    val approvals: Int = 0,
    val blocked: Int = 0,
    val removed: Boolean = false,
)

@Serializable
data class StartedBy(val key: String = "", val name: String = "")

@Serializable
data class ActivityTask(
    val taskId: String = "",
    val botId: String = "",
    val title: String = "",
    val status: String = "active",
    val projectName: String = "",
    val backend: String = "",
    val startedBy: StartedBy = StartedBy(),
    val usage: TaskUsage? = null,
    val pr: PullRequest? = null,
    val createdAt: Long = 0,
)

@Serializable
data class ActivityTotals(
    val spend: Double = 0.0,
    val todaySpend: Double = 0.0,
    val todayTasks: Int = 0,
    val approvals: Int = 0,
    val blocked: Int = 0,
)

@Serializable
data class Activity(
    val days: Int = 7,
    val people: List<ActivityPerson> = emptyList(),
    val tasks: List<ActivityTask> = emptyList(),
    val events: List<AuditEntry> = emptyList(),
    val totals: ActivityTotals = ActivityTotals(),
)

/** "$4.20" for a limit; "No limit" for 0. */
fun limitLabel(x: Double): String = if (x <= 0.0) "No limit" else "$" + String.format(java.util.Locale.US, "%.2f", x)

/** `projectGuide`: a Project guide or Handover doc, every file path checked against the folder. */
@Serializable
data class GuideDoc(
    val markdown: String = "",
    val title: String = "",
    val kind: String = "guide",
    /** "agent" or "facts" (built from the folder and git history only). */
    val by: String = "facts",
    val cited: List<String> = emptyList(),
    /** Paths the agent named that aren't in the folder; removed from the text. */
    val dropped: List<String> = emptyList(),
)

@Serializable
data class AskSource(val path: String = "", val line: Int? = null, val text: String = "")

/** `projectAsk`: an answer that cites files. */
@Serializable
data class AskAnswer(
    val answer: String = "",
    val sources: List<AskSource> = emptyList(),
    val by: String = "search",
    val cited: List<String> = emptyList(),
    val dropped: List<String> = emptyList(),
)

/** A "Teach me this" card: an idea, in simple words, with a real line from the user's code. */
@Serializable
data class LearnCard(
    val id: String = "",
    val concept: String = "",
    val explain: String = "",
    val example: String = "",
    val file: String = "",
    val line: Int = 0,
)

@Serializable
data class LearnCards(val cards: List<LearnCard> = emptyList())

@Serializable
data class QuizQuestion(val q: String = "", val options: List<String> = emptyList(), val answer: Int = 0, val why: String = "")

@Serializable
data class Quiz(val questions: List<QuizQuestion> = emptyList(), val by: String = "task")
// Fix from a log (host `triage`, `triageRedact`, `triageSources`, `triageSource`).

@Serializable
data class Redaction(val kind: String = "", val count: Int = 0, val hint: String = "")

@Serializable
data class CodePlace(val path: String = "", val line: Int = 0, val snippet: String = "", val note: String = "")

@Serializable
data class Triage(
    val broke: String = "",
    val cause: String = "",
    val places: List<CodePlace> = emptyList(),
    /** low | medium | high */
    val confidence: String = "low",
    val confidenceWhy: String = "",
    val fix: List<String> = emptyList(),
    val more: String = "",
)

@Serializable
data class TriageResult(
    val triage: Triage = Triage(),
    /** `agent`, or `log` when only the first error line could be picked out. */
    val by: String = "log",
    val redactions: List<Redaction> = emptyList(),
    val redactedText: String = "",
    val ticket: String = "",
    val fixGoal: String = "",
)

@Serializable
data class RedactPreview(val text: String = "", val redactions: List<Redaction> = emptyList())

@Serializable
data class TriageSource(val kind: String = "", val id: String = "", val title: String = "", val detail: String = "", val at: Long = 0)

@Serializable
data class TriageSources(val sources: List<TriageSource> = emptyList())

@Serializable
data class TriageSourceText(val text: String = "")
// Daily standup (host `standup`, `teamStandup`, `standupReminder`).

@Serializable
data class Standup(
    val yesterday: List<String> = emptyList(),
    val today: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
)

@Serializable
data class StandupCommit(val project: String = "", val sha: String = "", val subject: String = "", val at: Long = 0, val today: Boolean = false)

@Serializable
data class StandupTask(
    val title: String = "",
    val project: String = "",
    val status: String = "",
    val today: Boolean = false,
    val waiting: Boolean = false,
    val pr: String? = null,
    val ticket: String? = null,
)

@Serializable
data class StandupFacts(
    val projects: List<String> = emptyList(),
    val commits: List<StandupCommit> = emptyList(),
    val tasks: List<StandupTask> = emptyList(),
)

@Serializable
data class StandupResult(
    val standup: Standup = Standup(),
    val text: String = "",
    /** `agent` when an agent wrote it, `facts` for the plain draft. */
    val by: String = "facts",
    val facts: StandupFacts = StandupFacts(),
    val date: String = "",
)

@Serializable
data class StandupReminder(val enabled: Boolean = false, val time: String = "09:30", val timeZone: String = "Asia/Kolkata")

@Serializable
data class StandupReminderReply(val reminder: StandupReminder? = null)

@Serializable
data class TeamStandupPerson(
    val key: String = "",
    val name: String? = null,
    val yesterday: List<String> = emptyList(),
    val today: List<String> = emptyList(),
    val finished: Int = 0,
    val open: Int = 0,
)

@Serializable
data class TeamStandup(val available: Boolean = false, val reason: String? = null, val people: List<TeamStandupPerson> = emptyList())
// MARK: Ticket to PR (host `ticket.rs`)

@Serializable
data class PlanFile(val path: String = "", val why: String = "", val exists: Boolean = true)

@Serializable
data class TicketPlan(
    val understood: String = "",
    val files: List<PlanFile> = emptyList(),
    val checks: List<String> = emptyList(),
    val tests: List<String> = emptyList(),
    val testCommand: String = "",
)

@Serializable
data class TicketResolve(val ticket: Issue? = null, val needsSetup: String? = null, val key: String = "")

@Serializable
data class TicketPlanResult(val plan: TicketPlan = TicketPlan(), val by: String = "ticket")

@Serializable
data class TicketPublishInfo(
    /** github | gitlab | azure | other | none */
    val provider: String = "none",
    /** How the PR would be opened; empty: copy the description instead. */
    val via: String = "",
    val branch: String = "",
    val base: String = "",
    val protected: Boolean = false,
)

@Serializable
data class TicketPr(
    val pushed: Boolean = false,
    val branch: String = "",
    val provider: String = "",
    val url: String? = null,
    val number: Long? = null,
    /** No PR tool signed in: the user copies the description. */
    val copy: Boolean = false,
    val error: String? = null,
)

@Serializable
data class TicketExtra(val plan: TicketPlan? = null, val pr: TicketPr? = null)

@Serializable
data class TicketReview(
    val ticket: Issue = Issue("text", ""),
    val files: List<FileChange> = emptyList(),
    val summary: String = "",
    val by: String = "files",
    val prTitle: String = "",
    val prBody: String = "",
    val comment: String = "",
    val publish: TicketPublishInfo = TicketPublishInfo(),
    val pr: TicketPr? = null,
    val testCommand: String = "",
)

@Serializable
data class TicketCli(val gh: Boolean = false, val glab: Boolean = false, val az: Boolean = false)

@Serializable
data class TicketSetup(val integrations: Integrations = Integrations(), val cli: TicketCli = TicketCli())

// MARK: QA test writer (host `qa.rs`)

@Serializable
data class QaCase(
    val id: String = "",
    val title: String = "",
    val steps: List<String> = emptyList(),
    val expected: String = "",
    /** high | medium | low */
    val priority: String = "medium",
    /** positive | negative | edge */
    val kind: String = "positive",
    val why: String = "",
    val automatable: Boolean = false,
)

@Serializable
data class QaFramework(val id: String = "", val name: String = "", val command: String = "", val needsDevice: Boolean = false)

@Serializable
data class QaCasesResult(
    val cases: List<QaCase> = emptyList(),
    val by: String = "template",
    val frameworks: List<QaFramework> = emptyList(),
    val ticket: Issue? = null,
    val needsSetup: String? = null,
    val explain: Boolean = false,
)

@Serializable
data class QaSaved(val cases: List<QaCase> = emptyList(), val input: String = "", val source: String = "text", val savedAt: Long = 0)

@Serializable
data class QaRunResult(
    val ok: Boolean = false,
    val passed: Long = 0,
    val failed: Long = 0,
    val timedOut: Boolean = false,
    val command: String = "",
    val seconds: Long = 0,
    val output: String = "",
    val automated: List<String> = emptyList(),
    val manual: List<String> = emptyList(),
    val needsDevice: Boolean = false,
)

// ---------- Show me it works (Stage 8) ----------

@Serializable
data class ProofPlan(val kind: String = "", val what: String = "", val command: String? = null)

@Serializable
data class ProofShot(val label: String = "", val before: String? = null, val after: String? = null)

@Serializable
data class ProofTests(
    val ok: Boolean = false,
    val passed: Int = 0,
    val failed: Int = 0,
    val command: String = "",
    val seconds: Int = 0,
    val output: String = "",
)

@Serializable
data class ProofChange(val path: String = "", val added: Int = 0, val removed: Int = 0)

@Serializable
data class ProofVerdict(val ok: Boolean = true, val text: String = "")

/** Visual proof of a task's result: before/after shots, a clip, test counts, the diff in words. */
@Serializable
data class Proof(
    /** needsApproval | running | ready | failed */
    val status: String = "",
    val plan: ProofPlan? = null,
    val kind: String = "",
    val shots: List<ProofShot> = emptyList(),
    val clip: String? = null,
    val tests: ProofTests? = null,
    val summary: String = "",
    val files: List<ProofChange> = emptyList(),
    val note: String = "",
    val log: String = "",
    val verdict: ProofVerdict? = null,
    val seconds: Int = 0,
)

@Serializable
data class ProofReply(val proof: Proof? = null)

@Serializable
data class ProofFileData(val name: String = "", val data: String = "")

// ---------- Trace a click & Page map ----------

/** A place in the repo (`client/src/api.js:9`) and how Hypurr knows: source map, browser or code search. */
@Serializable
data class TraceCodeRef(val name: String? = null, val file: String? = null, val line: Int? = null, val how: String = "") {
    val place: String? get() = file?.let { f -> line?.let { "$f:$it" } ?: f }
}

/** The backend route that answers a call (Express, Next.js, FastAPI, Flask, Spring, Django). */
@Serializable
data class TraceRoute(
    val framework: String = "",
    val method: String = "",
    val path: String = "",
    val file: String = "",
    val line: Int = 0,
    val handler: String? = null,
)

@Serializable
data class TraceCall(
    val method: String = "",
    val url: String = "",
    val status: Int? = null,
    val statusText: String = "",
    val ms: Long? = null,
    val requestBody: String = "",
    val responseBody: String = "",
    val requestHeaders: List<List<String>> = emptyList(),
    val failed: String? = null,
    val blocked: Boolean = false,
    val sentFrom: TraceCodeRef? = null,
    val handledBy: TraceRoute? = null,
)

@Serializable
data class TraceClick(
    val label: String = "",
    val tag: String = "",
    val page: String = "",
    val handlerName: String? = null,
    val handler: TraceCodeRef? = null,
    val calls: List<TraceCall> = emptyList(),
    val appeared: List<String> = emptyList(),
)

@Serializable
data class Trace(
    val id: String = "",
    val url: String = "",
    val at: Long = 0,
    /** recording | done | failed */
    val status: String = "",
    val error: String? = null,
    val browser: String = "",
    val clicks: List<TraceClick> = emptyList(),
    val blockedHosts: List<String> = emptyList(),
    val chain: List<List<String>> = emptyList(),
)

/** `traceStart` / `traceAllow`: either the trace, or what needs the user's OK first. */
@Serializable
data class TraceReply(
    val status: String = "",
    val trace: Trace? = null,
    val host: String? = null,
    /** local | staging | other */
    val hostKind: String? = null,
    val why: String? = null,
)

@Serializable
data class TraceGetReply(val trace: Trace? = null)

@Serializable
data class TraceExport(val code: String = "", val path: String = "")

@Serializable
data class MapElement(val kind: String = "", val label: String = "", val handler: String? = null, val file: String = "", val line: Int = 0)

@Serializable
data class MapCall(
    val method: String = "",
    val path: String = "",
    val file: String = "",
    val line: Int = 0,
    val via: String? = null,
    val handledBy: TraceRoute? = null,
)

@Serializable
data class MapPage(
    val path: String = "",
    val file: String = "",
    val component: String? = null,
    val files: List<String> = emptyList(),
    val elements: List<MapElement> = emptyList(),
    val calls: List<MapCall> = emptyList(),
)

@Serializable
data class PageMapData(
    val at: Long = 0,
    val router: String = "",
    val pages: List<MapPage> = emptyList(),
    val routes: List<TraceRoute> = emptyList(),
    val unusedRoutes: List<TraceRoute> = emptyList(),
)

@Serializable
data class RestructureMove(val from: String = "", val to: String = "", val why: String = "")

@Serializable
data class RestructurePlan(
    val root: String = "",
    val moves: List<RestructureMove> = emptyList(),
    val notes: List<String> = emptyList(),
    val testCommand: String = "",
)

/** The applied restructure: running | ready (tests passed) | testsFailed | noTests | failed. */
@Serializable
data class RestructureApplied(
    val status: String = "",
    val branch: String = "",
    val base: String = "",
    val moved: Int = 0,
    val rewritten: Int = 0,
    val testCommand: String = "",
    val testOutput: String = "",
    val prTitle: String = "",
    val prBody: String = "",
    val error: String? = null,
    val pushed: Boolean = false,
    val prUrl: String? = null,
)

@Serializable
data class MapReply(val map: PageMapData? = null, val plan: RestructurePlan? = null, val applied: RestructureApplied? = null)

@Serializable
data class OpenShellReply(val term: String = "", val cwd: String = "")

@Serializable
data class TermEventWire(val type: String = "", val data: String? = null, val code: Int? = null)
