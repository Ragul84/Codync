package com.ragul84.hypurr.data

import com.ragul84.hypurr.model.Activity
import com.ragul84.hypurr.model.Actor
import com.ragul84.hypurr.model.AuditLog
import com.ragul84.hypurr.model.Backend
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.TicketSetup
import com.ragul84.hypurr.model.QaCase
import com.ragul84.hypurr.model.QaCasesResult
import com.ragul84.hypurr.model.QaRunResult
import com.ragul84.hypurr.model.TicketResolve
import com.ragul84.hypurr.model.TicketPlanResult
import com.ragul84.hypurr.model.TicketPlan
import com.ragul84.hypurr.model.TicketReview
import com.ragul84.hypurr.model.TicketPr
import com.ragul84.hypurr.model.PolicyInfo
import com.ragul84.hypurr.model.TeamInfo
import com.ragul84.hypurr.model.Computer
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.Attachment
import com.ragul84.hypurr.model.DirListing
import com.ragul84.hypurr.model.Hello
import com.ragul84.hypurr.model.isImage
import com.ragul84.hypurr.model.EntryData
import com.ragul84.hypurr.model.HypurrJson
import com.ragul84.hypurr.model.Pairing
import com.ragul84.hypurr.model.Integrations
import com.ragul84.hypurr.model.Issue
import com.ragul84.hypurr.model.IssueList
import com.ragul84.hypurr.model.SafetySettings
import com.ragul84.hypurr.model.TaskCosts
import com.ragul84.hypurr.model.TaskRoute
import com.ragul84.hypurr.model.TaskSetup
import com.ragul84.hypurr.model.TaskTemplate
import kotlinx.serialization.encodeToString
import com.ragul84.hypurr.net.ChannelTransport
import com.ragul84.hypurr.net.Dialer
import com.ragul84.hypurr.net.HostClient
import com.ragul84.hypurr.net.HostConnector
import com.ragul84.hypurr.net.HostEvent
import com.ragul84.hypurr.net.HostException
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.OkHttpSocket
import com.ragul84.hypurr.net.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** A screenshot, photo or file picked on the phone, waiting to go with a task. */
class PickedFile(val name: String, val bytes: ByteArray, val mime: String = "application/octet-stream") {
    val isImage: Boolean get() = mime.startsWith("image/")
    val size: Long get() = bytes.size.toLong()
}

enum class ThemeMode { System, Light, Dark }

