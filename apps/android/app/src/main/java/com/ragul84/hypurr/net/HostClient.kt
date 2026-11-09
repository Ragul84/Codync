package com.ragul84.hypurr.net

import com.ragul84.hypurr.data.DeviceIdentity
import com.ragul84.hypurr.model.Backend
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.TicketSetup
import com.ragul84.hypurr.model.TraceReply
import com.ragul84.hypurr.model.TraceGetReply
import com.ragul84.hypurr.model.TraceExport
import com.ragul84.hypurr.model.MapReply
import com.ragul84.hypurr.model.QaCase
import com.ragul84.hypurr.model.QaCasesResult
import com.ragul84.hypurr.model.QaRunResult
import com.ragul84.hypurr.model.TicketResolve
import com.ragul84.hypurr.model.TicketPlanResult
import com.ragul84.hypurr.model.TicketPlan
import com.ragul84.hypurr.model.TicketReview
import com.ragul84.hypurr.model.TicketPr
import com.ragul84.hypurr.model.Computer
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.Hello
import com.ragul84.hypurr.model.DirListing
import com.ragul84.hypurr.model.ScreenConnection
import com.ragul84.hypurr.model.ScreenState
import com.ragul84.hypurr.model.TeamInfo
import com.ragul84.hypurr.model.PolicyInfo
import com.ragul84.hypurr.model.AuditLog
import com.ragul84.hypurr.model.Activity
import com.ragul84.hypurr.model.HypurrJson
import com.ragul84.hypurr.model.Pairing
import com.ragul84.hypurr.model.Attachment
import com.ragul84.hypurr.model.Integrations
import com.ragul84.hypurr.model.Issue
import com.ragul84.hypurr.model.IssueList
import com.ragul84.hypurr.model.SafetySettings
import com.ragul84.hypurr.model.TaskCosts
import com.ragul84.hypurr.model.SyncResponse
import com.ragul84.hypurr.model.TaskInfo
import com.ragul84.hypurr.model.TaskRoute
import com.ragul84.hypurr.model.TaskSetup
import com.ragul84.hypurr.model.TaskTemplate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One host event (`sub: events`), as kit HostClient.parseEvent maps them. */
sealed interface HostEvent {
    data class Hello(val hostId: String, val rev: Long) : HostEvent
    data class BotChanged(val bot: Bot) : HostEvent
    data class BotDeleted(val id: String, val rev: Long) : HostEvent
    data class EntryChanged(val entry: Entry) : HostEvent
    data object Resync : HostEvent
    /** A known type that didn't decode: the store rewinds to rev 0 rather than skip it. */
    data class Undecodable(val type: String) : HostEvent
    data class Other(val type: String, val rev: Long?) : HostEvent

    companion object {
        fun parse(ev: JsonElement): HostEvent? {
            val o = ev as? JsonObject ?: return null
            val type = o.str("type") ?: return null
            val rev = o["rev"]?.jsonPrimitive?.longOrNull
            return when (type) {
                "hello" -> Hello(o.str("hostId") ?: "", rev ?: 0)
                "bot" -> runCatching {
                    val bot = HypurrJson.decodeFromJsonElement<Bot>(o.getValue("bot"))
                    if (bot.deleted == true) BotDeleted(bot.id, bot.rev) else BotChanged(bot)
                }.getOrElse { Undecodable(type) }
                "entry" -> runCatching { EntryChanged(HypurrJson.decodeFromJsonElement<Entry>(o.getValue("entry"))) }
                    .getOrElse { Undecodable(type) }
                "resync" -> Resync
                else -> Other(type, rev)
            }
        }
    }
}

/** Typed host methods over a [ChannelTransport] (kit HostClient.swift). */
class HostClient(val transport: ChannelTransport) {
    private suspend inline fun <reified T> call(method: String, body: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 20_000): T =
        HypurrJson.decodeFromJsonElement(transport.call(method, body, timeoutMs))

    suspend fun hello(): Hello = call("hello")

    suspend fun sync(since: Long): SyncResponse = call("sync", buildJsonObject { put("since", since) })

