package com.ragul84.hypurr

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.graphics.asImageBitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import com.ragul84.hypurr.data.HypurrStore
import com.ragul84.hypurr.data.PickedFile
import com.ragul84.hypurr.model.Attachment
import com.ragul84.hypurr.model.ScreenState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.screen.ScreenPhase
import com.ragul84.hypurr.screen.ScreenSession
import com.ragul84.hypurr.ui.screens.AttachKind
import com.ragul84.hypurr.ui.screens.BotEditorScreen
import com.ragul84.hypurr.ui.screens.TerminalPhase
import com.ragul84.hypurr.ui.screens.TerminalScreen
import com.ragul84.hypurr.ui.screens.TerminalUiState
import com.ragul84.hypurr.ui.screens.bytesToBase64
import com.ragul84.hypurr.ui.screens.stripAnsi
import android.util.Base64
import com.ragul84.hypurr.ui.screens.BotEditorState
import com.ragul84.hypurr.ui.screens.ComposerFiles
import com.ragul84.hypurr.ui.screens.ScreenScreen
import com.ragul84.hypurr.ui.screens.ScreenUiState
import com.ragul84.hypurr.ui.screens.usableBackends
import android.webkit.MimeTypeMap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import com.ragul84.hypurr.model.Pairing
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.ui.screens.BotListScreen
import com.ragul84.hypurr.ui.screens.HomeTab
import com.ragul84.hypurr.ui.screens.TasksScreen
import com.ragul84.hypurr.ui.screens.TriageScreen
import com.ragul84.hypurr.ui.screens.StandupScreen
import com.ragul84.hypurr.ui.screens.TicketScreen
import com.ragul84.hypurr.ui.screens.QaScreen
import com.ragul84.hypurr.ui.screens.QaStep
import com.ragul84.hypurr.ui.screens.TicketStep
import com.ragul84.hypurr.ui.screens.SpendScreen
import com.ragul84.hypurr.ui.screens.NeedsYouAsk
import com.ragul84.hypurr.ui.screens.BuiltinInstallPrompt
import com.ragul84.hypurr.ui.screens.ChatScreen
import com.ragul84.hypurr.ui.screens.NewTaskScreen
import com.ragul84.hypurr.ui.screens.NewTaskUiState
import com.ragul84.hypurr.model.TaskTemplate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.delay
import com.ragul84.hypurr.ui.screens.PairingScreen
import com.ragul84.hypurr.ui.screens.ProjectFolderPickerScreen
import com.ragul84.hypurr.ui.screens.OnboardingScreen
import com.ragul84.hypurr.data.UserProfile
import com.ragul84.hypurr.ui.screens.PairingUiState
import com.ragul84.hypurr.ui.screens.SettingsScreen
import com.ragul84.hypurr.ui.screens.AdminTab
import com.ragul84.hypurr.ui.screens.TeamAdminScreen
import com.ragul84.hypurr.ui.screens.ItKitScreen
import com.ragul84.hypurr.ui.Share
import com.ragul84.hypurr.ui.screens.TeamAdminUiState
import com.ragul84.hypurr.ui.screens.SettingsUiState
import com.ragul84.hypurr.ui.screens.rosterOrder
import com.ragul84.hypurr.ui.theme.HypurrTheme
import com.ragul84.hypurr.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as HypurrApp
    private val pendingLink = MutableStateFlow<String?>(null)
    private val openBot = MutableStateFlow<String?>(null)
    /** A log, stack trace, text file or screenshot shared into Hypurr (opens Fix from a log). */
    private val sharedLog = MutableStateFlow<SharedLog?>(null)
    private val triageImage = MutableStateFlow<Uri?>(null)
    private val pickTriageImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { triageImage.value = it }
    }
    private val scanned = MutableStateFlow<String?>(null)

    private val scan = registerForActivityResult(ScanContract()) { result -> result.contents?.let { scanned.value = it } }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    /** A screenshot or photo picked for the New task screen. */
    private val picked = MutableStateFlow<PickedFile?>(null)
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { readFile(it, "screenshot.jpg") }?.let { picked.value = it }
    }

    /** Photos and files picked for the open chat's composer. */
    private val chatPicked = MutableStateFlow<List<PickedFile>>(emptyList())
    private val pickChatMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        addChatFiles(uris.mapNotNull { readFile(it, "photo.jpg") })
    }
    private val pickChatFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addChatFiles(uris.mapNotNull { readFile(it, "file") })
    }

    private fun addChatFiles(files: List<PickedFile>) {
        if (files.isNotEmpty()) chatPicked.value = chatPicked.value + files
    }

    /** An image on the clipboard, if there is one. */
    private fun clipboardImage(): PickedFile? {
        val item = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        return item?.uri?.takeIf { contentResolver.getType(it)?.startsWith("image/") == true }?.let { readFile(it, "pasted.png") }
    }

    /** Hands a chat's file to another app: written to the cache, shared read-only through the FileProvider. */
    private fun openFile(a: Attachment, bytes: ByteArray) {
        val dir = java.io.File(cacheDir, "shared/${a.id.filter { it.isLetterOrDigit() || it == '-' }}").apply { mkdirs() }
        val file = java.io.File(dir, a.name.substringAfterLast('/').ifEmpty { "file" })
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(view, a.name))
    }

    /** Reads a picked or pasted file (the system photo picker or the clipboard; no storage permission). */
    private fun readFile(uri: Uri, fallback: String): PickedFile? = runCatching {
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        } ?: fallback
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > MAX_FILE) return null
        PickedFile(name, bytes, mime)
    }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val store = app.store
            val mode by store.themeMode.collectAsState()
            val dynamic by store.dynamicColor.collectAsState()
            HypurrTheme(mode, dynamic) {
                val voiceProfile by store.userProfile.collectAsState()
                androidx.compose.runtime.CompositionLocalProvider(
                    com.ragul84.hypurr.voice.LocalVoiceSettings provides com.ragul84.hypurr.voice.VoiceSettings(
                        com.ragul84.hypurr.voice.VoiceLanguage.of(voiceProfile.voiceLanguage), voiceProfile.explainInMyLanguage),
                ) {
                    var startFree by remember { mutableStateOf(false) }
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.ragul84.hypurr.ui.LocalStartFree provides { startFree = true },
                    ) { App(store) }
                    if (startFree) com.ragul84.hypurr.ui.StartFreeDialog(
                        start = { store.ownFreeStart() },
                        finish = { store.ownFreeFinish(it) },
                        onDone = { startFree = false },
                        onDismiss = { startFree = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        val store = app.store
        if (store.computer.value != null && store.link.value !is LinkState.Ready) store.reconnect()
    }

    private fun handle(intent: Intent?) {
        // A pairing link fills the pairing screen; the user still confirms it with Pair.
        intent?.data?.takeIf { it.scheme == "hypurr" }?.let { pendingLink.value = it.toString() }
        intent?.getStringExtra(EXTRA_BOT)?.let { openBot.value = it }
        if (intent?.action == Intent.ACTION_SEND) {
            val type = intent.type.orEmpty()
            @Suppress("DEPRECATION")
            val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            sharedLog.value = when {
                type.startsWith("image/") && stream != null -> SharedLog(image = stream)
                stream != null -> SharedLog(text = readText(stream), name = readFile(stream, "log.txt")?.name)
                !text.isNullOrBlank() -> SharedLog(text = text)
                else -> null
            }
        }
    }

    /** A shared text file, up to 1 MB (logs beyond that keep their end, where errors are). */
    private fun readText(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            val tail = if (bytes.size > 1_000_000) bytes.copyOfRange(bytes.size - 1_000_000, bytes.size) else bytes
            String(tail, Charsets.UTF_8)
        }
    }.getOrNull()

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Composable
    private fun App(store: HypurrStore) {
        val computer by store.computer.collectAsState()
        val scope = rememberCoroutineScope()
        var pairing by remember { mutableStateOf(PairingUiState()) }
        var justPaired by remember { mutableStateOf(false) }
        val link by pendingLink.collectAsState()
        val scan by scanned.collectAsState()
        var setupOpen by remember { mutableStateOf(false) }
        var setupInstall by remember { mutableStateOf(com.ragul84.hypurr.ui.screens.AgentInstallState()) }
        fun startScan() {
            this.scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                .setOrientationLocked(false).setPrompt(getString(R.string.scan_prompt)))
        }
        @Composable
        fun SetupDialog(hello: com.ragul84.hypurr.model.Hello?) {
            if (!setupOpen) return
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { setupOpen = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                com.ragul84.hypurr.ui.screens.AgentSetupPanel(
                    hello, setupInstall,
                    onInstall = {
                        setupInstall = com.ragul84.hypurr.ui.screens.AgentInstallState(busy = true)
                        lifecycleScope.launch {
                            setupInstall = runCatching { store.installBuiltinAgent() }.fold(
                                { com.ragul84.hypurr.ui.screens.AgentInstallState(done = true) },
                                { com.ragul84.hypurr.ui.screens.AgentInstallState(error = it.message ?: "the computer didn't say why") },
                            )
                        }
                    },
                    onPair = { setupOpen = false; startScan() },
                    onCheckAgain = { store.reconnect() },
                    onClose = { setupOpen = false },
                )
            }
        }

        fun pair(text: String) {
            val parsed = runCatching { Pairing.parse(text) }
            pairing = pairing.copy(link = text, busy = parsed.isSuccess, error = parsed.exceptionOrNull()?.message,
                computerName = parsed.getOrNull()?.computer?.name)
            if (parsed.isFailure) return
            scope.launch {
                pairing = try {
                    store.pair(text)
                    justPaired = true
                    askForNotifications()
                    PairingUiState()
                } catch (e: Exception) {
                    pairing.copy(busy = false, error = e.message ?: "Pairing failed.")
                }
            }
        }
        link?.let {
            pendingLink.value = null
            pairing = pairing.copy(link = it, error = null)
        }
        scan?.let {
            scanned.value = null
            pair(it)
        }

        val profile by store.userProfile.collectAsState()
        if (!profile.completed) {
            OnboardingScreen(
                initial = profile,
                onFinished = { store.setUserProfile(it) },
                onSkipAll = { store.setUserProfile(UserProfile(completed = true, skipped = true)) },
            )
            return
        }

        val current = computer
        if (current == null) {
            PairingScreen(
                pairing,
                onLinkChange = { pairing = pairing.copy(link = it, error = null) },
                onScan = { startScan() },
                onInstallAgent = { setupOpen = true },
                onPaste = {
                    val clip = getSystemService(ClipboardManager::class.java).primaryClip
                    clip?.getItemAt(0)?.coerceToText(this)?.toString()?.let { pairing = pairing.copy(link = it.trim(), error = null) }
                },
                onPair = { pair(pairing.link) },
            )
            SetupDialog(null)
            return
        }

        val linkState by store.link.collectAsState()
        val bots by store.bots.collectAsState()
        val entries by store.entries.collectAsState()
        val synced by store.synced.collectAsState()
        val requested by openBot.collectAsState()
        val you by store.you.collectAsState()
        var screen by rememberSaveable { mutableStateOf("list") }
        // The new-task draft survives a trip to the folder picker.
        var taskDraft by remember { mutableStateOf<NewTaskUiState?>(null) }
        var guide by remember { mutableStateOf(com.ragul84.hypurr.ui.screens.GuideUiState()) }
        // Learning mode per task: cards, quiz, and tasks where the panel was hidden.
        var learnCards by remember { mutableStateOf(mapOf<String, List<com.ragul84.hypurr.model.LearnCard>>()) }
        var quizzes by remember { mutableStateOf(mapOf<String, com.ragul84.hypurr.model.Quiz>()) }
        var learnHidden by remember { mutableStateOf(setOf<String>()) }
        var proofs by remember { mutableStateOf(mapOf<String, com.ragul84.hypurr.model.Proof>()) }
        var proofImages by remember { mutableStateOf(mapOf<String, androidx.compose.ui.graphics.ImageBitmap>()) }
        var proofLoading by remember { mutableStateOf(setOf<String>()) }
        var proofBusy by remember { mutableStateOf(setOf<String>()) }
        var qaDraft by remember { mutableStateOf<com.ragul84.hypurr.ui.screens.QaUiState?>(null) }
        var traceDraft by remember { mutableStateOf<com.ragul84.hypurr.ui.screens.TraceMapUiState?>(null) }
        var ticketDraft by remember { mutableStateOf<com.ragul84.hypurr.ui.screens.TicketUiState?>(null) }
        val projectFolder by store.projectFolder.collectAsState()
        // Right after pairing, ask once which folder the agent may work in (can wait).
        LaunchedEffect(justPaired) {
            if (justPaired) {
                justPaired = false
                if (projectFolder == null) screen = "folderpick:pair"
            }
        }
        val incoming by sharedLog.collectAsState()
        LaunchedEffect(incoming) { if (incoming != null) screen = "triage" }
        requested?.let {
            openBot.value = null
            screen = "chat:$it"
        }
        BackHandler(screen != "list") {
            if (screen == "newtask") taskDraft = null
            screen = parentOf(screen)
        }

        AnimatedContent(
            screen,
            transitionSpec = {
                val forward = targetState != "list"
                (slideInHorizontally(Motion.offset) { if (forward) it / 3 else -it / 3 } + fadeIn(Motion.effects()))
                    .togetherWith(slideOutHorizontally(Motion.offset) { if (forward) -it / 4 else it / 4 } + fadeOut(Motion.effects()))
            },
            label = "nav",
        ) { target ->
            when {
                target.startsWith("chat:") || target.startsWith("thread:") -> {
                    val parts = target.split(':')
                    val id = parts[1]
                    val rootId = parts.getOrNull(2)
                    val bot = bots[id]
                    val root = rootId?.let { r -> entries[id]?.firstOrNull { it.id == r } }
                    if (bot == null || (rootId != null && root == null)) {
                        screen = if (bot == null) "list" else "chat:$id"
                        return@AnimatedContent
                    }
                    var draft by rememberSaveable(target) { mutableStateOf("") }
                    var files by remember(target) { mutableStateOf(listOf<PickedFile>()) }
                    val integrations by store.integrations.collectAsState()
                    val threads by store.threads.collectAsState()
                    val images by store.files.collectAsState()
                    LaunchedEffect(target) {
                        chatPicked.value = emptyList()
                        chatPicked.collect { picked ->
                            if (picked.isNotEmpty()) {
                                files = files + picked
                                chatPicked.value = emptyList()
                            }
                        }
                    }
                    LaunchedEffect(bot.task != null) { if (bot.task != null) store.loadIntegrations() }
                    val learnTask = bot.task?.takeIf { it.learning && it.id !in learnHidden }
                    LaunchedEffect(learnTask?.id, learnTask?.status, entries[id]?.size) {
                        learnTask?.let { t -> runCatching { store.learnCards(t.id) }.getOrNull()?.let { learnCards = learnCards + (t.id to it.cards) } }
                    }
                    val proofTask = bot.task?.takeIf { t ->
                        bot.status != "working" && (!t.isActive || entries[id].orEmpty().any { it.kind == "agent" } || proofs[t.id] != null)
                    }
                    LaunchedEffect(proofTask?.id, proofTask?.status, entries[id]?.size) {
                        proofTask?.let { t -> runCatching { store.proof(t.id) }.getOrNull()?.let { proofs = proofs + (t.id to it) } }
                    }
                    val proofRunning = proofTask?.let { proofs[it.id]?.status == "running" } == true
                    LaunchedEffect(proofTask?.id, proofRunning) {
                        // While it runs, check every few seconds (the host also posts a chat note when done).
                        while (proofRunning && proofTask != null) {
                            kotlinx.coroutines.delay(4_000)
                            runCatching { store.proof(proofTask.id) }.getOrNull()?.let { proofs = proofs + (proofTask.id to it) }
                            if (proofs[proofTask.id]?.status != "running") break
                        }
                    }
                    LaunchedEffect(target, synced) { if (rootId != null) store.openThread(id, rootId) else store.openChat(id) }
                    ChatScreen(
                        bot, if (rootId != null) threads[rootId].orEmpty() else entries[id].orEmpty(), draft, { draft = it },
                        onBack = { screen = parentOf(target) },
                        onSend = {
                            val text = draft
                            val sending = files
                            draft = ""
                            files = emptyList()
                            scope.launch {
                                // A failed message with files gives them back to the composer for another try.
                                runCatching { store.send(id, text, rootId, sending) }.onFailure { if (sending.isNotEmpty()) files = sending + files }
                            }
                        },
                        onStop = { scope.launch { runCatching { store.stop(id) } } },
                        onRespond = { entry, option -> scope.launch { runCatching { store.respond(entry.id, option) } } },
                        onUndo = { entry -> entry.data.checkpoint?.let { cp -> scope.launch { runCatching { store.rollback(id, cp) } } } },
                        onRollback = { cp -> scope.launch { runCatching { store.rollback(id, cp.id) } } },
                        onFinishTask = { o -> scope.launch { runCatching { store.finishTask(id, o.openPr, o.notify, o.learning) } } },
                        onSaveCheckpoint = { scope.launch { runCatching { store.saveCheckpoint(id) } } },
                        onOpenLink = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                        integrations = integrations,
                        you = you,
                        bots = bots,
                        threadRoot = root,
                        composer = ComposerFiles(files, canAttach = !bot.isGroup && you?.canAct != false, online = linkState is LinkState.Ready),
                        onAttach = { kind ->
                            when (kind) {
                                AttachKind.Photos -> pickChatMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                AttachKind.Files -> pickChatFiles.launch(arrayOf("*/*"))
                                AttachKind.Paste -> clipboardImage()?.let { files = files + it }
                            }
                        },
                        onRemoveFile = { i -> files = files.filterIndexed { j, _ -> j != i } },
                        images = images,
                        onLoadAttachment = { a -> scope.launch { store.loadAttachment(id, a) } },
                        onOpenFile = { a -> scope.launch { runCatching { openFile(a, store.fileBytes(id, a)) } } },
                        onOpenThread = { e -> screen = "thread:$id:${e.id}" },
                        onReact = { e, emoji -> scope.launch { runCatching { store.react(e.id, emoji) } } },
                        onEdit = if (you?.canAct != false) ({ screen = "edit:$id" }) else null,
                        onChangeFolder = if (you?.canAct != false && !bot.isGroup) ({ screen = "folderpick:chat:$id" }) else null,
                        learn = learnTask?.let { t ->
                            {
                                com.ragul84.hypurr.ui.screens.LearnPanel(
                                    t,
                                    (learnCards[t.id].orEmpty() + com.ragul84.hypurr.ui.screens.teachLines(entries[id].orEmpty())).distinctBy { it.concept.lowercase() },
                                    quizzes[t.id],
                                    onLoadQuiz = { scope.launch { runCatching { store.learnQuiz(t.id) }.getOrNull()?.let { quizzes = quizzes + (t.id to it) } } },
                                    onHide = { learnHidden = learnHidden + t.id },
                                )
                            }
                        },
                        proof = proofTask?.let { t ->
                            {
                                val p = proofs[t.id]
                                fun act(block: suspend () -> com.ragul84.hypurr.model.Proof?) {
                                    proofBusy = proofBusy + t.id
                                    scope.launch {
                                        runCatching { block() }.getOrNull()?.let { proofs = proofs + (t.id to it) }
                                        proofBusy = proofBusy - t.id
                                    }
                                }
                                com.ragul84.hypurr.ui.screens.ProofCard(
                                    p, proofImages.filterKeys { it.startsWith("${t.id}/") }.mapKeys { it.key.substringAfter('/') },
                                    busy = t.id in proofBusy,
                                    onShowMe = { act { store.proofStart(t.id) } },
                                    onAllow = { act { store.proofAllow(t.id) } },
                                    onLoad = { name ->
                                        val key = "${t.id}/$name"
                                        if (key !in proofImages && key !in proofLoading) {
                                            proofLoading = proofLoading + key
                                            scope.launch {
                                                val bmp = runCatching { store.proofFile(t.id, name) }.getOrNull()
                                                    ?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                                                if (bmp != null) proofImages = proofImages + (key to bmp.asImageBitmap())
                                            }
                                        }
                                    },
                                    onPlayClip = { name ->
                                        scope.launch {
                                            runCatching {
                                                val bytes = store.proofFile(t.id, name) ?: return@runCatching
                                                val dir = java.io.File(cacheDir, "shared").apply { mkdirs() }
                                                val f = java.io.File(dir, "proof-${t.id.take(8)}-$name").apply { writeBytes(bytes) }
                                                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                                                val mime = if (name.endsWith(".mp4")) "video/mp4" else "video/webm"
                                                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                            }
                                        }
                                    },
                                    onVerdict = { ok, text ->
                                        scope.launch {
                                            runCatching { store.proofVerdict(t.id, ok, text) }
                                            runCatching { store.proof(t.id) }.getOrNull()?.let { proofs = proofs + (t.id to it) }
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
                target == "bot:new" || target == "group:new" || target.startsWith("edit:") -> {
                    val hello by store.hello.collectAsState()
                    val policies by store.policies.collectAsState()
                    val editing = if (target.startsWith("edit:")) bots[target.removePrefix("edit:")] else null
                    var form by remember(target) {
                        mutableStateOf(when {
                            editing != null -> BotEditorState.of(editing)
                            target == "group:new" -> BotEditorState.newGroup()
                            else -> BotEditorState()
                        })
                    }
                    LaunchedEffect(target) {
                        store.refreshHello()
                        store.loadPolicies()
                    }
                    val usable = usableBackends(hello?.backends.orEmpty(), policies?.policies?.allowedAgents.orEmpty())
                    LaunchedEffect(usable) {
                        if (form.isNew && !form.group && form.backend == null) usable.singleOrNull()?.let { form = form.copy(backend = it.id) }
                    }
                    BotEditorScreen(
                        form, usable,
                        agentBots = bots.values.filter { !it.isGroup && !it.hidden }.sortedBy { it.name.lowercase() },
                        onChange = { form = it },
                        onSave = {
                            val body = form.toJson(editing)
                            form = form.copy(busy = true, error = null)
                            scope.launch {
                                try {
                                    val saved = if (editing == null) store.createBot(body) else store.updateBot(body)
                                    screen = "chat:${saved.id}"
                                } catch (e: Exception) {
                                    form = form.copy(busy = false, error = e.message ?: "Couldn't save.")
                                }
                            }
                        },
                        onDelete = {
                            val id = editing?.id ?: return@BotEditorScreen
                            scope.launch {
                                try {
                                    store.deleteBot(id)
                                    screen = "list"
                                } catch (e: Exception) {
                                    form = form.copy(confirmDelete = false, error = e.message ?: "Couldn't delete.")
                                }
                            }
                        },
                        onBack = { screen = parentOf(target) },
                        onBrowse = { path ->
                            scope.launch {
                                runCatching { store.listDirs(path) }
                                    .onSuccess { form = form.copy(browsing = it, error = null) }
                                    .onFailure { form = form.copy(error = it.message ?: "Couldn't list folders.") }
                            }
                        },
                        onTerminal = editing?.takeIf { !it.isGroup && it.cwd.isNotBlank() }?.let { bot ->
                            { screen = "terminal:${bot.id}" }
                        },
                    )
                }

                target.startsWith("terminal:") -> {
                    val botId = target.removePrefix("terminal:")
                    val bot = bots[botId]
                    if (bot == null || bot.isGroup) {
                        LaunchedEffect(botId) { screen = "list" }
                    } else {
                        TerminalRoute(
                            store = store,
                            title = bot.name,
                            botId = bot.id,
                            offline = linkState !is LinkState.Ready,
                            onBack = { screen = parentOf(target) },
                        )
                    }
                }

                target == "screen" -> ScreenRoute(store, current.name) { screen = "list" }
                target == "qa" -> QaRoute(store, projectFolder, qaDraft, { qaDraft = it }) { screen = it }
                target == "trace" -> TraceMapRoute(store, projectFolder, traceDraft, { traceDraft = it }) { screen = it }
                target == "ticket" -> TicketRoute(store, projectFolder, ticketDraft, { ticketDraft = it }) { screen = it }
                target == "newtask" -> {
                    val setup by store.setup.collectAsState()
                    var task by remember {
                        mutableStateOf(taskDraft ?: NewTaskUiState(project = projectFolder, agent = store.lastTaskAgent.value, model = store.lastTaskModel.value))
                    }
                    val taskHello by store.hello.collectAsState()
                    SideEffect { taskDraft = task }
                    LaunchedEffect(Unit) { store.loadSetup() }
                    // Ask the host for its pick as the user types (debounced).
                    LaunchedEffect(task.goal, task.template, task.issue, setup) {
                        if (task.routeText.isBlank() && task.template == null) return@LaunchedEffect
                        delay(500)
                        runCatching { store.route(task.routeText, task.template) }.getOrNull()?.let { task = task.copy(route = it) }
                    }
                    val file by picked.collectAsState()
                    file?.let {
                        picked.value = null
                        task = task.copy(files = task.files + it, error = null)
                    }
                    val taskCosts by store.costs.collectAsState()
                    LaunchedEffect(Unit) { store.loadCosts() }
                    NewTaskScreen(
                        task.copy(setup = setup, models = com.ragul84.hypurr.ui.screens.runnableModels(taskHello)),
                        ownFree = taskCosts?.ownFree == true,
                        onAddCredits = {
                            taskDraft = null
                            screen = "spend"
                        },
                        onChange = { task = it },
                        onStart = {
                            task = task.copy(busy = true, error = null)
                            scope.launch {
                                try {
                                    val voice = store.userProfile.value
                                    val note = com.ragul84.hypurr.voice.voiceTaskNote(
                                        com.ragul84.hypurr.voice.VoiceLanguage.of(voice.voiceLanguage), voice.explainInMyLanguage, task.spoken)
                                    val botId = store.startTask(com.ragul84.hypurr.voice.withVoiceNote(task.goal, note), task.template, task.input, task.projectPath, task.agentId,
                                        task.files, task.issue, model = task.model)
                                    store.rememberTaskPicks(task.agentId, task.model, task.projectPath)
                                    taskDraft = null
                                    screen = "chat:$botId"
                                } catch (e: Exception) {
                                    task = task.copy(busy = false, error = e.message ?: "Couldn't start the task.")
                                }
                            }
                        },
                        onBack = {
                            taskDraft = null
                            screen = "list"
                        },
                        onAddImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onPaste = {
                            // An image on the clipboard becomes an attachment; text goes where the error belongs.
                            val item = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                            val uri = item?.uri
                            val pasted = uri?.takeIf { contentResolver.getType(it)?.startsWith("image/") == true }?.let { readFile(it, "pasted.png") }
                            if (pasted != null) {
                                task = task.copy(files = task.files + pasted)
                            } else {
                                val text = item?.coerceToText(this@MainActivity)?.toString()?.trim().orEmpty()
                                if (text.isNotEmpty()) task = if (task.selectedTemplate?.inputLabel != null || task.goal.isNotBlank())
                                    task.copy(input = listOf(task.input, text).filter { it.isNotBlank() }.joinToString("\n"))
                                else task.copy(goal = text)
                            }
                        },
                        onPickFolder = { screen = "folderpick:newtask" },
                        onPickIssue = {
                            task = task.copy(pickingIssue = true, issues = null)
                            scope.launch {
                                val list = runCatching { store.issues() }.getOrElse {
                                    com.ragul84.hypurr.model.IssueList(errors = listOf(com.ragul84.hypurr.model.IssueError(message = it.message ?: "Couldn't load issues.")))
                                }
                                task = task.copy(issues = list)
                            }
                        },
                    )
                }

                target == "spend" -> {
                    val costs by store.costs.collectAsState()
                    LaunchedEffect(Unit) { store.loadCosts() }
                    fun go(tab: HomeTab) {
                        screen = when (tab) {
                            HomeTab.Bots -> "list"
                            HomeTab.Tasks -> "tasks"
                            HomeTab.Spend -> "spend"
                            HomeTab.You -> "settings"
                        }
                    }
                    val pricing by store.pricing.collectAsState()
                    LaunchedEffect(Unit) { store.loadPricing() }
                    SpendScreen(current.name, linkState, costs, bots, onTab = ::go, pricing = pricing,
                        onDisconnectOwnFree = { scope.launch { runCatching { store.ownFreeDisconnect() } } })
                }
                target == "standup" -> {
                    var st by remember { mutableStateOf(com.ragul84.hypurr.ui.screens.StandupUiState(loading = true,
                        projectName = projectFolder?.let { com.ragul84.hypurr.ui.screens.folderName(it) })) }
                    fun write(useAgent: Boolean = true) {
                        st = st.copy(loading = true, error = null)
                        scope.launch {
                            runCatching { store.standup(listOfNotNull(projectFolder), useAgent) }
                                .onSuccess { st = com.ragul84.hypurr.ui.screens.StandupUiState.from(it, st) }
                                .onFailure { st = st.copy(loading = false, error = it.message ?: "Couldn't reach your computer.") }
                        }
                    }
                    LaunchedEffect(Unit) {
                        write()
                        st = st.copy(reminder = store.standupReminder(), team = store.teamStandup())
                    }
                    StandupScreen(
                        st,
                        onBack = { screen = "tasks" },
                        onChange = { st = it },
                        onRewrite = { write() },
                        onCopy = {
                            getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Standup", st.shareText()))
                            st = st.copy(copied = true)
                        },
                        onShare = {
                            // The person picks Teams, Slack, WhatsApp…; nothing is sent automatically.
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, st.shareText())
                            startActivity(Intent.createChooser(send, "Share standup"))
                        },
                        onReminder = { on ->
                            scope.launch {
                                runCatching { store.setStandupReminder(on, st.reminder?.time ?: "09:30", "Asia/Kolkata") }
                                    .onSuccess { st = st.copy(reminder = it) }
                                    .onFailure { st = st.copy(error = it.message) }
                            }
                        },
                    )
                }
                target == "triage" -> {
                    val projectName = projectFolder?.let { com.ragul84.hypurr.ui.screens.folderName(it) }
                    var tr by remember { mutableStateOf(com.ragul84.hypurr.ui.screens.TriageUiState(projectName = projectName)) }
                    val shared by sharedLog.collectAsState()
                    val image by triageImage.collectAsState()
                    suspend fun ocr(uri: Uri) {
                        tr = tr.copy(busy = true, busyLabel = "Reading the screenshot", error = null)
                        val text = com.ragul84.hypurr.triage.Ocr.read(this@MainActivity, uri)
                        tr = if (text == null) tr.copy(busy = false, error = "Couldn't read any text in that image.")
                        else tr.copy(busy = false, input = text, sourceLabel = "Screenshot · text read on this phone")
                    }
                    LaunchedEffect(shared) {
                        val s = shared ?: return@LaunchedEffect
                        sharedLog.value = null
                        tr = com.ragul84.hypurr.ui.screens.TriageUiState(projectName = projectName)
                        when {
                            s.image != null -> ocr(s.image)
                            s.text != null -> tr = tr.copy(input = s.text, sourceLabel = s.name?.let { "Shared file · $it" } ?: "Shared text")
                        }
                    }
                    LaunchedEffect(image) {
                        val u = image ?: return@LaunchedEffect
                        triageImage.value = null
                        ocr(u)
                    }
                    // What the computer will remove, shown before anything is explained.
                    LaunchedEffect(tr.input) {
                        if (tr.input.isBlank()) return@LaunchedEffect
                        delay(500)
                        runCatching { store.triageRedact(tr.input) }.onSuccess { tr = tr.copy(preview = it.redactions) }
                    }
                    fun explain(mode: String = "explain") {
                        val previous = tr.result?.triage
                        tr = tr.copy(busy = true, error = null, busyLabel = if (mode == "more") "Looking deeper" else "Reading the log")
                        scope.launch {
                            runCatching { store.triage(tr.input, projectFolder, mode, previous) }
                                .onSuccess { tr = tr.copy(busy = false, result = it, ticket = it.ticket) }
                                .onFailure { tr = tr.copy(busy = false, error = it.message ?: "Couldn't reach your computer.") }
                        }
                    }
                    TriageScreen(
                        tr,
                        onBack = {
                            when {
                                tr.showTicket -> tr = tr.copy(showTicket = false)
                                tr.result != null -> tr = tr.copy(result = null)
                                else -> screen = "tasks"
                            }
                        },
                        onChange = { tr = it },
                        onPaste = {
                            val item = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                            val uri = item?.uri
                            if (uri != null && contentResolver.getType(uri)?.startsWith("image/") == true) scope.launch { ocr(uri) }
                            else item?.coerceToText(this@MainActivity)?.toString()?.takeIf { it.isNotBlank() }?.let {
                                tr = tr.copy(input = it, sourceLabel = "Pasted")
                            }
                        },
                        onScreenshot = { pickTriageImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onFromProject = {
                            val folder = projectFolder
                            if (folder == null) {
                                tr = tr.copy(error = "Pick a project folder first (You → Project folder).")
                            } else {
                                tr = tr.copy(pickingSource = true, sources = null)
                                scope.launch {
                                    runCatching { store.triageSources(folder) }
                                        .onSuccess { tr = tr.copy(sources = it) }
                                        .onFailure { tr = tr.copy(sources = emptyList(), error = it.message) }
                                }
                            }
                        },
                        onPickSource = { s ->
                            val folder = projectFolder ?: return@TriageScreen
                            tr = tr.copy(busy = true, busyLabel = "Fetching ${s.title}", pickingSource = false)
                            scope.launch {
                                runCatching { store.triageSource(folder, s.kind, s.id) }
                                    .onSuccess { tr = tr.copy(busy = false, input = it, sourceLabel = (if (s.kind == "ci") "CI · " else "File · ") + s.title) }
                                    .onFailure { tr = tr.copy(busy = false, error = it.message) }
                            }
                        },
                        onExplain = { explain() },
                        onMore = { explain("more") },
                        onTicket = { tr = tr.copy(showTicket = true, copied = false) },
                        onFixIt = {
                            val goal = tr.result?.fixGoal ?: return@TriageScreen
                            scope.launch {
                                runCatching { store.startTask(goal, null, "", projectFolder, null) }
                                    .onSuccess { screen = "chat:$it" }
                                    .onFailure { tr = tr.copy(error = it.message ?: "Couldn't start the task.") }
                            }
                        },
                        onCopyTicket = {
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Ticket", tr.ticket))
                            tr = tr.copy(copied = true)
                        },
                        onShareTicket = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tr.ticket)
                            startActivity(Intent.createChooser(send, "Share ticket note"))
                        },
                    )
                }
                target == "tasks" -> {
                    val setup by store.setup.collectAsState()
                    LaunchedEffect(Unit) { store.loadSetup() }
                    fun go(tab: HomeTab) {
                        screen = when (tab) {
                            HomeTab.Bots -> "list"
                            HomeTab.Tasks -> "tasks"
                            HomeTab.Spend -> "spend"
                            HomeTab.You -> "settings"
                        }
                    }
                    val userProfile by store.userProfile.collectAsState()
                    val templates = setup?.templates.orEmpty().let { list ->
                        val order = userProfile.preferTemplateIds(list.map { it.id })
                        list.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) 999 else i } }
                    }
                    TasksScreen(
                        current.name, linkState,
                        templates = templates,
                        recent = rosterOrder(bots.values),
                        onNewTask = { screen = "newtask" },
                        onLearnProject = { screen = "guide" },
                        onTriage = { screen = "triage" },
                        onStandup = { screen = "standup" },
                        onTicket = { screen = "ticket" },
                        onQa = { screen = "qa" },
                        onTrace = { screen = "trace" },
                        onOpenTemplate = { screen = "newtask" },
                        onOpenBot = { screen = "chat:${it.id}" },
                        onTab = ::go,
                    )
                }
                target == "profile" -> {
                    val userProfile by store.userProfile.collectAsState()
                    OnboardingScreen(
                        initial = userProfile,
                        editing = true,
                        onFinished = {
                            store.setUserProfile(it)
                            screen = "settings"
                        },
                        onSkipAll = { screen = "settings" },
                    )
                }
                target.startsWith("folderpick:") -> {
                    // folderpick:newtask | folderpick:settings | folderpick:pair | folderpick:chat:<botId>
                    val mode = target.removePrefix("folderpick:")
                    val back = when {
                        mode == "guide" -> "guide"
                        mode == "newtask" -> "newtask"
                        mode == "settings" -> "settings"
                        mode.startsWith("chat:") -> mode
                        else -> "list"
                    }
                    val setup by store.setup.collectAsState()
                    var browsing by remember { mutableStateOf<com.ragul84.hypurr.model.DirListing?>(null) }
                    var selected by remember {
                        mutableStateOf(if (mode == "newtask") taskDraft?.project ?: projectFolder else projectFolder)
                    }
                    fun browse(path: String?) {
                        scope.launch { runCatching { store.listDirs(path) }.onSuccess { browsing = it } }
                    }
                    LaunchedEffect(Unit) {
                        store.loadSetup()
                        browse(null)
                    }
                    ProjectFolderPickerScreen(
                        projects = setup?.projects.orEmpty(),
                        browsing = browsing,
                        selectedPath = selected,
                        subtitle = if (mode == "pair") "Which folder should agents work in? You can change it later." else "",
                        confirmLabel = if (mode.startsWith("chat:")) selected?.let { "New task in ${com.ragul84.hypurr.ui.screens.folderName(it)}" } else null,
                        onBack = { screen = back },
                        onBrowse = ::browse,
                        onSelect = { selected = it },
                        onConfirm = {
                            val path = selected ?: return@ProjectFolderPickerScreen
                            when {
                                mode == "newtask" -> {
                                    taskDraft = (taskDraft ?: NewTaskUiState()).copy(project = path)
                                    screen = "newtask"
                                }
                                mode.startsWith("chat:") -> {
                                    taskDraft = NewTaskUiState(project = path)
                                    screen = "newtask"
                                }
                                mode == "guide" -> {
                                    if (path != guide.folder) guide = com.ragul84.hypurr.ui.screens.GuideUiState(folder = path, tab = guide.tab)
                                    screen = "guide"
                                }
                                else -> {
                                    store.setProjectFolder(path)
                                    screen = back
                                }
                            }
                        },
                        onLater = if (mode == "pair") ({ screen = "list" }) else null,
                    )
                }
                target == "guide" -> {
                    BackHandler { screen = "tasks" }
                    LaunchedEffect(Unit) { if (guide.folder == null) guide = guide.copy(folder = projectFolder) }
                    fun text(kind: String) = guide.text(kind)
                    fun title(kind: String) = guide.docs[kind]?.title ?: "Project guide"
                    fun fileBase(kind: String) = (if (kind == "handover") "HANDOVER" else "HYPURR_GUIDE") + "-" +
                        com.ragul84.hypurr.ui.screens.folderName(guide.folder ?: "project")
                    com.ragul84.hypurr.ui.screens.ProjectGuideScreen(
                        guide,
                        onBack = { screen = "tasks" },
                        onPickFolder = { screen = "folderpick:guide" },
                        onTab = { guide = guide.copy(tab = it, editing = false, error = null) },
                        onGenerate = { kind ->
                            val folder = guide.folder ?: return@ProjectGuideScreen
                            guide = guide.copy(loading = kind, error = null)
                            scope.launch {
                                runCatching { store.projectGuide(folder, kind) }
                                    .onSuccess { d -> guide = guide.copy(loading = null, docs = guide.docs + (kind to d), edits = guide.edits - kind) }
                                    .onFailure { guide = guide.copy(loading = null, error = it.message ?: "Couldn't read the folder.") }
                            }
                        },
                        onEdit = { kind, t -> guide = guide.copy(edits = guide.edits + (kind to t)) },
                        onToggleEdit = { guide = guide.copy(editing = !guide.editing) },
                        onShareMarkdown = { kind -> com.ragul84.hypurr.ui.ShareSheet.file(this@MainActivity, fileBase(kind) + ".md", "text/markdown", text(kind)) },
                        onSharePdf = { kind ->
                            runCatching { com.ragul84.hypurr.ui.PdfExport.write(this@MainActivity, fileBase(kind) + ".pdf", text(kind)) }
                                .onSuccess { com.ragul84.hypurr.ui.ShareSheet.existing(this@MainActivity, it, "application/pdf") }
                                .onFailure { guide = guide.copy(error = "Couldn't make the PDF.") }
                        },
                        onCopy = { kind -> com.ragul84.hypurr.ui.ShareSheet.copy(this@MainActivity, title(kind), text(kind)); guide = guide.copy(copied = true) },
                        onAskSave = { guide = guide.copy(confirmSave = true) },
                        onCancelSave = { guide = guide.copy(confirmSave = false) },
                        onConfirmSave = {
                            val folder = guide.folder ?: return@ProjectGuideScreen
                            guide = guide.copy(confirmSave = false)
                            scope.launch {
                                runCatching { store.projectGuideSave(folder, text("guide")) }
                                    .onSuccess { botId -> screen = "chat:$botId" }
                                    .onFailure { guide = guide.copy(error = it.message ?: "Couldn't start the task.") }
                            }
                        },
                        onQuestion = { guide = guide.copy(question = it) },
                        onAsk = {
                            val folder = guide.folder ?: return@ProjectGuideScreen
                            val q = guide.question.trim()
                            guide = guide.copy(loading = "ask", error = null)
                            scope.launch {
                                runCatching { store.projectAsk(folder, q) }
                                    .onSuccess { a -> guide = guide.copy(loading = null, answer = a, asked = q, question = "") }
                                    .onFailure { guide = guide.copy(loading = null, error = it.message ?: "Couldn't search the folder.") }
                            }
                        },
                    )
                }
                target == "settings" -> {
                    val theme by store.themeMode.collectAsState()
                    val dynamic by store.dynamicColor.collectAsState()
                    val notify by store.notifications.collectAsState()
                    val anywhere by store.anywhere.collectAsState()
                    val setup by store.setup.collectAsState()
                    val integrations by store.integrations.collectAsState()
                    val costs by store.costs.collectAsState()
                    var tests by remember { mutableStateOf(mapOf<String, String>()) }
                    LaunchedEffect(Unit) {
                        store.loadSetup()
                        store.loadIntegrations()
                        store.loadCosts()
                    }
                    val userProfile by store.userProfile.collectAsState()
                    val learningMode by store.learningMode.collectAsState()
                    val pricing by store.pricing.collectAsState()
                    LaunchedEffect(Unit) { store.loadPricing() }
                    SettingsScreen(
                        SettingsUiState(current, linkState, theme, dynamic, notify, app.pushAvailable, BuildConfig.VERSION_NAME,
                            safety = setup?.safety, customTemplates = setup?.templates.orEmpty().filter { !it.builtin },
                            integrations = integrations, costs = costs, testResults = tests, you = you, profile = userProfile,
                            projectFolder = projectFolder, learningMode = learningMode, pricing = pricing, anywhere = anywhere,
                            voice = com.ragul84.hypurr.voice.VoiceSettings(com.ragul84.hypurr.voice.VoiceLanguage.of(userProfile.voiceLanguage),
                                userProfile.explainInMyLanguage)),
                        onVoice = { v -> store.setUserProfile(userProfile.copy(voiceLanguage = v.language.tag, explainInMyLanguage = v.explainInMyLanguage)) },
                        onEditProfile = { screen = "profile" },
                        onPickFolder = { screen = "folderpick:settings" },
                        onClearProfile = { store.clearUserProfile() },
                        onTeamAdmin = { screen = "team" },
                        onLearningMode = { store.setLearningMode(it) },
                        onItKit = { screen = "itkit" },
                        onBack = { screen = "list" },
                        showHomeTabs = true,
                        onTab = { tab ->
                            screen = when (tab) {
                                HomeTab.Bots -> "list"
                                HomeTab.Tasks -> "tasks"
                                HomeTab.Spend -> "spend"
                                HomeTab.You -> "settings"
                            }
                        },
                        onTheme = store::setTheme,
                        onDynamic = store::setDynamicColor,
                        onNotifications = store::setNotifications,
                        onAnywhere = store::setAnywhere,
                        onForget = {
                            scope.launch {
                                store.forget()
                                screen = "list"
                            }
                        },
                        onSafety = { scope.launch { runCatching { store.setSafety(it) } } },
                        onAddTemplate = { title, prompt -> scope.launch { runCatching { store.saveTemplate(TaskTemplate(title = title, prompt = prompt)) } } },
                        onDeleteTemplate = { scope.launch { runCatching { store.deleteTemplate(it) } } },
                        onIntegrations = { patch ->
                            scope.launch {
                                runCatching { store.setIntegrations(patch) }.onFailure { e ->
                                    patch.keys.firstOrNull()?.let { tests = tests + (it to (e.message ?: "Couldn't save.")) }
                                }
                            }
                        },
                        onTestIntegration = { kind ->
                            tests = tests + (kind to "Testing…")
                            scope.launch {
                                val result = runCatching { store.testIntegration(kind) }.getOrElse { it.message ?: "Test failed." }
                                tests = tests + (kind to result)
                            }
                        },
                    )
                }
                target == "itkit" -> {
                    val kit by store.itKit.collectAsState()
                    var copied by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { store.loadItKit() }
                    BackHandler { screen = "settings" }
                    ItKitScreen(
                        kit,
                        onBack = { screen = "settings" },
                        onShare = { k -> Share.text(this@MainActivity, "Hypurr: IT approval kit", k.text) },
                        onCopy = { k -> Share.copy(this@MainActivity, "Hypurr IT kit", k.text); copied = true },
                        copied = copied,
                    )
                }
                target == "team" -> {
                    val team by store.team.collectAsState()
                    val policies by store.policies.collectAsState()
                    val activity by store.activity.collectAsState()
                    val audit by store.audit.collectAsState()
                    val spend by store.spendByPerson.collectAsState()
                    var exportNote by remember { mutableStateOf<String?>(null) }
                    var tab by rememberSaveable { mutableStateOf(AdminTab.Activity) }
                    var error by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) { store.loadAdmin() }
                    fun save(block: suspend () -> Unit) {
                        error = null
                        scope.launch { runCatching { block() }.onFailure { error = it.message ?: "Couldn't save." } }
                    }
                    BackHandler { screen = "settings" }
                    TeamAdminScreen(
                        TeamAdminUiState(you, team, policies, activity, audit, spend, tab, exportNote = exportNote, error = error),
                        onBack = { screen = "settings" },
                        onTab = {
                            tab = it
                            scope.launch { store.loadAdmin() }
                        },
                        onPolicies = { patch -> save { store.setPolicies(patch) } },
                        onRole = { key, role -> save { store.setRole(key, role) } },
                        onDefaultRole = { role -> save { store.setDefaultRole(role) } },
                        onExportAudit = {
                            scope.launch {
                                runCatching { store.auditExport() }
                                    .onSuccess { x ->
                                        Share.file(this@MainActivity, x.fileName, x.mime, x.text)
                                        exportNote = "${x.rows} records" + if (x.intact) ", chain intact." else ", changed at record ${x.brokenAt}."
                                    }
                                    .onFailure { exportNote = it.message ?: "Couldn't export." }
                            }
                        },
                    )
                }
                else -> {
                    val hello by store.hello.collectAsState()
                    val builtin = hello?.builtinAgent
                    val prompt = if (you?.canAct != false && builtin != null && (builtin.needsInstall || (!builtin.installed && builtin.available))) {
                        BuiltinInstallPrompt(busy = setupInstall.busy, error = setupInstall.error, consent = builtin.consent ?: BuiltinInstallPrompt().consent)
                    } else null
                    val asks = bots.values.filter { it.needsInput }.associate { bot ->
                        val pending = entries[bot.id].orEmpty().asReversed().firstOrNull {
                            it.kind == "permission" && (it.data.status == null || it.data.status == "pending")
                        }
                        val fallback = bot.activity.ifEmpty { "Needs your OK" }.let { a ->
                            val capped = a.replaceFirstChar { ch -> ch.uppercaseChar() }
                            if (capped.endsWith("?")) capped else "$capped?"
                        }
                        bot.id to NeedsYouAsk(
                            title = pending?.data?.explain?.takeIf { it.isNotBlank() }
                                ?: pending?.data?.title?.takeIf { it.isNotBlank() }
                                ?: fallback,
                            meta = "${bot.name} · ${current.name}",
                            options = pending?.data?.options.orEmpty().ifEmpty {
                                listOf(
                                    com.ragul84.hypurr.model.PermissionOption("allow", "Allow once", "allow_once"),
                                    com.ragul84.hypurr.model.PermissionOption("always", "Always", "allow_always"),
                                    com.ragul84.hypurr.model.PermissionOption("reject", "Deny", "reject_once"),
                                )
                            },
                            entryId = pending?.id,
                        )
                    }
                    BotListScreen(
                        current.name, linkState, rosterOrder(bots.values), synced,
                        onOpen = { screen = "chat:${it.id}" },
                        onSettings = { screen = "settings" },
                        onRetry = store::reconnect,
                        onNewTask = { screen = "newtask" },
                        byId = bots,
                        onNewBot = { screen = "bot:new" },
                        onNewGroup = { screen = "group:new" },
                        canCreate = you?.canAct != false,
                        onScreen = if (hello?.screen?.enabled == true && you?.canAct != false) ({ screen = "screen" }) else null,
                        builtinInstall = prompt,
                        onInstallBuiltin = { setupOpen = true },
                        hostOs = hello?.os.orEmpty(),
                        setupProgress = com.ragul84.hypurr.ui.screens.setupProgress(hello),
                        asks = asks,
                        onRespondAsk = { _, entryId, optionId ->
                            if (entryId != null) scope.launch { runCatching { store.respond(entryId, optionId) } }
                        },
                        onSpend = { screen = "spend" },
                        onTasks = { screen = "tasks" },
                    )
                    SetupDialog(hello)
                }
            }
        }
    }

    /** Where Back goes: a thread to its chat, a bot's settings to its chat, everything else to the list. */
    private fun parentOf(screen: String): String = when {
        screen.startsWith("thread:") -> "chat:" + screen.split(':')[1]
        screen.startsWith("edit:") -> "chat:" + screen.removePrefix("edit:")
        screen.startsWith("terminal:") -> "edit:" + screen.removePrefix("terminal:")
        screen == "team" || screen == "profile" -> "settings"
        screen == "folderpick:settings" -> "settings"
        screen == "folderpick:newtask" -> "newtask"
        screen == "folderpick:guide" -> "guide"
        screen == "guide" -> "tasks"
        screen.startsWith("folderpick:chat:") -> screen.removePrefix("folderpick:")
        screen == "spend" || screen == "tasks" -> "list"
        screen == "ticket" || screen == "qa" || screen == "trace" -> "tasks"
        else -> "list"
    }

    private fun copyText(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    /** QA test writer: cases → edit → export (copy / share) or automate → run → coverage. */
    @Composable
    private fun QaRoute(
        store: HypurrStore,
        projectFolder: String?,
        draft: com.ragul84.hypurr.ui.screens.QaUiState?,
        saveDraft: (com.ragul84.hypurr.ui.screens.QaUiState?) -> Unit,
        go: (String) -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val setup by store.setup.collectAsState()
        val bots by store.bots.collectAsState()
        var st by remember { mutableStateOf(draft ?: com.ragul84.hypurr.ui.screens.QaUiState()) }
        SideEffect { saveDraft(st) }
        LaunchedEffect(Unit) { store.loadSetup() }
        LaunchedEffect(setup) {
            val projects = setup?.projects.orEmpty()
            val pick = st.project ?: projects.firstOrNull { projectFolder != null && projectFolder.startsWith(it.path) }?.path ?: projects.firstOrNull()?.path
            st = st.copy(projects = projects, project = pick)
        }
        val bot = st.botId?.let { bots[it] }
        LaunchedEffect(bot?.status, bot?.task?.id) {
            if (bot != null) st = st.copy(agentStatus = when {
                bot.needsInput -> "needsInput"
                bot.isWorking -> "working"
                else -> "idle"
            }, taskId = bot.task?.id ?: st.taskId)
        }
        // Cases live on the computer, per project: load last time's, save edits (debounced).
        LaunchedEffect(st.project) {
            val p = st.project ?: return@LaunchedEffect
            if (st.cases.isEmpty()) runCatching { store.qaSavedCases(p) }.getOrNull()?.let { st = st.copy(saved = it) }
        }
        LaunchedEffect(st.cases) {
            val p = st.project ?: return@LaunchedEffect
            if (st.cases.isEmpty()) return@LaunchedEffect
            delay(800)
            runCatching { store.qaSaveCases(p, st.cases, st.input, st.sourceName) }
        }
        fun fail(e: Throwable, what: String) { st = st.copy(busy = false, error = e.message ?: what) }
        fun exportText() = com.ragul84.hypurr.qa.QaExport.export(st.cases, st.format, st.automated)
        fun run() {
            val id = st.taskId ?: return
            st = st.copy(busy = true, busyLabel = "Running ${st.selectedFramework?.command ?: "the tests"} on the computer", error = null)
            scope.launch {
                runCatching { store.qaRun(id) }
                    .onSuccess { r -> st = st.copy(busy = false, step = QaStep.Result, result = r) }
                    .onFailure { fail(it, "Couldn't run the tests.") }
            }
        }
        fun back() {
            st = when (st.step) {
                QaStep.Cases -> st.copy(step = QaStep.Input, error = null)
                QaStep.Export -> st.copy(step = if (st.result != null) QaStep.Result else QaStep.Cases)
                QaStep.Result -> st.copy(step = QaStep.Cases)
                QaStep.Running -> { go("tasks"); st }
                QaStep.Input -> { saveDraft(null); go("tasks"); st }
            }
        }
        BackHandler { back() }
        QaScreen(
            st,
            onBack = ::back,
            onChange = { st = it },
            onPaste = {
                val text = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    ?.coerceToText(this@MainActivity)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) st = st.copy(input = text, error = null)
            },
            onWrite = {
                st = st.copy(busy = true, busyLabel = "Writing test cases", error = null, needsSetup = null)
                scope.launch {
                    runCatching { store.qaCases(st.sourceName, st.input, st.project) }
                        .onSuccess { r ->
                            st = if (r.needsSetup != null) st.copy(busy = false, needsSetup = r.needsSetup)
                            else st.copy(busy = false, step = QaStep.Cases, cases = r.cases, by = r.by, frameworks = r.frameworks,
                                framework = r.frameworks.firstOrNull()?.id, picked = emptySet(), result = null, explain = st.explain || r.explain)
                        }
                        .onFailure { fail(it, "Couldn't write test cases.") }
                }
            },
            onCopy = { copyText("Test cases", exportText()); st = st.copy(copied = true) },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).setType(if (st.format.csv) "text/csv" else "text/plain")
                    .putExtra(Intent.EXTRA_TEXT, exportText())
                    .putExtra(Intent.EXTRA_SUBJECT, com.ragul84.hypurr.qa.QaExport.fileName(st.format))
                runCatching { startActivity(Intent.createChooser(send, null)) }
            },
            onAutomate = {
                val project = st.project ?: return@QaScreen
                val f = st.selectedFramework ?: return@QaScreen
                val cases = st.cases.filter { it.id in st.picked }
                st = st.copy(busy = true, busyLabel = "Starting on a safe branch", error = null)
                scope.launch {
                    runCatching { store.qaAutomate(cases, f.id, project) }
                        .onSuccess { botId -> st = st.copy(busy = false, step = QaStep.Running, botId = botId, agentStatus = "working"); go("chat:$botId") }
                        .onFailure { fail(it, "Couldn't start the task.") }
                }
            },
            onOpenTask = { st.botId?.let { go("chat:$it") } },
            onRun = ::run,
        )
    }

    /** Trace a click and the page map: approvals, live recording, Playwright export, restructure on a branch. */
    @Composable
    private fun TraceMapRoute(
        store: HypurrStore,
        projectFolder: String?,
        draft: com.ragul84.hypurr.ui.screens.TraceMapUiState?,
        saveDraft: (com.ragul84.hypurr.ui.screens.TraceMapUiState?) -> Unit,
        go: (String) -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val setup by store.setup.collectAsState()
        var st by remember { mutableStateOf(draft ?: com.ragul84.hypurr.ui.screens.TraceMapUiState()) }
        SideEffect { saveDraft(st) }
        LaunchedEffect(Unit) { store.loadSetup() }
        LaunchedEffect(setup) {
            val projects = setup?.projects.orEmpty()
            val pick = projects.firstOrNull { it.path == st.project }
                ?: projects.firstOrNull { projectFolder != null && projectFolder.startsWith(it.path) } ?: projects.firstOrNull()
            st = st.copy(project = pick?.path, projectName = pick?.name)
        }
        LaunchedEffect(st.project) {
            val p = st.project ?: return@LaunchedEffect
            if (st.map == null) runCatching { store.mapGet(p) }.getOrNull()?.let { r ->
                st = st.copy(map = r.map, plan = r.plan, applied = r.applied?.takeIf { it.status.isNotEmpty() })
            }
        }
        // While recording or applying, follow the laptop.
        LaunchedEffect(st.trace?.id, st.recording) {
            val p = st.project ?: return@LaunchedEffect
            while (st.recording) {
                delay(2000)
                val id = st.trace?.id ?: break
                runCatching { store.traceGet(p, id) }.getOrNull()?.trace?.let { st = st.copy(trace = it) }
            }
        }
        LaunchedEffect(st.applied?.status) {
            val p = st.project ?: return@LaunchedEffect
            while (st.applied?.status == "running") {
                delay(3000)
                runCatching { store.mapGet(p) }.getOrNull()?.applied?.let { st = st.copy(applied = it) }
            }
        }
        fun fail(e: Throwable, what: String) { st = st.copy(busy = false, error = e.message ?: what) }
        fun started(r: com.ragul84.hypurr.model.TraceReply) {
            st = if (r.status == "needsApproval") st.copy(busy = false, approval = r)
            else st.copy(busy = false, approval = null, trace = r.trace, open = null, exported = null)
        }
        fun back() {
            st = when {
                st.tab == 1 && st.mapStep != com.ragul84.hypurr.ui.screens.MapStep.Pages ->
                    st.copy(mapStep = com.ragul84.hypurr.ui.screens.MapStep.Pages, page = null, confirmApply = false)
                st.approval != null -> st.copy(approval = null)
                else -> { saveDraft(null); go("tasks"); st }
            }
        }
        BackHandler { back() }
        com.ragul84.hypurr.ui.screens.TraceMapScreen(
            st,
            onBack = ::back,
            onChange = { st = it },
            onStart = {
                val p = st.project ?: return@TraceMapScreen
                st = st.copy(busy = true, busyLabel = "Opening the page", error = null)
                scope.launch { runCatching { store.traceStart(p, st.url, st.stepList) }.onSuccess(::started).onFailure { fail(it, "Couldn't start the trace.") } }
            },
            onAllow = {
                val p = st.project ?: return@TraceMapScreen
                st = st.copy(busy = true, busyLabel = "Opening the page", error = null)
                scope.launch { runCatching { store.traceAllow(p, st.url, st.stepList) }.onSuccess(::started).onFailure { fail(it, "Couldn't start the trace.") } }
            },
            onStop = {
                val id = st.trace?.id ?: return@TraceMapScreen
                scope.launch { runCatching { store.traceStop(id) } }
            },
            onExport = {
                val p = st.project ?: return@TraceMapScreen
                val id = st.trace?.id ?: return@TraceMapScreen
                scope.launch { runCatching { store.traceExport(p, id) }.onSuccess { st = st.copy(exported = it, copied = false) }.onFailure { fail(it, "Couldn't make the test.") } }
            },
            onCopy = { st.exported?.let { copyText("Playwright test", it.code); st = st.copy(copied = true) } },
            onShare = {
                val code = st.exported?.code ?: return@TraceMapScreen
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code).putExtra(Intent.EXTRA_SUBJECT, "flow.spec.js")
                runCatching { startActivity(Intent.createChooser(send, null)) }
            },
            onScan = {
                val p = st.project ?: return@TraceMapScreen
                st = st.copy(busy = true, busyLabel = "Reading the project", error = null)
                scope.launch { runCatching { store.mapScan(p) }.onSuccess { st = st.copy(busy = false, map = it.map, plan = it.plan) }.onFailure { fail(it, "Couldn't map the project.") } }
            },
            onApply = {
                val p = st.project ?: return@TraceMapScreen
                st = st.copy(confirmApply = false, busy = true, busyLabel = "Starting", error = null)
                scope.launch { runCatching { store.restructureApply(p) }.onSuccess { st = st.copy(busy = false, applied = it.applied) }.onFailure { fail(it, "Couldn't start.") } }
            },
            onPublish = {
                val p = st.project ?: return@TraceMapScreen
                st = st.copy(busy = true, busyLabel = "Uploading the branch", error = null)
                scope.launch { runCatching { store.restructurePublish(p) }.onSuccess { st = st.copy(busy = false, applied = it.applied) }.onFailure { fail(it, "Couldn't open the pull request.") } }
            },
            onDiscard = {
                val p = st.project ?: return@TraceMapScreen
                scope.launch { runCatching { store.restructureDiscard(p) }.onSuccess { st = st.copy(applied = null) }.onFailure { fail(it, "Couldn't delete the branch.") } }
            },
        )
    }

    /** Ticket to PR: paste → plan → task on the safe branch → review → approved upload. */
    @Composable
    private fun TicketRoute(
        store: HypurrStore,
        projectFolder: String?,
        draft: com.ragul84.hypurr.ui.screens.TicketUiState?,
        saveDraft: (com.ragul84.hypurr.ui.screens.TicketUiState?) -> Unit,
        go: (String) -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val setup by store.setup.collectAsState()
        val bots by store.bots.collectAsState()
        var st by remember { mutableStateOf(draft ?: com.ragul84.hypurr.ui.screens.TicketUiState()) }
        SideEffect { saveDraft(st) }
        LaunchedEffect(Unit) { store.loadSetup() }
        LaunchedEffect(setup) {
            val projects = setup?.projects.orEmpty()
            val pick = st.project ?: projects.firstOrNull { projectFolder != null && projectFolder.startsWith(it.path) }?.path ?: projects.firstOrNull()?.path
            st = st.copy(projects = projects, project = pick)
        }
        // Follow the running task's agent.
        val bot = st.botId?.let { bots[it] }
        LaunchedEffect(bot?.status, bot?.task?.branch) {
            if (bot != null) st = st.copy(agentStatus = when {
                bot.needsInput -> "needsInput"
                bot.isWorking -> "working"
                else -> "idle"
            }, branch = bot.task?.branch ?: st.branch, taskId = bot.task?.id ?: st.taskId)
        }
        fun fail(e: Throwable, what: String) { st = st.copy(busy = false, error = e.message ?: what) }
        fun loadPlan(ticket: com.ragul84.hypurr.model.Issue, project: String) {
            st = st.copy(busy = true, busyLabel = "Reading the code and making a plan", error = null)
            scope.launch {
                runCatching { store.ticketPlan(ticket, project) }
                    .onSuccess { r -> st = st.copy(busy = false, step = TicketStep.Plan, plan = r.plan, planBy = r.by, checksText = r.plan.checks.joinToString("\n")) }
                    .onFailure { fail(it, "Couldn't make a plan.") }
            }
        }
        fun review() {
            val id = st.taskId ?: return
            st = st.copy(busy = true, busyLabel = "Summarising the changes", error = null)
            scope.launch {
                runCatching { store.ticketReview(id) }
                    .onSuccess { r -> st = st.copy(busy = false, step = TicketStep.Review, review = r, prTitle = r.prTitle, prBody = r.prBody) }
                    .onFailure { fail(it, "Couldn't read the changes.") }
            }
        }
        fun openSetup() {
            st = st.copy(step = TicketStep.Setup, busy = true, busyLabel = "Checking the computer", error = null, setupNote = null)
            scope.launch {
                runCatching { store.ticketSetup() }
                    .onSuccess { s -> st = st.copy(busy = false, setup = s, jiraUrl = s.integrations.jira.baseUrl, jiraEmail = s.integrations.jira.email) }
                    .onFailure { fail(it, "Couldn't reach the computer.") }
            }
        }
        BackHandler {
            st = when (st.step) {
                TicketStep.Setup, TicketStep.Plan -> st.copy(step = TicketStep.Input, error = null)
                TicketStep.Review -> st.copy(step = TicketStep.Running, confirmPush = false, error = null)
                else -> { go("tasks"); st }
            }
        }
        TicketScreen(
            st,
            onBack = {
                when (st.step) {
                    TicketStep.Setup, TicketStep.Plan -> st = st.copy(step = TicketStep.Input, error = null)
                    TicketStep.Review -> st = st.copy(step = TicketStep.Running, confirmPush = false, error = null)
                    TicketStep.Running -> go("tasks")
                    TicketStep.Input -> { saveDraft(null); go("tasks") }
                }
            },
            onChange = { st = it },
            onPaste = {
                val text = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    ?.coerceToText(this@MainActivity)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) st = st.copy(input = text, needsSetup = null, error = null)
            },
            onRead = {
                val project = st.project ?: return@TicketScreen
                st = st.copy(busy = true, busyLabel = "Reading the ticket", error = null, needsSetup = null)
                scope.launch {
                    runCatching { store.ticketResolve(st.input, project) }
                        .onSuccess { r ->
                            val t = r.ticket
                            if (t == null) st = st.copy(busy = false, needsSetup = r.needsSetup)
                            else { st = st.copy(ticket = t); loadPlan(t, project) }
                        }
                        .onFailure { fail(it, "Couldn't read the ticket.") }
                }
            },
            onSetup = ::openSetup,
            onApprovePlan = {
                val (t, p, project) = Triple(st.ticket, st.editedPlan(), st.project)
                if (t == null || p == null || project == null) return@TicketScreen
                st = st.copy(busy = true, busyLabel = "Starting on a safe branch", error = null)
                scope.launch {
                    runCatching { store.ticketStart(t, p, project) }
                        .onSuccess { botId -> st = st.copy(busy = false, step = TicketStep.Running, botId = botId, plan = p); go("chat:$botId") }
                        .onFailure { fail(it, "Couldn't start the task.") }
                }
            },
            onRedoPlan = { val t = st.ticket; val p = st.project; if (t != null && p != null) loadPlan(t, p) },
            onOpenTask = { st.botId?.let { go("chat:$it") } },
            onReview = ::review,
            onApprovePush = {
                val id = st.taskId ?: return@TicketScreen
                st = st.copy(confirmPush = false, busy = true, busyLabel = "Uploading the branch", error = null)
                scope.launch {
                    runCatching { store.ticketPublish(id, st.prTitle, st.prBody) }
                        .onSuccess { pr ->
                            val r = st.review
                            st = st.copy(busy = false, review = r?.copy(pr = pr, comment = if (pr.url != null) r.comment.replace("A pull request is on its way for review.", "Pull request for review: ${pr.url}") else r.comment))
                        }
                        .onFailure { fail(it, "Couldn't upload the branch.") }
                }
            },
            onCopy = { label, text -> copyText(label, text); st = st.copy(copied = label) },
            onShare = ::shareText,
            onOpenUrl = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            onSaveSetup = {
                val patch = kotlinx.serialization.json.buildJsonObject {
                    if (st.githubToken.isNotBlank()) put("github", kotlinx.serialization.json.buildJsonObject { put("token", kotlinx.serialization.json.JsonPrimitive(st.githubToken.trim())) })
                    if (st.jiraUrl.isNotBlank() || st.jiraEmail.isNotBlank() || st.jiraToken.isNotBlank()) put("jira", kotlinx.serialization.json.buildJsonObject {
                        put("baseUrl", kotlinx.serialization.json.JsonPrimitive(st.jiraUrl.trim()))
                        put("email", kotlinx.serialization.json.JsonPrimitive(st.jiraEmail.trim()))
                        if (st.jiraToken.isNotBlank()) put("token", kotlinx.serialization.json.JsonPrimitive(st.jiraToken.trim()))
                    })
                }
                st = st.copy(busy = true, busyLabel = "Saving on the computer", error = null)
                scope.launch {
                    runCatching { store.setIntegrations(patch); store.ticketSetup() }
                        .onSuccess { s -> st = st.copy(busy = false, setup = s, githubToken = "", jiraToken = "", setupNote = "Saved on the computer.") }
                        .onFailure { fail(it, "Couldn't save. Only an admin can change trackers.") }
                }
            },
            onTest = { kind ->
                st = st.copy(busy = true, busyLabel = "Testing", error = null)
                scope.launch {
                    runCatching { store.testIntegration(kind) }
                        .onSuccess { st = st.copy(busy = false, setupNote = it) }
                        .onFailure { fail(it, "Test failed.") }
                }
            },
        )
    }

    /** The computer's screen over WebRTC; the session lives as long as this screen is open. */
    @Composable
    private fun ScreenRoute(store: HypurrStore, name: String, onBack: () -> Unit) {
        val client = store.client
        var status by remember { mutableStateOf<ScreenState?>(null) }
        var frame by remember { mutableStateOf(IntSize.Zero) }
        var session by remember { mutableStateOf<ScreenSession?>(null) }
        val idle = remember { MutableStateFlow<ScreenPhase>(ScreenPhase.Connecting) }
        fun begin() {
            frame = IntSize.Zero
            lifecycleScope.launch {
                val st = runCatching { client?.screenStatus() }.getOrNull()
                status = st ?: ScreenState()
                session?.release()
                session = null
                if (client != null && st?.ready == true) {
                    val display = st.displays.firstOrNull { it.main } ?: st.displays.firstOrNull()
                    session = ScreenSession(this@MainActivity, client, store.route == Route.Relay, lifecycleScope, display).also { it.start() }
                }
            }
        }
        LaunchedEffect(Unit) { begin() }
        DisposableEffect(Unit) { onDispose { session?.release() } }
        BackHandler(onBack = onBack)
        val s = session
        val phase by (s?.phase ?: idle).collectAsState()
        val clipboard by (s?.remoteClipboard ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
        val error by (s?.lastError ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
        ScreenScreen(
            ScreenUiState(name, status, phase, frame, clipboard, error),
            onBack = onBack,
            onRetry = ::begin,
            onInput = { s?.send(it) },
            onTakeClipboard = {
                clipboard?.let { getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Computer", it)) }
                s?.remoteClipboard?.value = null
            },
        ) { modifier ->
            if (s != null) key(s) {
                val track by s.track.collectAsState()
                var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
                AndroidView(factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(s.egl.eglBaseContext, object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() = Unit
                            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                                post { frame = if (rotation % 180 == 0) IntSize(w, h) else IntSize(h, w) }
                            }
                        })
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setEnableHardwareScaler(true)
                        renderer = this
                    }
                }, modifier = modifier, onRelease = { it.release() })
                DisposableEffect(track, renderer) {
                    val r = renderer
                    val t = track
                    if (r != null) t?.addSink(r)
                    onDispose { if (r != null) t?.removeSink(r) }
                }
            }
        }
    }


    @Composable
    private fun TerminalRoute(
        store: HypurrStore,
        title: String,
        botId: String,
        offline: Boolean,
        onBack: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        var state by remember(botId) { mutableStateOf(TerminalUiState(title = title, phase = TerminalPhase.Connecting)) }
        var termId by remember(botId) { mutableStateOf<String?>(null) }
        var closed by remember(botId) { mutableStateOf(false) }
        var cols by remember { mutableStateOf(80) }
        var rows by remember { mutableStateOf(24) }

        fun appendOutput(chunk: String) {
            val plain = stripAnsi(chunk)
            if (plain.isEmpty()) return
            state = state.copy(output = (state.output + plain).takeLast(200_000))
        }

        fun sendBytes(bytes: ByteArray) {
            val id = termId ?: return
            if (state.phase != TerminalPhase.Live && state.phase != TerminalPhase.Reconnecting) return
            scope.launch {
                runCatching { store.termInput(id, bytesToBase64(bytes)) }
                    .onFailure { state = state.copy(error = it.message ?: "Couldn't send.") }
            }
        }

        LaunchedEffect(botId) {
            closed = false
            state = TerminalUiState(title = title, phase = TerminalPhase.Connecting)
            val opened = runCatching { store.openShell(botId = botId, cols = cols, rows = rows) }
            opened.onFailure {
                state = state.copy(phase = TerminalPhase.Failed, error = it.message ?: "Couldn't open the shell.")
                return@LaunchedEffect
            }
            val reply = opened.getOrThrow()
            termId = reply.term
            state = state.copy(cwd = reply.cwd, phase = TerminalPhase.Live, error = null)
            var attempt = 0
            while (!closed && state.phase != TerminalPhase.Ended && state.phase != TerminalPhase.Failed) {
                try {
                    store.termEvents(reply.term).collect { ev ->
                        attempt = 0
                        when (ev.type) {
                            "output" -> {
                                val data = ev.data ?: return@collect
                                val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: return@collect
                                appendOutput(bytes.toString(Charsets.UTF_8))
                            }
                            "exit" -> {
                                state = state.copy(phase = TerminalPhase.Ended, exitCode = ev.code)
                            }
                        }
                    }
                    if (state.phase == TerminalPhase.Live) {
                        attempt++
                        if (attempt > 5) {
                            state = state.copy(phase = TerminalPhase.Failed, error = "Lost the terminal connection.")
                            break
                        }
                        state = state.copy(phase = TerminalPhase.Reconnecting)
                        kotlinx.coroutines.delay(1_000)
                        if (!closed) state = state.copy(phase = TerminalPhase.Live)
                    } else break
                } catch (e: Exception) {
                    if (closed) break
                    attempt++
                    if (attempt > 5) {
                        state = state.copy(phase = TerminalPhase.Failed, error = e.message ?: "Lost the terminal connection.")
                        break
                    }
                    state = state.copy(phase = TerminalPhase.Reconnecting, error = null)
                    kotlinx.coroutines.delay(1_000)
                    if (!closed) state = state.copy(phase = TerminalPhase.Live)
                }
            }
        }

        TerminalScreen(
            state = state,
            offline = offline,
            onBack = onBack,
            onDraft = { state = state.copy(draft = it) },
            onSubmitLine = {
                val line = state.draft
                state = state.copy(draft = "")
                sendBytes((line + "\n").toByteArray(Charsets.UTF_8))
            },
            onControl = { sendBytes(it) },
            onResize = { c, r ->
                cols = c; rows = r
                val id = termId ?: return@TerminalScreen
                scope.launch { runCatching { store.termResize(id, c, r) } }
            },
            onDisconnect = {
                closed = true
                val id = termId ?: return@TerminalScreen
                termId = null
                scope.launch { runCatching { store.termClose(id) } }
            },
        )
    }

    companion object {
        const val EXTRA_BOT = "botId"
        const val MAX_FILE = 100 * 1024 * 1024
    }
}

/** Something shared into Hypurr for Fix from a log. */
data class SharedLog(val text: String? = null, val image: Uri? = null, val name: String? = null)
