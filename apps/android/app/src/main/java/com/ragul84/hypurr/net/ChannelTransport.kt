package com.ragul84.hypurr.net

import com.ragul84.hypurr.crypto.B64
import com.ragul84.hypurr.crypto.RelayCrypto
import com.ragul84.hypurr.crypto.RelayCryptoException
import com.ragul84.hypurr.crypto.b64url
import com.ragul84.hypurr.data.DeviceIdentity
import com.ragul84.hypurr.model.Computer
import com.ragul84.hypurr.model.Hello
import com.ragul84.hypurr.model.HypurrJson
import com.ragul84.hypurr.model.Pairing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

enum class Route { Direct, Relay }

sealed interface LinkState {
    data object Connecting : LinkState
    data class Ready(val route: Route) : LinkState
    data class HostOffline(val lastSeen: Long?) : LinkState
    data class Unauthorized(val message: String) : LinkState
    data class Failed(val message: String) : LinkState
}

class HostException(val kind: Kind, message: String, val status: Int = 0) : Exception(message) {
    enum class Kind { Unreachable, Offline, Unauthorized, UpgradeRequired, Http }

    companion object {
        fun unreachable() = HostException(Kind.Unreachable, "Can't reach your computer.")
        fun upgrade() = HostException(Kind.UpgradeRequired, "Update Hypurr on this phone or your computer.")
    }
}

/**
 * The end-to-end encrypted channel to one computer (§6, §7), a port of kit ChannelTransport.swift:
 * direct WebSocket on the LAN / Tailscale when it answers within 1.5 s, otherwise the cloud relay.
 * Inner RPC multiplexes calls and streams over one channel; the relay adds presence and the offline
 * mailbox. Reconnects by itself until [shutdown].
 */