/** What the app keeps between launches (SharedPreferences on the phone, memory in tests). */
interface Persistence {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

class MemoryPersistence : Persistence {
    private val map = mutableMapOf<String, String>()
    override fun read(key: String) = map[key]
    override fun write(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/**
 * The app's model (kit HypurrUI BotStore): the paired computer, its channel, the roster and the
 * chats. Every mutation goes to the host; the event stream brings the result back (CLAUDE.md "Sync").
 */
class HypurrStore(
    private val scope: CoroutineScope,
    private val persistence: Persistence,
    val identity: DeviceIdentity,
    private val deviceName: String,
    private val dial: Dialer = OkHttpSocket.dial,
) {
    private val _computer = MutableStateFlow(persistence.read(COMPUTER)?.let {
        runCatching { HypurrJson.decodeFromString<Computer>(it) }.getOrNull()
    })
    val computer: StateFlow<Computer?> = _computer.asStateFlow()

    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _bots = MutableStateFlow<Map<String, Bot>>(emptyMap())
    val bots: StateFlow<Map<String, Bot>> = _bots.asStateFlow()

    private val _entries = MutableStateFlow<Map<String, List<Entry>>>(emptyMap())
    val entries: StateFlow<Map<String, List<Entry>>> = _entries.asStateFlow()

    /** Thread replies by root entry id, oldest first (the roots live in [entries]). */
    private val _threads = MutableStateFlow<Map<String, List<Entry>>>(emptyMap())
    val threads: StateFlow<Map<String, List<Entry>>> = _threads.asStateFlow()

    /** Sent and fetched files by upload id (image previews). Memory only: gone when the app quits. */
    private val _files = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val files: StateFlow<Map<String, ByteArray>> = _files.asStateFlow()
    private val fetching = mutableSetOf<String>()

    private val _hello = MutableStateFlow<Hello?>(null)
    /** The computer's `hello`: its agents (for New bot), home folder and screen state. */
    val hello: StateFlow<Hello?> = _hello.asStateFlow()

    private val _synced = MutableStateFlow(false)
    /** The first sync finished: the roster is real, not just empty. */
    val synced: StateFlow<Boolean> = _synced.asStateFlow()

    val themeMode = MutableStateFlow(persistence.read(THEME)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System)
    val dynamicColor = MutableStateFlow(persistence.read(DYNAMIC) == "1")
    val notifications = MutableStateFlow(persistence.read(NOTIFY) != "0")
    /** "Connect from anywhere": reach the computer through the encrypted relay when not on its Wi-Fi. */
    val anywhere = MutableStateFlow(persistence.read(ANYWHERE) != "0")

    private val _userProfile = MutableStateFlow(
        persistence.read(USER_PROFILE)?.let {
            runCatching { HypurrJson.decodeFromString(UserProfile.serializer(), it) }.getOrNull()
        } ?: UserProfile(),
    )
    /** Local first-run profile (role / experience / stack / goals). Never holds employee ID or salary. */
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    fun setUserProfile(profile: UserProfile) {
        _userProfile.value = profile
        if (persistence.read(LEARNING) == null) learningMode.value = profile.experience == "New" || profile.role == "Student"
        persistence.write(
            USER_PROFILE,
            if (profile == UserProfile()) null
            else HypurrJson.encodeToString(UserProfile.serializer(), profile),
        )
        scope.launch { syncUserProfileToHost() }
    }

    /** Default project folder for new tasks (picked after pairing or under You). */
    val projectFolder = MutableStateFlow(persistence.read(PROJECT_FOLDER)?.takeIf { it.isNotBlank() })

    /** Learning mode: on by default for New experience or Student (from the profile), until changed here. */
    val learningMode = MutableStateFlow(
        persistence.read(LEARNING)?.toBooleanStrictOrNull() ?: userProfileDefaultLearning(),
    )

    private fun userProfileDefaultLearning(): Boolean =
        runCatching { userProfile.value.let { it.experience == "New" || it.role == "Student" } }.getOrDefault(false)

    fun setLearningMode(on: Boolean) {
        learningMode.value = on
        persistence.write(LEARNING, on.toString())
    }

    suspend fun projectGuide(project: String, kind: String) = client?.projectGuide(project, kind) ?: throw HostException.unreachable()
    suspend fun projectAsk(project: String, q: String) = client?.projectAsk(project, q) ?: throw HostException.unreachable()
    suspend fun projectGuideSave(project: String, md: String): String {
        val bot = client?.projectGuideSave(project, md) ?: throw HostException.unreachable()
        upsert(bot)
        return bot.id
    }
    suspend fun learnCards(taskId: String) = client?.learnCards(taskId)
    suspend fun learnQuiz(taskId: String) = client?.learnQuiz(taskId)
    suspend fun proof(taskId: String) = client?.proof(taskId)?.proof
    suspend fun proofStart(taskId: String) = client?.proofStart(taskId)?.proof
    suspend fun proofAllow(taskId: String) = client?.proofAllow(taskId)?.proof
    suspend fun proofVerdict(taskId: String, ok: Boolean, text: String) = client?.proofVerdict(taskId, ok, text)

    /** One proof picture or clip, as bytes. */
    suspend fun proofFile(taskId: String, name: String): ByteArray? =
        client?.proofFile(taskId, name)?.data?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }

    /** The New task screen's last agent and model, so the next task starts with the same picks. */
    val lastTaskAgent = MutableStateFlow(persistence.read(LAST_TASK_AGENT)?.takeIf { it.isNotBlank() })
    val lastTaskModel = MutableStateFlow(persistence.read(LAST_TASK_MODEL)?.takeIf { it.isNotBlank() })

    fun rememberTaskPicks(agent: String?, model: String?, project: String?) {
        lastTaskAgent.value = agent?.takeIf { it.isNotBlank() }
        lastTaskModel.value = model?.takeIf { it.isNotBlank() }
        persistence.write(LAST_TASK_AGENT, lastTaskAgent.value)
        persistence.write(LAST_TASK_MODEL, lastTaskModel.value)
        if (!project.isNullOrBlank()) setProjectFolder(project)
    }

    fun setProjectFolder(path: String?) {
        projectFolder.value = path?.takeIf { it.isNotBlank() }
        persistence.write(PROJECT_FOLDER, projectFolder.value)
    }

    fun clearUserProfile() {
        setUserProfile(UserProfile(completed = true, skipped = true))
    }

    /** Push non-sensitive preferences to the host so agent prompts adapt. */
    suspend fun syncUserProfileToHost() {
        val c = client ?: return
        val p = _userProfile.value
        if (!p.hasAnswers && !p.completed) return
        runCatching {
            c.setUserProfile(buildJsonObject {
                put("completed", p.completed)
                put("skipped", p.skipped)
                p.role?.let { put("role", it) }
                p.experience?.let { put("experience", it) }
                putJsonArray("stacks") { p.stacks.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("goals") { p.goals.forEach { add(JsonPrimitive(it)) } }
            })
        }
    }

    var transport: ChannelTransport? = null
        private set
    val client: HostClient? get() = transport?.let(::HostClient)
    private var jobs = listOf<Job>()
    private var rev = 0L

    /** Runs after every handshake on a fresh channel (push registration). */
    var onConnected: (suspend (HostClient) -> Unit)? = null

    /** A bot needs you, finished or failed while the channel is live (local fallback for push). */
    var onLocalAlert: ((com.ragul84.hypurr.push.LocalAlerts.Kind, Bot) -> Unit)? = null

    fun setTheme(mode: ThemeMode) {
        themeMode.value = mode
        persistence.write(THEME, mode.name)
    }

    fun setDynamicColor(on: Boolean) {
        dynamicColor.value = on
        persistence.write(DYNAMIC, if (on) "1" else "0")
    }

    fun setAnywhere(on: Boolean) {
        anywhere.value = on
        persistence.write(ANYWHERE, if (on) "1" else "0")
        if (_computer.value != null) connect()
    }

    fun setNotifications(on: Boolean) {
        notifications.value = on
        persistence.write(NOTIFY, if (on) "1" else "0")
        scope.launch {
            val c = client ?: return@launch
            runCatching { if (on) onConnected?.invoke(c) else c.unregisterDevice() }
        }
    }

    // MARK: pairing and the channel

    suspend fun pair(link: String): Computer {
        val pairing = Pairing.parse(link)
        val computer = HostConnector.pair(pairing, identity, deviceName, scope, dial)
        save(computer)
        connect()
        return computer
    }

    private fun save(computer: Computer?) {
        _computer.value = computer
        persistence.write(COMPUTER, computer?.let { HypurrJson.encodeToString(Computer.serializer(), it) })
    }

    fun connect() {
        val computer = _computer.value ?: return
        disconnect()
        val t = HostConnector.connect(computer, identity, scope, dial) { anywhere.value }
        transport = t
        jobs = listOf(
            scope.launch { t.state.collect { _link.value = it } },
            scope.launch { t.computer.collect { if (it != _computer.value) save(it) } },
            scope.launch { follow(t) },
        )
    }

    fun disconnect() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        transport?.shutdown()
        transport = null
    }

    fun reconnect() = transport?.reconnect() ?: connect()

    /** Removes the computer from this phone: its access goes with it. */
    suspend fun forget() {
        runCatching { client?.unregisterDevice() }
        disconnect()
        save(null)
        _bots.value = emptyMap()
        _entries.value = emptyMap()
        _synced.value = false
        rev = 0
        _you.value = null
        _threads.value = emptyMap()
        _files.value = emptyMap()
        _hello.value = null
        setProjectFolder(null)
    }

    /** Sync, then follow the event stream; a dropped channel resubscribes from the last rev. */
    private suspend fun follow(t: ChannelTransport) {
        val c = HostClient(t)
        while (true) {
            try {
                t.state.first { it is LinkState.Ready }
                val sync = c.sync(rev)
                if (rev == 0L) _bots.value = emptyMap()
                sync.bots.forEach(::upsert)
                sync.entries.forEach(::upsert)
                rev = maxOf(rev, sync.rev)
                _synced.value = true
                runCatching { c.hello() }.getOrNull()?.let { h ->
                    _hello.value = h
                    h.you?.let { _you.value = it }
                }
                runCatching { syncUserProfileToHost() }
                if (notifications.value) runCatching { onConnected?.invoke(c) }
                c.events(rev).collect(::apply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(1000)
            }
        }
    }

    internal fun apply(event: HostEvent) {
        when (event) {
            is HostEvent.Hello -> Unit
            is HostEvent.BotChanged -> upsert(event.bot)
            is HostEvent.BotDeleted -> {
                _bots.update { it - event.id }
                rev = maxOf(rev, event.rev)
            }
            is HostEvent.EntryChanged -> upsert(event.entry)
            // Never skip what didn't decode: start over from rev 0 (as the iOS app does).
            HostEvent.Resync, is HostEvent.Undecodable -> {
                rev = 0
                throw HostException.unreachable()
            }
            is HostEvent.Other -> event.rev?.let { rev = maxOf(rev, it) }
        }
    }

    private fun upsert(bot: Bot) {
        rev = maxOf(rev, bot.rev)
        com.ragul84.hypurr.push.LocalAlerts.detect(_bots.value[bot.id], bot)?.let { kind ->
            if (notifications.value) runCatching { onLocalAlert?.invoke(kind, bot) }
        }
        if (bot.deleted == true) _bots.update { it - bot.id } else _bots.update { it + (bot.id to bot) }
    }

    private fun upsert(entry: Entry) {
        rev = maxOf(rev, entry.rev)
        val root = entry.threadId
        if (root != null) _threads.update { all -> all + (root to merged(all[root].orEmpty(), entry)) }
        else _entries.update { all -> all + (entry.botId to merged(all[entry.botId].orEmpty(), entry)) }
    }

    /** A sent message replaces its optimistic copy (same clientNonce); host order by seq, local ones last. */
    private fun merged(list: List<Entry>, entry: Entry): List<Entry> {
        val kept = list.filter { it.id != entry.id && !(entry.data.clientNonce != null && it.data.clientNonce == entry.data.clientNonce) }
        return (kept + entry).sortedWith(compareBy({ it.seq == 0L }, { it.seq }, { it.createdAt }))
    }

    // MARK: chat actions

    suspend fun openChat(botId: String) {
        val c = client ?: return
        runCatching { c.history(botId).forEach(::upsert) }
        runCatching { c.markRead(botId) }
    }

    /** Opens a thread: its replies, then marks it read (the main chat's unread stays as it is). */
    suspend fun openThread(botId: String, rootId: String) {
        val c = client ?: return
        runCatching { c.thread(botId, rootId) }.getOrNull()?.let { list ->
            _threads.update { all -> all + (rootId to list.fold(all[rootId].orEmpty().filter { it.seq == 0L }, ::merged)) }
        }
        runCatching { c.markRead(botId, rootId) }
    }

    /**
     * Sends now, or queues it in the relay mailbox while the computer is offline. `threadId` replies in
     * a thread. Files go up first (384 KiB chunks) and never through the mailbox: offline, a message with
     * files fails and the composer keeps them.
     */
    suspend fun send(botId: String, text: String, threadId: String? = null, files: List<PickedFile> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && files.isEmpty()) return
        val nonce = UUID.randomUUID().toString().uppercase()
        val now = System.currentTimeMillis()
        val uploads = files.map { UUID.randomUUID().toString() to it }
        val local = Entry(id = "local-$nonce", botId = botId, threadId = threadId, kind = "user", createdAt = now, updatedAt = now,
            data = EntryData(text = trimmed, status = "queued", clientNonce = nonce,
                attachments = uploads.map { (id, f) -> Attachment(id, f.name, f.size) }.ifEmpty { null }))
        upsertLocal(local)
        _files.update { all -> all + uploads.filter { it.second.isImage }.associate { (id, f) -> id to f.bytes } }
        val t = transport
        try {
            val c = client ?: throw HostException.unreachable()
            uploads.forEach { (id, f) -> c.upload(id, f.name, f.bytes, botId = botId) }
            upsert(c.send(botId, trimmed, nonce, threadId, uploads.map { it.first }))
        } catch (e: HostException) {
            val queued = uploads.isEmpty() && e.kind == HostException.Kind.Offline && t != null &&
                runCatching { t.enqueue(botId, trimmed, nonce, threadId) }.isSuccess
            upsertLocal(local.copy(data = local.data.copy(status = if (queued) "queued" else "failed")))
            if (!queued) throw e
        }
    }

    private fun upsertLocal(entry: Entry) {
        val root = entry.threadId
        if (root != null) _threads.update { all -> all + (root to (all[root].orEmpty().filter { it.id != entry.id } + entry)) }
        else _entries.update { all -> all + (entry.botId to (all[entry.botId].orEmpty().filter { it.id != entry.id } + entry)) }
    }

    /** Fetches a sent picture once for its preview (other files show as cards). */
    suspend fun loadAttachment(botId: String, a: Attachment) {
        if (!a.isImage || a.size > MAX_PREVIEW || a.id in _files.value || !fetching.add(a.id)) return
        try {
            val c = client ?: return
            runCatching { c.readUpload(botId, a.id, MAX_PREVIEW) }.getOrNull()?.let { bytes -> _files.update { it + (a.id to bytes) } }
        } finally {
            fetching.remove(a.id)
        }
    }

    /** Fetches any sent file (to open or share it). */
    suspend fun fileBytes(botId: String, a: Attachment): ByteArray {
        _files.value[a.id]?.let { return it }
        val c = client ?: throw HostException.unreachable()
        return c.readUpload(botId, a.id)
    }

    suspend fun react(entryId: String, emoji: String) {
        client?.react(entryId, emoji)?.let(::upsert)
    }

    /** Installs Hypurr's built-in Hypurr Agent after the user consents. */
    suspend fun installBuiltinAgent(): Backend {
        val b = (client ?: throw HostException.unreachable()).installBuiltinAgent()
        refreshHello()
        return b
    }

    // MARK: bots and groups

    /** Creates a bot or a group; returns it (a group with the same bots returns the one they share). */
    suspend fun createBot(config: JsonObject): Bot {
        val bot = client?.createBot(config) ?: throw HostException.unreachable()
        upsert(bot)
        return bot
    }

    suspend fun updateBot(patch: JsonObject): Bot {
        val bot = client?.updateBot(patch) ?: throw HostException.unreachable()
        upsert(bot)
        return bot
    }

    suspend fun deleteBot(botId: String) {
        (client ?: throw HostException.unreachable()).deleteBot(botId)
        _bots.update { it - botId }
    }

    suspend fun listDirs(path: String?): DirListing = (client ?: throw HostException.unreachable()).listDirs(path)

    /** Refreshes `hello` (agents for New bot, screen state). */
    suspend fun refreshHello(): Hello? {
        val c = client ?: return _hello.value
        return runCatching { c.hello() }.getOrNull()?.also { _hello.value = it } ?: _hello.value
    }

    suspend fun stop(botId: String) = client?.stop(botId)

    suspend fun respond(entryId: String, optionId: String?) = client?.respondPermission(entryId, optionId)

    // MARK: tasks and the safety net

    private val _setup = MutableStateFlow<TaskSetup?>(null)
    /** Templates, projects, agents and safety settings for the New task screen. */
    val setup: StateFlow<TaskSetup?> = _setup.asStateFlow()

    suspend fun loadSetup(): TaskSetup? {
        val c = client ?: return _setup.value
        return runCatching { c.taskSetup() }.getOrNull()?.also { _setup.value = it } ?: _setup.value
    }

    suspend fun route(goal: String, template: String?): TaskRoute? = client?.routeTask(goal, template)

    /**
     * Starts a task and returns its bot id (the chat to open). Files go up first as a draft
     * (the bot doesn't exist yet); the host moves them into the task's chat.
     */
    suspend fun startTask(goal: String, template: String?, input: String, project: String?, backend: String?,
                          files: List<PickedFile> = emptyList(), issue: Issue? = null, model: String? = null): String {
        val c = client ?: throw HostException.unreachable()
        val draft = if (files.isEmpty()) null else UUID.randomUUID().toString()
        val ids = files.map { f ->
            val id = UUID.randomUUID().toString()
            c.upload(id, f.name, f.bytes, draftId = draft)
            id
        }
        val bot = c.startTask(goal, template, input, project, backend, ids, draftId = draft, issue = issue, learning = learningMode.value, model = model)
        upsert(bot)
        return bot.id
    }

    // MARK: Ticket to PR
    suspend fun qaCases(source: String, input: String, project: String?): QaCasesResult = cl().qaCases(source, input, project)
    suspend fun qaAutomate(cases: List<QaCase>, framework: String, project: String): String {
        val bot = cl().qaAutomate(cases, framework, project)
        upsert(bot)
        return bot.id
    }
    suspend fun qaSaveCases(project: String, cases: List<QaCase>, input: String, source: String) = cl().qaSaveCases(project, cases, input, source)
    suspend fun qaSavedCases(project: String) = cl().qaSavedCases(project)
    suspend fun qaRun(taskId: String): QaRunResult = cl().qaRun(taskId)
    suspend fun traceStart(project: String, url: String, steps: List<String>) = cl().traceStart(project, url, steps)
    suspend fun traceAllow(project: String, url: String, steps: List<String>) = cl().traceAllow(project, url, steps)
    suspend fun traceStop(id: String) = cl().traceStop(id)
    suspend fun traceGet(project: String, id: String) = cl().traceGet(project, id)
    suspend fun traceExport(project: String, id: String) = cl().traceExport(project, id)
    suspend fun mapScan(project: String) = cl().mapScan(project)
    suspend fun mapGet(project: String) = cl().mapGet(project)
    suspend fun restructureApply(project: String) = cl().restructureApply(project)
    suspend fun restructureDiscard(project: String) = cl().restructureDiscard(project)
    suspend fun restructurePublish(project: String) = cl().restructurePublish(project)


    // Project / task shell
    suspend fun openShell(cwd: String? = null, botId: String? = null, taskId: String? = null, cols: Int = 80, rows: Int = 24) =
        cl().openShell(cwd, botId, taskId, cols, rows)
    suspend fun termInput(term: String, dataBase64: String) = cl().termInput(term, dataBase64)
    suspend fun termResize(term: String, cols: Int, rows: Int) = cl().termResize(term, cols, rows)
    suspend fun termClose(term: String) = cl().termClose(term)
    fun termEvents(termId: String) = cl().termEvents(termId)

    private fun cl() = client ?: throw HostException.unreachable()
    suspend fun ticketSetup(): TicketSetup = cl().ticketSetup()
    suspend fun ticketResolve(input: String, project: String?): TicketResolve = cl().ticketResolve(input, project)
    suspend fun ticketPlan(ticket: Issue, project: String): TicketPlanResult = cl().ticketPlan(ticket, project)
    suspend fun ticketStart(ticket: Issue, plan: TicketPlan, project: String): String {
        val bot = cl().ticketStart(ticket, plan, project)
        upsert(bot)
        return bot.id
    }
    suspend fun ticketReview(taskId: String): TicketReview = cl().ticketReview(taskId)
    suspend fun ticketPublish(taskId: String, title: String, body: String): TicketPr = cl().ticketPublish(taskId, title, body)

    /** Open GitHub issues and Jira tickets to start a task from. */
    suspend fun issues(): IssueList = client?.issues() ?: IssueList()

    private fun need() = client ?: throw HostException.unreachable()
    suspend fun triageRedact(text: String) = need().triageRedact(text)
    suspend fun triage(text: String, project: String?, mode: String = "explain", previous: com.ragul84.hypurr.model.Triage? = null) =
        need().triage(text, project, mode, previous)
    suspend fun triageSources(project: String) = need().triageSources(project).sources
    suspend fun triageSource(project: String, kind: String, id: String) = need().triageSource(project, kind, id).text
    suspend fun standup(projects: List<String>, useAgent: Boolean = true) =
        (client ?: throw HostException.unreachable()).standup(projects, useAgent)

    /** `null` when this phone isn't an admin (the host refuses) or the computer is unreachable. */
    suspend fun teamStandup() = runCatching { client?.teamStandup() }.getOrNull()

    suspend fun standupReminder() = runCatching { client?.standupReminder()?.reminder }.getOrNull()

    suspend fun setStandupReminder(enabled: Boolean, time: String, timeZone: String) =
        (client ?: throw HostException.unreachable()).setStandupReminder(enabled, time, timeZone).reminder

    // MARK: work tools and costs

    private val _integrations = MutableStateFlow<Integrations?>(null)
    val integrations: StateFlow<Integrations?> = _integrations.asStateFlow()

    suspend fun loadIntegrations(): Integrations? {
        val c = client ?: return _integrations.value
        return runCatching { c.integrations() }.getOrNull()?.also { _integrations.value = it } ?: _integrations.value
    }

    suspend fun setIntegrations(patch: JsonObject) {
        val saved = client?.setIntegrations(patch) ?: throw HostException.unreachable()
        _integrations.value = saved
        _setup.update { it?.copy(integrations = saved) }
    }

    suspend fun testIntegration(kind: String): String = client?.testIntegration(kind) ?: throw HostException.unreachable()

    private val _costs = MutableStateFlow<TaskCosts?>(null)
    val costs: StateFlow<TaskCosts?> = _costs.asStateFlow()

    suspend fun ownFreeStart(): String = (client ?: throw HostException.unreachable()).ownFreeStart()
    suspend fun ownFreeFinish(code: String) { (client ?: throw HostException.unreachable()).ownFreeFinish(code); loadCosts(); refreshHello() }
    suspend fun ownFreeDisconnect() { (client ?: throw HostException.unreachable()).ownFreeDisconnect(); loadCosts(); refreshHello() }

    suspend fun loadCosts(): TaskCosts? {
        val c = client ?: return _costs.value
        return runCatching { c.taskCosts() }.getOrNull()?.also { _costs.value = it } ?: _costs.value
    }

    /** Undo / go back: stops a working agent first, then restores the checkpoint. */
    suspend fun rollback(botId: String, checkpointId: String) {
        val c = client ?: throw HostException.unreachable()
        val bot = _bots.value[botId] ?: return
        val task = bot.task ?: return
        if (bot.status == "working" || bot.status == "needsInput") {
            runCatching { c.stop(botId) }
            // The host refuses a rollback while the turn is still winding down.
            var waited = 0
            while (_bots.value[botId]?.status == "working" && waited++ < 20) delay(250)
        }
        var last: Exception? = null
        repeat(5) {
            try {
                c.rollbackTask(task.id, checkpointId)
                return
            } catch (e: HostException) {
                last = e
                delay(400)
            }
        }
        last?.let { throw it }
    }

    suspend fun finishTask(botId: String, openPr: Boolean? = null, notify: Boolean? = null, learning: Boolean? = null) {
        val task = _bots.value[botId]?.task ?: return
        client?.finishTask(task.id, openPr, notify, learning)
    }

    suspend fun saveCheckpoint(botId: String) {
        val task = _bots.value[botId]?.task ?: return
        client?.taskCheckpoint(task.id, "Saved by you")
    }

    suspend fun saveTemplate(template: TaskTemplate) {
        client?.saveTemplate(template)
        loadSetup()
    }

    suspend fun deleteTemplate(id: String) {
        client?.deleteTemplate(id)
        loadSetup()
    }

    suspend fun setSafety(settings: SafetySettings) {
        val saved = client?.setSafety(settings) ?: return
        _setup.update { it?.copy(safety = saved) ?: TaskSetup(safety = saved) }
    }

    // MARK: team admin

    private val _you = MutableStateFlow<Actor?>(null)
    /** This phone's role on the computer (null until the host says). */
    val you: StateFlow<Actor?> = _you.asStateFlow()

    private val _team = MutableStateFlow<TeamInfo?>(null)
    val team: StateFlow<TeamInfo?> = _team.asStateFlow()
    private val _policies = MutableStateFlow<PolicyInfo?>(null)
    val policies: StateFlow<PolicyInfo?> = _policies.asStateFlow()
    private val _activity = MutableStateFlow<Activity?>(null)
    val activity: StateFlow<Activity?> = _activity.asStateFlow()
    private val _audit = MutableStateFlow<AuditLog?>(null)
    val audit: StateFlow<AuditLog?> = _audit.asStateFlow()

    private val _spend = MutableStateFlow<com.ragul84.hypurr.model.SpendByPerson?>(null)
    val spendByPerson: StateFlow<com.ragul84.hypurr.model.SpendByPerson?> = _spend.asStateFlow()
    private val _itKit = MutableStateFlow<com.ragul84.hypurr.model.ItKit?>(null)
    val itKit: StateFlow<com.ragul84.hypurr.model.ItKit?> = _itKit.asStateFlow()
    private val _pricing = MutableStateFlow<com.ragul84.hypurr.model.Pricing?>(null)
    val pricing: StateFlow<com.ragul84.hypurr.model.Pricing?> = _pricing.asStateFlow()

    suspend fun loadItKit() {
        val c = client ?: return
        runCatching { c.itKit() }.getOrNull()?.let { _itKit.value = it }
    }

    suspend fun loadPricing() {
        val c = client ?: return
        runCatching { c.pricing() }.getOrNull()?.let { _pricing.value = it }
    }

    /** The audit log as a CSV file, for the share sheet (admins only). */
    suspend fun auditExport(): com.ragul84.hypurr.model.AuditExport =
        client?.auditExport("csv") ?: throw HostException.unreachable()

    /** Loads what the Team admin screen shows; admin-only parts are skipped for others. */
    suspend fun loadAdmin() {
        val c = client ?: return
        runCatching { c.team() }.getOrNull()?.let {
            _team.value = it
            _you.value = it.you
        }
        runCatching { c.policies() }.getOrNull()?.let { _policies.value = it }
        if (_you.value?.isAdmin != false) {
            runCatching { c.activity() }.getOrNull()?.let { _activity.value = it }
            runCatching { c.auditLog() }.getOrNull()?.let { _audit.value = it }
            runCatching { c.spendByPerson() }.getOrNull()?.let { _spend.value = it }
        }
    }

    /** The team's rules (anyone may read them): New bot offers only allowed agents. */
    suspend fun loadPolicies() {
        val c = client ?: return
        runCatching { c.policies() }.getOrNull()?.let { _policies.value = it }
    }

    suspend fun setPolicies(patch: JsonObject) {
        val saved = client?.setPolicies(patch) ?: throw HostException.unreachable()
        _policies.value = saved
        _setup.update { it?.copy(safety = saved.safety) }
        runCatching { client?.auditLog() }.getOrNull()?.let { _audit.value = it }
        runCatching { client?.spendByPerson() }.getOrNull()?.let { _spend.value = it }
    }

    suspend fun setRole(key: String, role: String?) {
        _team.value = client?.setRole(key, role) ?: throw HostException.unreachable()
    }

    suspend fun setDefaultRole(role: String) {
        _team.value = client?.setTeam(role) ?: throw HostException.unreachable()
    }

    val route: Route? get() = (link.value as? LinkState.Ready)?.route

    private companion object {
        const val COMPUTER = "computer"
        const val THEME = "theme"
        const val DYNAMIC = "dynamicColor"
        const val NOTIFY = "notifications"
        const val ANYWHERE = "connect_anywhere"
        const val USER_PROFILE = "user.profile"
        const val PROJECT_FOLDER = "project.folder"
        const val LAST_TASK_AGENT = "task.agent"
        const val LAST_TASK_MODEL = "task.model"
        const val LEARNING = "learning.mode"
        /** Pictures larger than this show as file cards instead of previews. */
        const val MAX_PREVIEW = 20L * 1024 * 1024
    }
}