    suspend fun history(botId: String, beforeSeq: Long = Long.MAX_VALUE, limit: Int = 100): List<Entry> {
        val res = transport.call("history", buildJsonObject {
            put("botId", botId)
            put("beforeSeq", beforeSeq)
            put("limit", limit)
        })
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("entries"))
    }

    /** `threadId` replies in that root's thread; `attachments` are upload ids sent first with [upload]. */
    suspend fun send(botId: String, text: String, clientNonce: String, threadId: String? = null,
                     attachments: List<String> = emptyList()): Entry {
        val res = transport.call("send", buildJsonObject {
            put("botId", botId)
            put("text", text)
            put("clientNonce", clientNonce)
            threadId?.let { put("threadId", it) }
            if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments.map(::JsonPrimitive)))
        })
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("entry"))
    }

    suspend fun stop(botId: String) {
        transport.call("stop", buildJsonObject { put("botId", botId) })
    }

    suspend fun newSession(botId: String) {
        transport.call("newSession", buildJsonObject { put("botId", botId) })
    }

    /** The main chat, or one thread (`threadId`). */
    suspend fun markRead(botId: String, threadId: String? = null) {
        transport.call("markRead", buildJsonObject {
            put("botId", botId)
            threadId?.let { put("threadId", it) }
            put("all", false)
        })
    }

    /** A thread's replies, oldest first (the root is in the main chat). */
    suspend fun thread(botId: String, rootId: String): List<Entry> {
        val res = transport.call("thread", buildJsonObject {
            put("botId", botId)
            put("rootId", rootId)
        })
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("entries"))
    }

    /** Toggles this user's reaction; returns the updated message. */
    suspend fun react(entryId: String, emoji: String): Entry {
        val res = transport.call("react", buildJsonObject {
            put("entryId", entryId)
            put("emoji", emoji)
        })
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("entry"))
    }

    /** A sent file, fetched back in 384 KiB chunks (for previews on this phone). */
    suspend fun readUpload(botId: String, uploadId: String, maxBytes: Long = 100L * 1024 * 1024): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val res = transport.call("readUpload", buildJsonObject {
                put("botId", botId)
                put("uploadId", uploadId)
                put("offset", out.size().toLong())
            }, timeoutMs = 60_000).jsonObject
            val chunk = java.util.Base64.getDecoder().decode(res.str("data") ?: "")
            out.write(chunk)
            val size = res["size"]?.jsonPrimitive?.longOrNull ?: out.size().toLong()
            if (chunk.isEmpty() || out.size() >= size || out.size() >= maxBytes) break
        }
        return out.toByteArray()
    }

    // Bots and groups.

    /** Downloads the pinned Hypurr Agent build into `~/.hypurr/agents/hypurr-agent` (user must have consented). */
    suspend fun installBuiltinAgent(): Backend {
        val res = transport.call("installBuiltinAgent", buildJsonObject { put("consent", true) }, timeoutMs = 600_000)
        return HypurrJson.decodeFromJsonElement(res)
    }

    /** A bot (`backend`, `cwd` empty = a personal workspace, `permission`) or a group (`kind: "group"`, `members`). */
    suspend fun createBot(config: JsonObject): Bot {
        val res = transport.call("createBot", config, timeoutMs = 30_000)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("bot"))
    }

    /** Partial update: `id` plus the fields that change. */
    suspend fun updateBot(patch: JsonObject): Bot {
        val res = transport.call("updateBot", patch, timeoutMs = 30_000)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("bot"))
    }

    suspend fun deleteBot(botId: String) {
        transport.call("deleteBot", buildJsonObject { put("botId", botId) })
    }

    /** Local user preferences for agent tone/defaults (non-sensitive). */
    suspend fun setUserProfile(body: JsonObject): JsonObject =
        transport.call("setUserProfile", body).jsonObject

    suspend fun userProfile(): JsonObject =
        transport.call("userProfile").jsonObject

    /** Folders on the computer (`path` null = home). */
    suspend fun listDirs(path: String?): DirListing = call("listDirs", buildJsonObject { path?.let { put("path", it) } })

    // Remote screen (host `screen`; video and input go over WebRTC).

    suspend fun screenStatus(): ScreenState = call("screenStatus")

    /** `relay`: the channel goes through the cloud, so the host fetches TURN servers. */
    suspend fun screenPrepare(relay: Boolean): ScreenConnection = call("screenPrepare", buildJsonObject { put("relay", relay) })

    /** Non-trickle: the offer carries every candidate; returns `{session, sdp}` (the answer). */
    suspend fun screenOffer(sdp: String, session: String?, display: Long?): Pair<String, String> {
        val res = transport.call("screenOffer", buildJsonObject {
            put("sdp", sdp)
            session?.let { put("session", it) }
            display?.let { put("display", it) }
        }, timeoutMs = 30_000).jsonObject
        return (res.str("session") ?: session.orEmpty()) to (res.str("sdp") ?: throw HostException(HostException.Kind.Http, "No answer from the screen helper."))
    }

    suspend fun screenClose(session: String) {
        transport.call("screenClose", buildJsonObject { put("session", session) })
    }

    suspend fun respondPermission(entryId: String, optionId: String?) {
        transport.call("respondPermission", buildJsonObject {
            put("entryId", entryId)
            optionId?.let { put("optionId", it) }
        })
    }

    /** Push tickets are bound to this device's key; `pushKey` lets the host seal notification text (§6.7). */
    suspend fun registerDevice(ticket: String, relay: String, name: String, pushKey: String, ctx: String) {
        transport.call("registerDevice", buildJsonObject {
            put("ticket", ticket)
            put("relay", relay)
            put("name", name)
            put("pushKey", pushKey)
            put("ctx", ctx)
        })
    }

    suspend fun unregisterDevice() {
        transport.call("unregisterDevice")
    }

    // Beginner tasks and the safety net (host `tasks`).

    suspend fun taskSetup(): TaskSetup = call("taskSetup")

    suspend fun routeTask(goal: String, template: String?): TaskRoute {
        val res = transport.call("routeTask", buildJsonObject {
            put("goal", goal)
            template?.let { put("template", it) }
        })
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("route"))
    }

    /** Starts a task; returns its bot (with `task`). */
    suspend fun startTask(goal: String, template: String?, input: String, project: String?, backend: String?,
                          attachments: List<String> = emptyList(), command: String? = null,
                          draftId: String? = null, issue: Issue? = null, learning: Boolean? = null, model: String? = null): Bot {
        val res = transport.call("startTask", buildJsonObject {
            put("goal", goal)
            learning?.let { put("learning", it) }
            draftId?.let { put("draftId", it) }
            issue?.let { put("issue", HypurrJson.encodeToJsonElement(Issue.serializer(), it)) }
            template?.let { put("template", it) }
            if (input.isNotBlank()) put("input", input)
            project?.let { put("project", it) }
            backend?.let { put("backend", it) }
            model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
            if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments.map(::JsonPrimitive)))
            command?.let { put("command", it) }
        }, timeoutMs = 60_000)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("bot"))
    }

    suspend fun projectGuide(project: String, kind: String): com.ragul84.hypurr.model.GuideDoc =
        call("projectGuide", buildJsonObject { put("project", project); put("kind", kind) }, timeoutMs = 240_000)

    suspend fun projectAsk(project: String, question: String): com.ragul84.hypurr.model.AskAnswer =
        call("projectAsk", buildJsonObject { put("project", project); put("question", question) }, timeoutMs = 240_000)

    /** Saves the guide as docs/HYPURR_GUIDE.md through a normal safe-branch task. */
    suspend fun projectGuideSave(project: String, markdown: String): Bot {
        val res = transport.call("projectGuideSave", buildJsonObject { put("project", project); put("markdown", markdown) }, timeoutMs = 60_000)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("bot"))
    }

    suspend fun learnCards(taskId: String): com.ragul84.hypurr.model.LearnCards =
        call("learnCards", buildJsonObject { put("taskId", taskId) })

    suspend fun proof(taskId: String): com.ragul84.hypurr.model.ProofReply =
        call("proofGet", buildJsonObject { put("taskId", taskId) })

    suspend fun proofStart(taskId: String): com.ragul84.hypurr.model.ProofReply =
        call("proofStart", buildJsonObject { put("taskId", taskId) })

    suspend fun proofAllow(taskId: String): com.ragul84.hypurr.model.ProofReply =
        call("proofAllow", buildJsonObject { put("taskId", taskId) })

    suspend fun proofFile(taskId: String, name: String): com.ragul84.hypurr.model.ProofFileData =
        call("proofFile", buildJsonObject { put("taskId", taskId); put("name", name) }, timeoutMs = 120_000)

    suspend fun proofVerdict(taskId: String, ok: Boolean, text: String) {
        transport.call("proofVerdict", buildJsonObject { put("taskId", taskId); put("ok", ok); put("text", text) })
    }

    suspend fun learnQuiz(taskId: String): com.ragul84.hypurr.model.Quiz =
        call("learnQuiz", buildJsonObject { put("taskId", taskId) }, timeoutMs = 240_000)

    suspend fun rollbackTask(taskId: String, checkpointId: String): TaskInfo {
        val res = transport.call("rollbackTask", buildJsonObject {
            put("taskId", taskId)
            put("checkpointId", checkpointId)
        }, timeoutMs = 60_000)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("task"))
    }

    suspend fun taskCheckpoint(taskId: String, label: String) {
        transport.call("taskCheckpoint", buildJsonObject {
            put("taskId", taskId)
            put("label", label)
        }, timeoutMs = 60_000)
    }

    /** Unset options follow the host's Work tools settings. */
    suspend fun finishTask(taskId: String, openPr: Boolean? = null, notify: Boolean? = null, learning: Boolean? = null) {
        transport.call("finishTask", buildJsonObject {
            put("taskId", taskId)
            openPr?.let { put("openPr", it) }
            notify?.let { put("notify", it) }
            learning?.let { put("learning", it) }
        }, timeoutMs = 60_000)
    }

    /**
     * One file for a task that hasn't started yet (`draftId`) or a bot's chat (`botId`), in
     * 384 KiB chunks so each call stays under the channel's 1 MiB limit (docs/features/file-attachments.md).
     */
    suspend fun upload(uploadId: String, name: String, bytes: ByteArray, draftId: String? = null, botId: String? = null): Attachment {
        val chunk = 384 * 1024
        var offset = 0
        var result: Attachment? = null
        do {
            val end = minOf(bytes.size, offset + chunk)
            val done = end == bytes.size
            val res = transport.call("upload", buildJsonObject {
                draftId?.let { put("draftId", it) }
                botId?.let { put("botId", it) }
                put("uploadId", uploadId)
                put("name", name)
                put("offset", offset.toLong())
                put("data", java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end)))
                put("done", done)
            }, timeoutMs = 60_000)
            if (done) result = HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("attachment"))
            offset = end
        } while (!done)
        return result!!
    }

    // Work tools (host `integrations`).

    suspend fun integrations(): Integrations {
        val res = transport.call("integrations")
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("integrations"))
    }

    /** Partial update: only the given fields change; an empty string clears a credential. */
    suspend fun setIntegrations(patch: JsonObject): Integrations {
        val res = transport.call("setIntegrations", patch)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("integrations"))
    }

    /** Checks a connection or posts a test message; returns what happened in plain words. */
    suspend fun testIntegration(kind: String): String {
        val res = transport.call("testIntegration", buildJsonObject { put("kind", kind) }, timeoutMs = 40_000)
        return res.jsonObject.str("detail") ?: "OK"
    }

    suspend fun issues(project: String? = null): IssueList =
        call("issues", buildJsonObject { project?.let { put("project", it) } }, timeoutMs = 45_000)

    suspend fun taskCosts(): TaskCosts = call("taskCosts")

    // "Keep going free": the user's own OpenRouter account (OAuth PKCE; the computer keeps the key).
    suspend fun ownFreeStart(): String = call<JsonObject>("ownFreeStart")["url"]!!.jsonPrimitive.content
    suspend fun ownFreeFinish(code: String) { call<JsonObject>("ownFreeFinish", buildJsonObject { put("code", code) }, timeoutMs = 30_000) }
    suspend fun ownFreeDisconnect() { call<JsonObject>("ownFreeDisconnect") }

    // Trace a click & Page map
    suspend fun traceStart(project: String, url: String, steps: List<String>): TraceReply = call("traceStart", buildJsonObject {
        put("project", project); put("url", url)
        put("steps", kotlinx.serialization.json.JsonArray(steps.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }, timeoutMs = 30_000)
    suspend fun traceAllow(project: String, url: String, steps: List<String>): TraceReply = call("traceAllow", buildJsonObject {
        put("project", project); put("url", url)
        put("steps", kotlinx.serialization.json.JsonArray(steps.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }, timeoutMs = 30_000)
    suspend fun traceStop(id: String) { transport.call("traceStop", buildJsonObject { put("id", id) }) }
    suspend fun traceGet(project: String, id: String): TraceGetReply = call("traceGet", buildJsonObject { put("project", project); put("id", id) })
    suspend fun traceExport(project: String, id: String): TraceExport = call("traceExport", buildJsonObject { put("project", project); put("id", id) })
    suspend fun mapScan(project: String): MapReply = call("mapScan", buildJsonObject { put("project", project) }, timeoutMs = 120_000)
    suspend fun mapGet(project: String): MapReply = call("mapGet", buildJsonObject { put("project", project) })
    suspend fun restructureApply(project: String): MapReply = call("restructureApply", buildJsonObject { put("project", project); put("approved", true) })
    suspend fun restructureDiscard(project: String) { transport.call("restructureDiscard", buildJsonObject { put("project", project) }) }
    suspend fun restructurePublish(project: String): MapReply =
        call("restructurePublish", buildJsonObject { put("project", project); put("approved", true) }, timeoutMs = 90_000)

    // QA test writer
    suspend fun qaCases(source: String, input: String, project: String?): QaCasesResult = call("qaCases", buildJsonObject {
        put("source", source); put("input", input); project?.let { put("project", it) }
    }, timeoutMs = 180_000)

    suspend fun qaAutomate(cases: List<QaCase>, framework: String, project: String): Bot {
        val res = transport.call("qaAutomate", buildJsonObject {
            put("cases", HypurrJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(QaCase.serializer()), cases))
            put("framework", framework); put("project", project)
        }, timeoutMs = 120_000)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("bot"))
    }

    suspend fun qaSaveCases(project: String, cases: List<QaCase>, input: String, source: String) {
        transport.call("qaSaveCases", buildJsonObject {
            put("project", project); put("input", input); put("source", source)
            put("cases", HypurrJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(QaCase.serializer()), cases))
        })
    }

    suspend fun qaSavedCases(project: String): com.ragul84.hypurr.model.QaSaved =
        call("qaSavedCases", buildJsonObject { put("project", project) })

    suspend fun qaRun(taskId: String): QaRunResult {
        val res = transport.call("qaRun", buildJsonObject { put("taskId", taskId) }, timeoutMs = 960_000)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("result"))
    }

    // Ticket to PR
    suspend fun ticketSetup(): TicketSetup = call("ticketSetup", timeoutMs = 30_000)

    suspend fun ticketResolve(input: String, project: String?): TicketResolve =
        call("ticketResolve", buildJsonObject { put("input", input); project?.let { put("project", it) } }, timeoutMs = 45_000)

    suspend fun ticketPlan(ticket: Issue, project: String): TicketPlanResult = call("ticketPlan", buildJsonObject {
        put("ticket", HypurrJson.encodeToJsonElement(Issue.serializer(), ticket))
        put("project", project)
    }, timeoutMs = 180_000)

    suspend fun ticketStart(ticket: Issue, plan: TicketPlan, project: String): Bot {
        val res = transport.call("ticketStart", buildJsonObject {
            put("ticket", HypurrJson.encodeToJsonElement(Issue.serializer(), ticket))
            put("plan", HypurrJson.encodeToJsonElement(TicketPlan.serializer(), plan))
            put("project", project)
        }, timeoutMs = 120_000)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("bot"))
    }

    suspend fun ticketReview(taskId: String): TicketReview =
        call("ticketReview", buildJsonObject { put("taskId", taskId) }, timeoutMs = 180_000)

    /** Uploads the branch and opens the PR. Only called from the Needs-you approval. */
    suspend fun ticketPublish(taskId: String, title: String, body: String): TicketPr {
        val res = transport.call("ticketPublish", buildJsonObject {
            put("taskId", taskId)
            put("approved", true)
            put("title", title)
            put("body", body)
        }, timeoutMs = 120_000)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("pr"))
    }

    suspend fun saveTemplate(template: TaskTemplate): TaskTemplate {
        val res = transport.call("saveTemplate", HypurrJson.encodeToJsonElement(TaskTemplate.serializer(), template).jsonObject)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("template"))
    }

    suspend fun deleteTemplate(id: String) {
        transport.call("deleteTemplate", buildJsonObject { put("id", id) })
    }

    suspend fun setSafety(settings: SafetySettings): SafetySettings {
        val res = transport.call("setSafetySettings", HypurrJson.encodeToJsonElement(SafetySettings.serializer(), settings).jsonObject)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("safety"))
    }

    // Team admin (host `admin`).

    suspend fun team(): TeamInfo = call("team")

    /** `role: null` goes back to the default role. */
    suspend fun setRole(key: String, role: String?): TeamInfo = call("setRole", buildJsonObject {
        put("key", key)
        if (role == null) put("role", kotlinx.serialization.json.JsonNull) else put("role", role)
    })

    suspend fun setTeam(defaultRole: String): TeamInfo = call("setTeam", buildJsonObject { put("defaultRole", defaultRole) })

    suspend fun policies(): PolicyInfo = call("policies")

    suspend fun spendByPerson(): com.ragul84.hypurr.model.SpendByPerson = call("spendByPerson")

    suspend fun auditExport(format: String = "csv"): com.ragul84.hypurr.model.AuditExport =
        call("auditExport", buildJsonObject { put("format", format) })

    suspend fun itKit(): com.ragul84.hypurr.model.ItKit = call("itKit")

    suspend fun pricing(): com.ragul84.hypurr.model.Pricing = call("pricing")

    /** Partial update of the team rules and the safety net settings. */
    suspend fun setPolicies(patch: JsonObject): PolicyInfo = call("setPolicies", patch)

    suspend fun auditLog(before: Long? = null, limit: Int = 100): AuditLog = call("auditLog", buildJsonObject {
        before?.let { put("before", it) }
        put("limit", limit)
    })

    suspend fun activity(days: Int = 7): Activity = call("activity", buildJsonObject { put("days", days) })

    suspend fun triageRedact(text: String): com.ragul84.hypurr.model.RedactPreview = call("triageRedact", buildJsonObject { put("text", text) })

    /** `mode`: explain | more (with `previous`). Secrets are removed on the computer before any agent sees the text. */
    suspend fun triage(text: String, project: String?, mode: String = "explain", previous: com.ragul84.hypurr.model.Triage? = null):
        com.ragul84.hypurr.model.TriageResult = call("triage", buildJsonObject {
            put("text", text)
            project?.let { put("project", it) }
            put("mode", mode)
            previous?.let { put("previous", com.ragul84.hypurr.model.HypurrJson.encodeToJsonElement(com.ragul84.hypurr.model.Triage.serializer(), it)) }
        }, timeoutMs = 180_000)

    suspend fun triageSources(project: String): com.ragul84.hypurr.model.TriageSources =
        call("triageSources", buildJsonObject { put("project", project) }, timeoutMs = 40_000)

    suspend fun triageSource(project: String, kind: String, id: String): com.ragul84.hypurr.model.TriageSourceText =
        call("triageSource", buildJsonObject {
            put("project", project)
            put("kind", kind)
            put("id", id)
        }, timeoutMs = 60_000)
    /** The agent may take a minute to write it; the host falls back to a plain draft. */
    suspend fun standup(projects: List<String>, useAgent: Boolean = true): com.ragul84.hypurr.model.StandupResult =
        call("standup", buildJsonObject {
            put("projects", kotlinx.serialization.json.JsonArray(projects.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("useAgent", useAgent)
        }, timeoutMs = 150_000)

    suspend fun teamStandup(): com.ragul84.hypurr.model.TeamStandup = call("teamStandup")

    suspend fun standupReminder(): com.ragul84.hypurr.model.StandupReminderReply = call("standupReminder")

    suspend fun setStandupReminder(enabled: Boolean, time: String, timeZone: String): com.ragul84.hypurr.model.StandupReminderReply =
        call("setStandupReminder", buildJsonObject {
            put("enabled", enabled)
            put("time", time)
            put("timeZone", timeZone)
        })


    // --- Routines (schedule / webhook automations on this bot) ---
    suspend fun routines(botId: String): com.ragul84.hypurr.model.RoutineListing =
        call("routines", buildJsonObject { put("botId", botId) })

    suspend fun routineSchedule(
        triggers: List<com.ragul84.hypurr.model.RoutineTrigger>? = null,
        timeZone: String? = null,
        draft: com.ragul84.hypurr.model.RoutineScheduleDraft? = null,
    ): com.ragul84.hypurr.model.RoutineSchedulePreview =
        call("routineSchedule", buildJsonObject {
            triggers?.let {
                put("triggers", com.ragul84.hypurr.model.HypurrJson.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(com.ragul84.hypurr.model.RoutineTrigger.serializer()), it))
            }
            timeZone?.let { put("timeZone", it) }
            draft?.let {
                put("draft", com.ragul84.hypurr.model.HypurrJson.encodeToJsonElement(
                    com.ragul84.hypurr.model.RoutineScheduleDraft.serializer(), it))
            }
        })

    suspend fun saveRoutine(
        botId: String,
        id: String?,
        name: String,
        instruction: String,
        schedule: com.ragul84.hypurr.model.RoutineScheduleDraft,
        timeoutSeconds: String,
        enabled: Boolean? = null,
    ): com.ragul84.hypurr.model.Routine {
        val reply: com.ragul84.hypurr.model.RoutineSaveReply = call("saveRoutine", buildJsonObject {
            put("botId", botId)
            id?.let { put("id", it) }
            put("name", name)
            put("instruction", instruction)
            put("schedule", com.ragul84.hypurr.model.HypurrJson.encodeToJsonElement(
                com.ragul84.hypurr.model.RoutineScheduleDraft.serializer(), schedule))
            put("timeoutSeconds", timeoutSeconds)
            enabled?.let { put("enabled", it) }
        })
        return reply.routine
    }

    suspend fun setRoutineEnabled(botId: String, id: String, enabled: Boolean) {
        call<kotlinx.serialization.json.JsonObject>("setRoutineEnabled", buildJsonObject {
            put("botId", botId); put("id", id); put("enabled", enabled)
        })
    }

    suspend fun deleteRoutine(botId: String, id: String) {
        call<kotlinx.serialization.json.JsonObject>("deleteRoutine", buildJsonObject {
            put("botId", botId); put("id", id)
        })
    }

    suspend fun runRoutine(botId: String, id: String) {
        call<kotlinx.serialization.json.JsonObject>("runRoutine", buildJsonObject {
            put("botId", botId); put("id", id)
        }, timeoutMs = 10_000)
    }

    suspend fun routineWebhook(botId: String, id: String, rotate: Boolean = false): com.ragul84.hypurr.model.RoutineWebhook =
        call("routineWebhook", buildJsonObject {
            put("botId", botId); put("id", id); put("rotate", rotate)
        })

    fun events(since: Long): Flow<HostEvent> = transport.events(since).mapNotNull(HostEvent::parse)
}