class ChannelTransport(
    computer: Computer,
    private val identity: DeviceIdentity,
    private val scope: CoroutineScope,
    /** Pairing mode (§4.1): the only request allowed is `pair`; the host then closes with 4100. */
    private val pairingCode: String? = null,
    private val dial: Dialer = OkHttpSocket.dial,
    private val directBudgetMs: Long = 1500,
    /** "Connect from anywhere": off means direct (LAN / Tailscale) only, never the relay. */
    private val useRelay: () -> Boolean = { true },
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Connecting)
    val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _computer = MutableStateFlow(computer)

    /** Every merged [Computer] after an inner `hello` (§7.5 step 5); persist it. */
    val computer: StateFlow<Computer> = _computer.asStateFlow()

    @Volatile var needsUpgrade = false
        private set

    @Volatile var isStopped = false
        private set

    private class Link(val socket: ChannelSocket, val route: Route, val generation: Int) {
        var handshake: RelayCrypto.Handshake? = null
        var sealer: RelayCrypto.FrameSealer? = null
        var opener: RelayCrypto.FrameOpener? = null

        @Volatile var lastReceived = System.currentTimeMillis()
    }

    private sealed interface Outcome {
        data object Again : Outcome
        data class Retry(val reason: String) : Outcome
        data class Wait(val shown: LinkState) : Outcome
        data class Stop(val final: LinkState) : Outcome
    }

    private val lock = Any()
    @Volatile private var link: Link? = null
    private var generation = 0
    @Volatile private var closed = false
    @Volatile private var restartRequested = false
    private var refusals = 0
    private var lastSeen: Long? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var supervisor: Job? = null
    private var nextRequestId = 0L
    private val calls = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val streams = ConcurrentHashMap<Long, Channel<JsonElement>>()
    private val puts = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun start() {
        if (supervisor != null || closed) return
        supervisor = scope.launch { run() }
    }

    fun shutdown() {
        if (closed) return
        closed = true
        supervisor?.cancel()
        link?.socket?.close(1000)
        dropChannel(HostException.unreachable())
        dropMailbox()
        wake.trySend(Unit)
    }

    /** Reconnects from scratch (the app came back to the foreground, the network changed). */
    fun reconnect() {
        if (closed) return
        restartRequested = true
        link?.socket?.close(1000)
        wake.trySend(Unit)
    }

    private fun setState(new: LinkState) {
        _state.value = new
    }

    // MARK: connection loop (§7.5, §7.6)

    private suspend fun run() {
        var backoff = Backoff()
        while (!closed && scope.isActive) {
            if (_state.value is LinkState.Ready) setState(LinkState.Connecting)
            val started = System.currentTimeMillis()
            val outcome = attempt()
            if (closed) return
            if (restartRequested) {
                restartRequested = false
                backoff = Backoff()
                continue
            }
            when (outcome) {
                Outcome.Again -> backoff = Backoff()
                is Outcome.Stop -> {
                    isStopped = true
                    setState(outcome.final)
                    return
                }
                is Outcome.Retry, is Outcome.Wait -> {
                    // Stable for a minute: the next failure starts the backoff over.
                    if (System.currentTimeMillis() - started > 60_000) backoff = Backoff()
                    if (outcome is Outcome.Wait) setState(outcome.shown)
                    else if (_state.value !is LinkState.HostOffline) setState(LinkState.Failed((outcome as Outcome.Retry).reason))
                    withTimeoutOrNull(backoff.next()) {
                        while (!closed && !restartRequested) wake.receive()
                    }
                    restartRequested = false
                }
            }
        }
    }

    private suspend fun attempt(): Outcome {
        val c = _computer.value.let { if (useRelay()) it else it.copy(cloud = null) }
        val direct = c.urls.mapNotNull(::channelUrl)
        if (direct.isNotEmpty()) {
            when (val r = raceDirect(direct)) {
                is DirectResult.Connected -> return serve(r.socket, Route.Direct, r.keys)
                is DirectResult.Ended -> return r.outcome
                is DirectResult.Rejected -> {
                    // A plaintext `reject` isn't signed: with a relay, ask the pinned computer through it.
                    if (c.cloud == null) {
                        val end = r.outcome
                        if (pairingCode != null) return end
                        return if (end is Outcome.Stop) Outcome.Wait(end.final) else end
                    }
                }
                DirectResult.None -> Unit
            }
        }
        val cloud = c.cloud ?: return Outcome.Retry(UNREACHABLE)
        val socket = try {
            dial(relayEndpoint(cloud))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketRefused) {
            return refusalOutcome(e.status)
        } catch (e: Exception) {
            return Outcome.Retry(UNREACHABLE)
        }
        return serve(socket, Route.Relay, null)
    }

    /** `GET {cloud}/v1/relay/device/{computerId}?v=1[&pair=<offerId>]`, signed with the device key. */
    internal fun relayEndpoint(cloud: String): Endpoint.Relay {
        var query = "v=1"
        pairingCode?.let(B64::decode)?.let { query += "&pair=${RelayCrypto.offerId(it)}" }
        val url = URI("${cloud.trimEnd('/')}/v1/relay/device/${_computer.value.id}?$query")
        val sig = identity.signatureHeader("GET", RelayCrypto.authority(url), RelayCrypto.pathAndQuery(url), ByteArray(0))
        return Endpoint.Relay(url.toString(), sig)
    }

    private sealed interface DirectResult {
        class Connected(val socket: ChannelSocket, val keys: RelayCrypto.ChannelKeys) : DirectResult
        /** Authenticated (the pinned key signed it): e.g. the identity changed. */
        class Ended(val outcome: Outcome) : DirectResult
        /** An unauthenticated `reject` from some address. */
        class Rejected(val outcome: Outcome) : DirectResult
        data object None : DirectResult
    }

    /** Every direct candidate in parallel; the first finished handshake within the budget wins. */
    private suspend fun raceDirect(urls: List<String>): DirectResult = coroutineScope {
        val results = Channel<DirectResult>(Channel.UNLIMITED)
        val jobs = urls.map { url -> launch { results.send(directHandshake(url)) } }
        var winner: DirectResult = DirectResult.None
        var rejection: DirectResult.Rejected? = null
        withTimeoutOrNull(directBudgetMs) {
            var failed = 0
            while (failed < urls.size) {
                when (val r = results.receive()) {
                    DirectResult.None -> failed++
                    is DirectResult.Rejected -> {
                        failed++
                        if (rejection == null) rejection = r
                    }
                    else -> {
                        winner = r
                        break
                    }
                }
            }
        }
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        results.close()
        // A slower address that connected anyway: not needed.
        for (late in results) if (late is DirectResult.Connected) late.socket.close(1000)
        if (winner == DirectResult.None) rejection ?: DirectResult.None else winner
    }

    private suspend fun directHandshake(url: String): DirectResult {
        val socket = try {
            dial(Endpoint.Direct(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DirectResult.None
        }
        var handedOff = false
        try {
            val c = _computer.value
            val hs = RelayCrypto.Handshake(c.signKey, c.id, identity.deviceKey)
            socket.send(helloMessage(hs))
            while (true) {
                val wire = parse(socket.receive()) ?: continue
                when (wire.str("t")) {
                    "welcome" -> {
                        val ek = wire.str("ek")?.let(B64::decode)
                        val sig = wire.str("sig")?.let(B64::decode)
                        if (ek == null || sig == null) {
                            socket.close(4002)
                            return DirectResult.None
                        }
                        return try {
                            val keys = hs.finish(ek, sig)
                            handedOff = true
                            DirectResult.Connected(socket, keys)
                        } catch (e: RelayCryptoException) {
                            socket.close(4001)
                            DirectResult.Ended(Outcome.Stop(LinkState.Unauthorized(IDENTITY_CHANGED)))
                        }
                    }
                    "reject" -> return rejection(wire)?.let { DirectResult.Rejected(it) } ?: DirectResult.None
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DirectResult.None
        } finally {
            if (!handedOff) socket.close(1000)
        }
    }

    /** What a `reject` means, or null when a plain retry may succeed (rate limits, clock skew). */
    private fun rejection(wire: JsonObject): Outcome? {
        val message = wire.str("message")?.takeIf { it.isNotEmpty() }
        return when (wire.str("code")) {
            "unauthorized", "revoked" -> Outcome.Stop(LinkState.Unauthorized(message ?: "This device isn't allowed on that computer anymore."))
            "leaseExpired" -> Outcome.Wait(LinkState.Unauthorized(message ?: "Your computer couldn't confirm this device's access. Retrying."))
            "unsupportedVersion" -> upgrade()
            "pairingClosed" -> Outcome.Stop(LinkState.Failed(message ?: PAIRING_EXPIRED))
            else -> null
        }
    }

    private fun helloMessage(hs: RelayCrypto.Handshake): String = buildJsonObject {
        put("t", "hello")
        put("v", 1)
        put("dk", identity.publicKey)
        put("ek", hs.ekD.b64url())
        put("n", hs.n.b64url())
        put("sig", identity.sign(hs.signInput).b64url())
        if (pairingCode != null) put("pair", true)
    }.toString()

    /** Runs one socket until it ends. */
    private suspend fun serve(socket: ChannelSocket, route: Route, keys: RelayCrypto.ChannelKeys?): Outcome {
        val current = synchronized(lock) {
            generation += 1
            Link(socket, route, generation).also { link = it }
        }
        val heartbeat = scope.launch { heartbeat(current) }
        if (keys != null) install(current, keys)
        if (closed || restartRequested) socket.close(1000)
        val end: Outcome = try {
            var result: Outcome? = null
            while (result == null) result = handle(current, socket.receive())
            socket.close(1000)
            result
        } catch (e: SocketClosed) {
            closeOutcome(e.code)
        } catch (e: SocketRefused) {
            refusalOutcome(e.status)
        } catch (e: CancellationException) {
            socket.close(1000)
            throw e
        } catch (e: Exception) {
            Outcome.Retry(UNREACHABLE)
        }
        heartbeat.cancel()
        synchronized(lock) { if (link === current) link = null }
        dropChannel(HostException.unreachable())
        dropMailbox()
        return end
    }

    private fun closeOutcome(code: Int): Outcome = when (code) {
        4001, 4003 -> Outcome.Stop(LinkState.Unauthorized("This device isn't allowed on ${_computer.value.name} anymore."))
        4410 -> Outcome.Stop(LinkState.Failed(PAIRING_EXPIRED))
        4400 -> upgrade()
        // Paired: pairing mode is done; otherwise reconnect normally.
        4100 -> if (pairingCode == null) Outcome.Again else Outcome.Stop(LinkState.Failed("Paired."))
        4011 -> Outcome.Again
        else -> Outcome.Retry(UNREACHABLE)
    }

    private fun refusalOutcome(httpStatus: Int): Outcome = when (httpStatus) {
        // Right after pairing the device can reach the relay before the host's new ACL does.
        403 -> if (++refusals <= 3) Outcome.Retry(UNREACHABLE)
        else Outcome.Stop(LinkState.Unauthorized("This device isn't allowed on ${_computer.value.name} anymore."))
        426 -> upgrade()
        else -> Outcome.Retry(UNREACHABLE)
    }

    private fun upgrade(): Outcome {
        needsUpgrade = true
        return Outcome.Stop(LinkState.Failed(UPGRADE))
    }

    private suspend fun heartbeat(current: Link) {
        // Direct: OkHttp answers the host's pings and pings itself. Relay: we ping the DO every 30 s.
        if (current.route == Route.Direct) return
        while (true) {
            delay(30_000)
            if (link !== current) return
            if (System.currentTimeMillis() - current.lastReceived > 75_000) {
                current.socket.close(1011)
                return
            }
            runCatching { current.socket.send("""{"t":"ping"}""") }
        }
    }

    // MARK: incoming

    /** Handles one socket message; returns an outcome when the socket must end. */
    private fun handle(current: Link, text: String): Outcome? {
        if (link !== current) return null
        current.lastReceived = System.currentTimeMillis()
        val wire = parse(text) ?: return null
        when (wire.str("t")) {
            "presence" -> {
                if (wire["online"]?.jsonPrimitive?.booleanOrNull == true) {
                    // The host (re)joined: any previous channel is gone; start a fresh handshake.
                    dropChannel(HostException.unreachable())
                    return startHandshake(current)
                }
                lastSeen = wire["lastSeenAt"]?.jsonPrimitive?.longOrNull
                dropChannel(HostException(HostException.Kind.Offline, "Your computer is offline."))
                synchronized(lock) {
                    current.handshake = null
                    current.sealer = null
                    current.opener = null
                }
                setState(LinkState.HostOffline(lastSeen))
            }
            "welcome" -> {
                val hs = current.handshake
                val ek = wire.str("ek")?.let(B64::decode)
                val sig = wire.str("sig")?.let(B64::decode)
                if (hs == null || ek == null || sig == null) return Outcome.Retry(UNREACHABLE)
                current.handshake = null
                try {
                    install(current, hs.finish(ek, sig))
                } catch (e: RelayCryptoException) {
                    return Outcome.Stop(LinkState.Unauthorized(IDENTITY_CHANGED))
                }
            }
            "reject" -> return rejection(wire) ?: Outcome.Retry(wire.str("message") ?: UNREACHABLE)
            "f" -> {
                val c = wire["c"]?.jsonPrimitive?.longOrNull
                val d = wire.str("d")?.let(B64::decode)
                val opener = current.opener
                if (opener == null || c == null || d == null) {
                    current.socket.close(4002)
                    return Outcome.Retry("Protocol error")
                }
                try {
                    opener.open(c, d)?.let(::handleInner)
                } catch (e: RelayCryptoException) {
                    current.socket.close(if (e.reason == RelayCryptoException.Reason.TooLarge) 4013 else 4002)
                    return Outcome.Retry("Protocol error")
                }
            }
            "mbox.ok" -> wire.str("nonce")?.let { puts.remove(it)?.complete(Unit) }
            "mbox.err" -> wire.str("nonce")?.let {
                puts.remove(it)?.completeExceptionally(MailboxException(wire.str("code") ?: "invalid"))
            }
        }
        return null
    }

    private fun startHandshake(current: Link): Outcome? {
        val c = _computer.value
        val hs = try {
            RelayCrypto.Handshake(c.signKey, c.id, identity.deviceKey)
        } catch (e: RelayCryptoException) {
            return Outcome.Stop(LinkState.Unauthorized(IDENTITY_CHANGED))
        }
        synchronized(lock) {
            current.handshake = hs
            current.sealer = null
            current.opener = null
        }
        if (_state.value is LinkState.Ready || _state.value is LinkState.HostOffline) setState(LinkState.Connecting)
        current.socket.send(helloMessage(hs))
        // No welcome within 10 s: give up on this socket.
        scope.launch {
            delay(10_000)
            if (link === current && current.handshake === hs) current.socket.close(1000)
        }
        return null
    }

    private fun install(current: Link, keys: RelayCrypto.ChannelKeys) {
        synchronized(lock) {
            current.sealer = RelayCrypto.FrameSealer(keys.d2h)
            current.opener = RelayCrypto.FrameOpener(keys.h2d)
        }
        refusals = 0
        setState(LinkState.Ready(current.route))
        if (pairingCode == null) scope.launch { refreshComputer() }
    }

    /** Inner RPC (§6.5): `ok`/`err` answer calls, `ev`/`end`/`err` feed streams. */
    private fun handleInner(message: ByteArray) {
        val obj = runCatching { HypurrJson.parseToJsonElement(message.decodeToString()).jsonObject }.getOrNull() ?: return
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return
        when {
            "ev" in obj -> streams[id]?.trySend(obj.getValue("ev"))
            obj["end"]?.jsonPrimitive?.booleanOrNull == true -> streams.remove(id)?.close()
            obj["err"] is JsonObject -> {
                val err = obj.getValue("err").jsonObject
                val status = err["status"]?.jsonPrimitive?.intOrNull ?: 500
                val error = HostException(HostException.Kind.Http, err.str("message") ?: "Host error $status", status)
                calls.remove(id)?.completeExceptionally(error) ?: streams.remove(id)?.close(error)
            }
            "ok" in obj -> calls.remove(id)?.complete(obj.getValue("ok"))
        }
    }

    /** Ends everything riding on the channel (not the mailbox, which lives on the relay socket). */
    private fun dropChannel(error: Exception) {
        calls.keys.toList().forEach { calls.remove(it)?.completeExceptionally(error) }
        // Route change or re-handshake: the store resubscribes from its own rev.
        streams.keys.toList().forEach { streams.remove(it)?.close(HostException.unreachable()) }
    }

    private fun dropMailbox() {
        puts.keys.toList().forEach { puts.remove(it)?.completeExceptionally(HostException.unreachable()) }
    }

    // MARK: inner hello (§7.5 step 5)

    private suspend fun refreshComputer() {
        val hello = runCatching { HypurrJson.decodeFromJsonElement<Hello>(call("hello")) }.getOrNull() ?: return
        merge(hello)
    }

    internal fun merge(hello: Hello) {
        val c = _computer.value
        // The welcome signature already proved the key; a hello naming another computer is ignored.
        if ((hello.computerId != null && hello.computerId != c.id) || (hello.signKey != null && hello.signKey != c.signKey)) return
        _computer.value = c.copy(
            name = hello.name.ifEmpty { c.name },
            boxKey = hello.boxKey?.takeIf { B64.decode(it)?.size == 32 } ?: c.boxKey,
            urls = hello.urls?.filter(Pairing::isDirectUrl) ?: c.urls,
            cloud = hello.cloud?.takeIf(Pairing::isCloudUrl),
            device = hello.device ?: c.device,
        )
    }

    // MARK: calls and streams

    private fun nextId(): Long = synchronized(lock) {
        nextRequestId = if (nextRequestId >= 0xFFFF_FFFFL) 1 else nextRequestId + 1
        nextRequestId
    }

    /** Waits (bounded) while connecting; throws what the link state means otherwise. */
    suspend fun awaitReady(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (closed) throw HostException.unreachable()
            when (val s = _state.value) {
                is LinkState.Ready -> if (link?.sealer != null) return else delay(20)
                LinkState.Connecting -> {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw HostException.unreachable()
                    withTimeoutOrNull(left) { _state.first { it != s } }
                }
                is LinkState.HostOffline -> throw HostException(HostException.Kind.Offline, "Your computer is offline.")
                is LinkState.Unauthorized -> throw HostException(HostException.Kind.Unauthorized, s.message)
                is LinkState.Failed -> throw if (needsUpgrade) HostException.upgrade() else HostException.unreachable()
            }
            if (System.currentTimeMillis() > deadline) throw HostException.unreachable()
        }
    }

    suspend fun call(method: String, body: JsonElement = JsonObject(emptyMap()), timeoutMs: Long = 20_000): JsonElement {
        awaitReady(timeoutMs)
        val id = nextId()
        val reply = CompletableDeferred<JsonElement>()
        calls[id] = reply
        try {
            sendInner(buildJsonObject {
                put("id", id)
                put("m", method)
                put("b", body)
            })
        } catch (e: Exception) {
            calls.remove(id)
            throw e
        }
        return try {
            withTimeout(timeoutMs) { reply.await() }
        } catch (e: CancellationException) {
            // Timed out or cancelled: the host drops the answer (an already started mutation still runs).
            if (calls.remove(id) != null) runCatching { sendInner(cancelMessage(id)) }
            if (e is TimeoutCancellationException) throw HostException.unreachable()
            throw e
        }
    }

    private fun cancelMessage(id: Long) = buildJsonObject {
        put("id", id)
        put("cancel", true)
    }

    private fun sendInner(message: JsonObject) {
        synchronized(lock) {
            val current = link ?: throw HostException.unreachable()
            val sealer = current.sealer ?: throw HostException.unreachable()
            for ((c, d) in sealer.seal(message.toString().toByteArray())) {
                current.socket.send("""{"t":"f","c":$c,"d":"${d.b64url()}"}""")
            }
            // Counters must never wrap: replace the channel (§3.5).
            if (sealer.exhausted) {
                restartRequested = true
                current.socket.close(4011)
            }
        }
    }

    /** A terminal's output stream (`sub: term`): scrollback, then live, ending after exit. */
    fun termEvents(termId: String): Flow<JsonElement> = channelFlow {
        awaitReady(10_000)
        val id = nextId()
        val inbox = Channel<JsonElement>(Channel.UNLIMITED)
        streams[id] = inbox
        try {
            sendInner(buildJsonObject {
                put("id", id)
                put("sub", "term")
                put("b", buildJsonObject { put("term", termId) })
            })
            for (ev in inbox) send(ev)
        } finally {
            if (streams.remove(id) != null) runCatching { sendInner(cancelMessage(id)) }
        }
    }

    /** The host's event stream (`sub: events`), from `since` on; ends when the channel drops. */
    fun events(since: Long, client: String = CLIENT): Flow<JsonElement> = channelFlow {
        awaitReady(10_000)
        val id = nextId()
        val inbox = Channel<JsonElement>(Channel.UNLIMITED)
        streams[id] = inbox
        try {
            sendInner(buildJsonObject {
                put("id", id)
                put("sub", "events")
                put("b", buildJsonObject {
                    put("since", since)
                    put("client", client)
                })
            })
            for (ev in inbox) send(ev)
        } finally {
            if (streams.remove(id) != null) runCatching { sendInner(cancelMessage(id)) }
        }
    }

    // MARK: mailbox (§6.4, §7.4)

    /** Queues a message in the relay for an offline computer; it runs when the computer is back. */
    suspend fun enqueue(botId: String, text: String, clientNonce: String, threadId: String? = null) {
        val c = _computer.value
        val bk = c.boxKey?.let(B64::decode)?.takeIf { it.size == 32 }
        if (pairingCode != null || bk == null) throw HostException(HostException.Kind.Offline, "Your computer is offline.")
        val current = link?.takeIf { it.route == Route.Relay } ?: throw HostException.unreachable()
        val inner = buildJsonObject {
            put("m", "send")
            put("b", buildJsonObject {
                put("botId", botId)
                put("text", text)
                put("clientNonce", clientNonce)
                threadId?.let { put("threadId", it) }
            })
            put("ts", System.currentTimeMillis())
        }
        // A fresh ephemeral key on every seal, retries included (§6.4 MUST).
        val sealed = RelayCrypto.sealMailbox(inner.toString().toByteArray(), bk, c.id, identity.deviceKey, clientNonce, identity::sign)
        val done = CompletableDeferred<Unit>()
        puts[clientNonce] = done
        current.socket.send(buildJsonObject {
            put("t", "mbox.put")
            put("nonce", clientNonce)
            put("d", sealed.blob.b64url())
        }.toString())
        try {
            withTimeout(15_000) { done.await() }
        } catch (e: TimeoutCancellationException) {
            puts.remove(clientNonce)
            throw HostException.unreachable()
        }
    }

    companion object {
        const val CLIENT = "android"
        const val IDENTITY_CHANGED = "This computer's identity changed. Pair it again to keep using it."
        const val UNREACHABLE = "Can't reach your computer."
        const val UPGRADE = "Update Hypurr on this phone or your computer."
        const val PAIRING_EXPIRED = "This pairing code expired. Show a new one on the computer."

        /** `http://h:p` → `ws://h:p/channel?v=1`. */
        fun channelUrl(base: String): String? {
            val u = runCatching { URI(base) }.getOrNull() ?: return null
            val scheme = when (u.scheme) {
                "https" -> "wss"
                "http" -> "ws"
                else -> return null
            }
            val host = u.host ?: return null
            val port = if (u.port == -1) "" else ":${u.port}"
            return "$scheme://$host$port/channel?v=1"
        }

        internal fun parse(text: String): JsonObject? =
            runCatching { HypurrJson.parseToJsonElement(text).jsonObject }.getOrNull()
    }
}

class MailboxException(val code: String) : Exception("mailbox: $code")

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/** Reconnect delays: from 1 s doubling to 30 s, full jitter (§7.6). */
class Backoff {
    private var attempt = 0

    fun next(): Long {
        val cap = minOf(30.0, Math.pow(2.0, attempt.toDouble()))
        attempt++
        return (Random.nextDouble(0.0, cap) * 1000).toLong().coerceAtLeast(100)
    }
}