/** Opens channels to computers (§7.5) and pairs new ones from a QR (§4.1). */
object HostConnector {
    fun connect(
        computer: Computer,
        identity: DeviceIdentity,
        scope: CoroutineScope,
        dial: Dialer = OkHttpSocket.dial,
        useRelay: () -> Boolean = { true },
    ): ChannelTransport =
        ChannelTransport(computer, identity, scope, dial = dial, useRelay = useRelay).also { it.start() }

    /**
     * Proves the QR's one-time code inside a channel to the QR's host key; the host then authorizes
     * this device and closes with `4100 paired`.
     */
    suspend fun pair(
        pairing: Pairing,
        identity: DeviceIdentity,
        deviceName: String,
        scope: CoroutineScope,
        dial: Dialer = OkHttpSocket.dial,
    ): Computer {
        val transport = ChannelTransport(pairing.computer, identity, scope, pairingCode = pairing.code, dial = dial)
        transport.start()
        try {
            val res = transport.call("pair", buildJsonObject {
                put("code", pairing.code)
                put("name", deviceName)
                put("platform", "android")
            }, timeoutMs = 30_000).jsonObject
            if (res.str("computerId") != pairing.computer.id) {
                throw HostException(HostException.Kind.Unauthorized, ChannelTransport.IDENTITY_CHANGED)
            }
            val name = res.str("name")?.takeIf { it.isNotEmpty() } ?: pairing.computer.name
            return pairing.computer.copy(name = name)
        } catch (e: HostException) {
            throw if (transport.needsUpgrade) HostException.upgrade() else e
        } finally {
            transport.shutdown()
        }
    }
}
