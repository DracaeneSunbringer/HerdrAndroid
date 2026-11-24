package dev.herdr.android.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.herdr.android.connection.ConnectionProfile
import dev.herdr.android.connection.ConnectionState
import dev.herdr.android.connection.HerdrConnectionController
import dev.herdr.android.connection.HerdrConnectionControllerFactory
import dev.herdr.android.connection.HostPlatform
import dev.herdr.android.ui.terminal.AgyAgentMode
import dev.herdr.android.ui.terminal.AgyBtwCardState
import dev.herdr.android.ui.terminal.AgyBtwTurn
import dev.herdr.android.ui.terminal.AgyInteractiveParser
import dev.herdr.android.ui.terminal.AgySlashModalKind
import dev.herdr.android.ui.terminal.AskNavDirection
import dev.herdr.android.ui.terminal.SequenceOperation
import dev.herdr.android.ui.workbench.SequenceExecutionStatus
import dev.herdr.android.ui.workbench.TerminalSequenceCoordinator
import dev.herdr.android.connection.ZeroTierOverlayRegistry
import dev.herdr.android.connection.ZeroTierOverlayState
import dev.herdr.android.connection.ZeroTierOverlayTransport
import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.AttachMode
import dev.herdr.android.protocol.MachineSummary
import dev.herdr.android.protocol.TerminalSession
import dev.herdr.android.protocol.TerminalSummary
import dev.herdr.android.protocol.TranscriptAgentKind
import dev.herdr.android.protocol.TranscriptRawEvent
import dev.herdr.android.service.HerdrKeepAliveCoordinator
import dev.herdr.android.ui.terminal.QuickReplyAction
import dev.herdr.android.ui.terminal.StructuredTerminalReader
import dev.herdr.android.ui.terminal.TerminalDialect
import dev.herdr.android.ui.terminal.TerminalSemanticBlock
import dev.herdr.android.ui.terminal.TerminalTranscriptUiState
import dev.herdr.android.ui.theme.HerdrColors
import dev.herdr.android.ui.theme.HerdrThemePreset
import dev.herdr.android.ui.theme.HerdrThemeTriad
import dev.herdr.android.ui.workbench.ImageUploadPreparer
import dev.herdr.android.update.AppUpdateManager
import dev.herdr.android.update.AppUpdateState
import dev.herdr.android.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class WorkspaceTab(val title: String, val badgeCode: String) {
    CONNECTION("连接", "CONN"),
    AGENTS("拓扑", "TREE"),
    TERMINAL("终端", "TERM"),
    SETTINGS("设置", "CONF")
}

data class HerdrAppSettings(
    val themePreset: HerdrThemePreset = HerdrThemePreset.LINEAR_OBSIDIAN,
    val themeTriad: HerdrThemeTriad = HerdrThemeTriad(),
    val defaultLanHost: String = "192.168.1.100",
    val defaultZeroTierHost: String = "10.147.17.100",
    val defaultDesktopFallbackCols: Int = 138,
    val defaultAttachMode: AttachMode = AttachMode.OBSERVE,
    val autoHideTerminalTopBar: Boolean = true,
    val autoHideObserveKeyStrip: Boolean = true,
    val keepAliveInBackground: Boolean = true
)

data class AgentTreeNode(
    val summary: TerminalSummary,
    val treeConnectors: List<String> = emptyList(),
    val depth: Int = 0,
    val effectiveUrgencyRank: Int = 0
)

data class DispatchedReceipt(
    val address: String,
    val text: String,
    val sentAtTurn: Int,
    val timestampMs: Long = android.os.SystemClock.elapsedRealtime()
)

data class FleetCardDigest(
    val headline: String,
    val urgentActions: List<QuickReplyAction> = emptyList()
)

data class StagedImageAttachment(
    val id: String,
    val displayName: String,
    val sizeBytes: Long = 0L,
    val remotePath: String? = null,
    val uploading: Boolean = false,
    val error: String? = null
)

enum class QueuedDispatchMode(val label: String, val badgeText: String) {
    FOLLOW_UP("本轮结束后自动发", "⏳ 待本轮后自动发送"),
    MANUAL("暂存待发", "📌 暂存队列")
}

data class QueuedPromptItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val address: String,
    val text: String,
    val attachmentPaths: List<String> = emptyList(),
    val attachmentNames: List<String> = emptyList(),
    val isAgentTui: Boolean = true,
    val mode: QueuedDispatchMode = QueuedDispatchMode.FOLLOW_UP,
    val createdAtMs: Long = System.currentTimeMillis(),
    val enqueuedAtTurn: Int = 0
)

class HerdrMainViewModel(
    private val controller: HerdrConnectionController = HerdrConnectionControllerFactory.shared()
) : ViewModel() {

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var storageInitialized = false

    private val _selectedTab = MutableStateFlow(
        if (HerdrKeepAliveCoordinator.activeSession.value != null) WorkspaceTab.TERMINAL else WorkspaceTab.AGENTS
    )
    val selectedTab: StateFlow<WorkspaceTab> = _selectedTab.asStateFlow()

    private val _navHistory = MutableStateFlow<List<WorkspaceTab>>(emptyList())
    val navHistory: StateFlow<List<WorkspaceTab>> = _navHistory.asStateFlow()

    private val _appSettings = MutableStateFlow(HerdrAppSettings())
    val appSettings: StateFlow<HerdrAppSettings> = _appSettings.asStateFlow()

    private val _profile = MutableStateFlow(
        ConnectionProfile(
            id = "win-workstation",
            displayName = "Windows 主力机 (LAN / ZeroTier)",
            host = "192.168.1.100",
            port = 22,
            username = "herdr",
            privateKeyPem = "",
            privateKeyPathHint = "/sdcard/Android/data/dev.herdr.android/files/id_ed25519_herdr_android",
            expectedHostKeyFingerprint = null,
            platform = HostPlatform.WINDOWS,
            hcExecutablePath = """C:\Users\herdr\AppData\Local\Programs\herdr-core\hc.exe"""
        )
    )
    val profile: StateFlow<ConnectionProfile> = _profile.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = controller.connectionState
    val machines: StateFlow<List<MachineSummary>> = controller.machines
    val terminals: StateFlow<List<TerminalSummary>> = controller.terminals

    private val _statusFilter = MutableStateFlow<AgentStateWord?>(null)
    val statusFilter: StateFlow<AgentStateWord?> = _statusFilter.asStateFlow()

    val totalLiveCount: StateFlow<Int> = controller.terminals.map { list ->
        list.count { it.state == "live" }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val statusCounts: StateFlow<Map<AgentStateWord, Int>> = controller.terminals.map { list ->
        val live = list.filter { it.state == "live" }
        mapOf(
            AgentStateWord.BLOCKED to live.count {
                it.agentStatus == AgentStateWord.BLOCKED || it.agentStatus == AgentStateWord.ERROR
            },
            AgentStateWord.WORKING to live.count { it.agentStatus == AgentStateWord.WORKING },
            AgentStateWord.DONE to live.count { it.agentStatus == AgentStateWord.DONE },
            AgentStateWord.IDLE to live.count {
                it.agentStatus == AgentStateWord.IDLE || it.agentStatus == AgentStateWord.NONE
            }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val agentTreeNodes: StateFlow<List<AgentTreeNode>> = combine(
        controller.terminals,
        _statusFilter
    ) { allTerminals, filter ->
        buildAgentTree(allTerminals, filter)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _activeSession: MutableStateFlow<TerminalSession?> = HerdrKeepAliveCoordinator.activeSession
    val activeSession: StateFlow<TerminalSession?> = _activeSession.asStateFlow()

    private val _activeTerminalSummary: MutableStateFlow<TerminalSummary?> = HerdrKeepAliveCoordinator.activeTerminalSummary
    val activeTerminalSummary: StateFlow<TerminalSummary?> = _activeTerminalSummary.asStateFlow()

    private val _savedDesktopDims = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val savedDesktopDims: StateFlow<Map<String, Pair<Int, Int>>> = _savedDesktopDims.asStateFlow()

    private val _mobilePtyTargetCols = MutableStateFlow(68)
    val mobilePtyTargetCols: StateFlow<Int> = _mobilePtyTargetCols.asStateFlow()

    private val _toastBanner = MutableStateFlow<String?>(null)
    val toastBanner: StateFlow<String?> = _toastBanner.asStateFlow()

    private val _zeroTierOverlayState = MutableStateFlow(ZeroTierOverlayState())
    val zeroTierOverlayState: StateFlow<ZeroTierOverlayState> = _zeroTierOverlayState.asStateFlow()

    private val _appUpdateState = MutableStateFlow(AppUpdateState())
    val appUpdateState: StateFlow<AppUpdateState> = _appUpdateState.asStateFlow()

    private val _acknowledgedDoneTurns = MutableStateFlow<Map<String, Int>>(emptyMap())
    val acknowledgedDoneTurns: StateFlow<Map<String, Int>> = _acknowledgedDoneTurns.asStateFlow()

    private val _draftsByAddress = MutableStateFlow<Map<String, String>>(emptyMap())
    val draftsByAddress: StateFlow<Map<String, String>> = _draftsByAddress.asStateFlow()

    private val _dispatchedReceipts = MutableStateFlow<Map<String, DispatchedReceipt>>(emptyMap())
    val dispatchedReceipts: StateFlow<Map<String, DispatchedReceipt>> = _dispatchedReceipts.asStateFlow()

    private val _terminalPreviews = MutableStateFlow<Map<String, String>>(emptyMap())
    val terminalPreviews: StateFlow<Map<String, String>> = _terminalPreviews.asStateFlow()

    private val _stagedAttachmentsByAddress = MutableStateFlow<Map<String, List<StagedImageAttachment>>>(emptyMap())
    val stagedAttachmentsByAddress: StateFlow<Map<String, List<StagedImageAttachment>>> = _stagedAttachmentsByAddress.asStateFlow()

    private val _btwHistoryByAddress = MutableStateFlow<Map<String, List<AgyBtwTurn>>>(emptyMap())
    val btwHistoryByAddress: StateFlow<Map<String, List<AgyBtwTurn>>> = _btwHistoryByAddress.asStateFlow()

    private val _viewingBtwHistoryAddress = MutableStateFlow<String?>(null)
    val viewingBtwHistoryAddress: StateFlow<String?> = _viewingBtwHistoryAddress.asStateFlow()

    private val _queuedPromptsByAddress = MutableStateFlow<Map<String, List<QueuedPromptItem>>>(emptyMap())
    val queuedPromptsByAddress: StateFlow<Map<String, List<QueuedPromptItem>>> = _queuedPromptsByAddress.asStateFlow()

    private val _transcriptStatesByAddress = MutableStateFlow<Map<String, TerminalTranscriptUiState>>(emptyMap())
    val transcriptStatesByAddress: StateFlow<Map<String, TerminalTranscriptUiState>> = _transcriptStatesByAddress.asStateFlow()

    private val sawWorkingByAddress = ConcurrentHashMap<String, Boolean>()
    private val latestStatusByAddress = ConcurrentHashMap<String, AgentStateWord>()
    private val queueDispatchJobs = ConcurrentHashMap<String, Job>()
    private val readerHistoryByAddress = ConcurrentHashMap<String, List<TerminalSemanticBlock>>()
    private val transcriptSyncMutexes = ConcurrentHashMap<String, Mutex>()
    private val transcriptPendingResync = ConcurrentHashMap<String, Boolean>()
    private val transcriptPendingForce = ConcurrentHashMap<String, Boolean>()
    private val transcriptLocateRetryAtMs = ConcurrentHashMap<String, Long>()

    fun getCachedReaderBlocks(address: String): List<TerminalSemanticBlock> =
        readerHistoryByAddress[address].orEmpty()

    fun updateCachedReaderBlocks(address: String, blocks: List<TerminalSemanticBlock>) {
        if (blocks.any { it is TerminalSemanticBlock.SessionHeaderBlock } &&
            blocks.none { it is TerminalSemanticBlock.UserPromptBlock }
        ) {
            readerHistoryByAddress.remove(address)
        } else if (blocks.isNotEmpty()) {
            readerHistoryByAddress[address] = blocks.filterNot { it is TerminalSemanticBlock.SessionHeaderBlock }
        }
    }

    private data class DigestCacheEntry(
        val cacheKey: String,
        val digest: FleetCardDigest
    )
    private val digestCache = ConcurrentHashMap<String, DigestCacheEntry>()

    private val sequenceCoordinator by lazy {
        TerminalSequenceCoordinator(
            scope = HerdrKeepAliveCoordinator.processScope,
            connectionState = controller.connectionState,
            sessionProvider = { addr -> _activeSession.value?.takeIf { it.address == addr } },
            onToast = { msg -> _toastBanner.value = msg }
        )
    }

    val sequenceStatus: StateFlow<SequenceExecutionStatus?>
        get() = sequenceCoordinator.status

    val fleetDigests: StateFlow<Map<String, FleetCardDigest>> = combine(
        controller.terminals,
        _terminalPreviews
    ) { list, previews ->
        val live = list.filter { it.state == "live" }
        val liveAddrs = live.mapTo(HashSet(live.size)) { it.address }
        digestCache.keys.retainAll(liveAddrs)
        buildMap(live.size) {
            for (t in live) {
                val rawPrev = previews[t.address]
                val key = "${t.turn}|${t.agentStatus.wireName}|${t.agentMessage.orEmpty()}|${rawPrev?.hashCode() ?: 0}"
                val cached = digestCache[t.address]
                val digest = if (cached != null && cached.cacheKey == key) {
                    cached.digest
                } else {
                    val headline = StructuredTerminalReader.extractLatestHeadline(rawPrev, t)
                    val isUrgent = t.agentStatus == AgentStateWord.BLOCKED || t.agentStatus == AgentStateWord.ERROR
                    val actions = if (isUrgent) {
                        StructuredTerminalReader.extractQuickActions(rawPrev, t).take(2)
                    } else {
                        emptyList()
                    }
                    FleetCardDigest(headline = headline, urgentActions = actions).also {
                        digestCache[t.address] = DigestCacheEntry(key, it)
                    }
                }
                put(t.address, digest)
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _requestInputFocusSignal = MutableStateFlow(0L)
    val requestInputFocusSignal: StateFlow<Long> = _requestInputFocusSignal.asStateFlow()

    private val previewCacheKeys = mutableMapOf<String, String>()
    private val previewLastFetchMs = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch {
            controller.terminals.collect { list ->
                val live = list.filter { it.state == "live" }
                val curActive = _activeTerminalSummary.value
                if (curActive != null) {
                    val refreshedActive = live.find { it.address == curActive.address }
                    if (refreshedActive != null && refreshedActive != curActive) {
                        _activeTerminalSummary.value = refreshedActive
                    }
                }
                val liveAddrs = live.map { it.address }.toSet()
                previewCacheKeys.keys.retainAll(liveAddrs)
                previewLastFetchMs.keys.retainAll(liveAddrs)
                sawWorkingByAddress.keys.retainAll(liveAddrs)
                latestStatusByAddress.keys.retainAll(liveAddrs)
                readerHistoryByAddress.keys.retainAll(liveAddrs)
                transcriptSyncMutexes.keys.retainAll(liveAddrs)
                transcriptPendingResync.keys.retainAll(liveAddrs)
                transcriptPendingForce.keys.retainAll(liveAddrs)
                transcriptLocateRetryAtMs.keys.retainAll(liveAddrs)
                if (_transcriptStatesByAddress.value.keys.any { it !in liveAddrs }) {
                    _transcriptStatesByAddress.value = _transcriptStatesByAddress.value.filterKeys { it in liveAddrs }
                }
                val staleQueueJobs = queueDispatchJobs.keys.filter { it !in liveAddrs }
                for (deadAddr in staleQueueJobs) {
                    queueDispatchJobs.remove(deadAddr)?.cancel()
                }
                if (_terminalPreviews.value.keys.any { it !in liveAddrs }) {
                    _terminalPreviews.value = _terminalPreviews.value.filterKeys { it in liveAddrs }
                }
                if (_stagedAttachmentsByAddress.value.keys.any { it !in liveAddrs }) {
                    _stagedAttachmentsByAddress.value = _stagedAttachmentsByAddress.value.filterKeys { it in liveAddrs }
                }
                if (_queuedPromptsByAddress.value.keys.any { it !in liveAddrs }) {
                    _queuedPromptsByAddress.value = _queuedPromptsByAddress.value.filterKeys { it in liveAddrs }
                }
                for (t in live) {
                    if (_queuedPromptsByAddress.value[t.address].orEmpty().isNotEmpty() &&
                        _activeSession.value?.address != t.address
                    ) {
                        syncLiveAgentStatus(t.address, t.agentStatus, t.turn)
                    }
                }
                if (_dispatchedReceipts.value.isNotEmpty()) {
                    val byAddr = live.associateBy { it.address }
                    val nowElapsed = android.os.SystemClock.elapsedRealtime()
                    val filteredReceipts = _dispatchedReceipts.value.filter { (addr, rec) ->
                        val term = byAddr[addr] ?: return@filter false
                        term.turn <= rec.sentAtTurn &&
                            term.agentStatus != AgentStateWord.WORKING &&
                            (nowElapsed - rec.timestampMs) < 12_000L
                    }
                    if (filteredReceipts.size != _dispatchedReceipts.value.size) {
                        _dispatchedReceipts.value = filteredReceipts
                    }
                }

                val nowMs = System.currentTimeMillis()
                val targetsToFetch = live.filter { t ->
                    val needsScreen = t.agentMessage.isNullOrBlank() ||
                        t.agentStatus == AgentStateWord.BLOCKED ||
                        t.agentStatus == AgentStateWord.ERROR
                    if (!needsScreen) return@filter false

                    val key = "${t.turn}:${t.agentStatus.wireName}"
                    val lastMs = previewLastFetchMs[t.address] ?: 0L
                    val shouldRefreshWorking = t.agentStatus == AgentStateWord.WORKING && (nowMs - lastMs >= 8_000L)
                    if (previewCacheKeys[t.address] == key && !shouldRefreshWorking) return@filter false

                    previewCacheKeys[t.address] = key
                    previewLastFetchMs[t.address] = nowMs
                    true
                }
                if (targetsToFetch.isNotEmpty()) {
                    val fetchedPairs = withContext(Dispatchers.IO) {
                        targetsToFetch.map { t ->
                            async {
                                controller.readTerminalPreview(t.address).getOrNull()
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { t.address to it }
                            }
                        }.awaitAll().filterNotNull()
                    }
                    if (fetchedPairs.isNotEmpty()) {
                        _terminalPreviews.value = _terminalPreviews.value + fetchedPairs
                    }
                }
            }
        }
    }

    private var connectJob: Job? = null
    private var autoReconnectJob: Job? = null
    private var sessionJob: Job? = null
    private var sessionWatchJob: Job? = null
    private var keepAliveWatchJob: Job? = null
    private var ztStateJob: Job? = null
    private var updateJob: Job? = null
    private var settingsSaveJob: Job? = null
    private var lastDesiredAttachMode: AttachMode = AttachMode.OBSERVE
    private var modeBeforePtyResize: AttachMode? = null
    private var lastInterruptTapMs: Long = 0L
    private var lastInterruptTargetAddress: String = ""

    fun bindZeroTierTransport(transport: ZeroTierOverlayTransport) {
        ZeroTierOverlayRegistry.transport = transport
        ztStateJob?.cancel()
        ztStateJob = viewModelScope.launch {
            transport.state.collect { state ->
                _zeroTierOverlayState.value = state
            }
        }
    }

    fun showBanner(message: String) {
        _toastBanner.value = message
    }

    fun initializeFromContext(context: Context, intent: Intent?) {
        appContext = context.applicationContext
        HerdrKeepAliveCoordinator.onDisconnectRequested = { disconnectNow() }
        HerdrKeepAliveCoordinator.onNetworkAvailable = {
            if (HerdrKeepAliveCoordinator.userWantsConnectionAlive &&
                _appSettings.value.keepAliveInBackground &&
                connectionState.value !is ConnectionState.Connected
            ) {
                autoReconnectJob?.cancel()
                autoReconnectJob = null
                triggerBackgroundAutoReconnect()
            }
        }
        val sp = context.getSharedPreferences("herdr_connection_prefs", Context.MODE_PRIVATE)
        prefs = sp
        var current = _profile.value
        if (!storageInitialized) {
            storageInitialized = true
            val savedHost = sp.getString("host", null)
            val savedPort = sp.getInt("port", current.port)
            val savedUser = sp.getString("username", null)
            val savedFp = sp.getString("fingerprint", null)
            val savedHcPath = sp.getString("hc_path", null)
            val savedPem = sp.getString("private_key_pem", null)
            val savedKeyPath = sp.getString("private_key_path", null)
            val savedZtNetId = sp.getString("zt_network_id", "")
            val savedZtSecret = sp.getString("zt_identity_secret", "")
            val savedZtPlanetPath = sp.getString("zt_custom_planet_path", "")
            _mobilePtyTargetCols.value = sp.getInt("mobile_pty_target_cols", 68)
            val savedHostClean = savedHost?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("demo://") }
            val explicitUseZt = if (sp.contains("use_builtin_zt")) sp.getBoolean("use_builtin_zt", false) else null
            val migratedLanHost = sp.getString("default_lan_host", null)?.takeIf { it.isNotBlank() }
                ?: if (savedHostClean != null && explicitUseZt == false) savedHostClean else "192.168.1.100"
            val migratedZtHost = sp.getString("default_zt_host", null)?.takeIf { it.isNotBlank() }
                ?: if (savedHostClean != null && explicitUseZt == true) savedHostClean else "10.147.17.100"
            if (!sp.contains("default_lan_host") && savedHostClean != null && explicitUseZt == false) {
                sp.edit().putString("default_lan_host", migratedLanHost).apply()
            }
            if (!sp.contains("default_zt_host") && savedHostClean != null && explicitUseZt == true) {
                sp.edit().putString("default_zt_host", migratedZtHost).apply()
            }
            val savedTheme = HerdrThemePreset.fromId(sp.getString("theme_preset", null))
            val savedTriad = HerdrThemeTriad(
                canvasHex = sp.getString("theme_canvas_hex", "").orEmpty().trim(),
                accentHex = sp.getString("theme_accent_hex", "").orEmpty().trim(),
                secondaryHex = sp.getString("theme_secondary_hex", "").orEmpty().trim()
            )
            HerdrColors.applyPreset(savedTheme, savedTriad)
            _appSettings.value = HerdrAppSettings(
                themePreset = savedTheme,
                themeTriad = savedTriad,
                defaultLanHost = migratedLanHost,
                defaultZeroTierHost = migratedZtHost,
                defaultDesktopFallbackCols = sp.getInt("default_desktop_fallback_cols", 138),
                defaultAttachMode = if (sp.getString("default_attach_mode", "OBSERVE") == "TAKEOVER") {
                    AttachMode.TAKEOVER
                } else {
                    AttachMode.OBSERVE
                },
                autoHideTerminalTopBar = sp.getBoolean("auto_hide_terminal_topbar", true),
                autoHideObserveKeyStrip = sp.getBoolean("auto_hide_observe_keystrip", true),
                keepAliveInBackground = sp.getBoolean("keep_alive_in_background", true)
            )
            ensureKeepAliveMonitor()
            val restoredDims = mutableMapOf<String, Pair<Int, Int>>()
            for ((k, v) in sp.all) {
                if (k.startsWith("desktop_dims_") && v is String) {
                    val addr = k.removePrefix("desktop_dims_")
                    val parts = v.split(',')
                    val c = parts.getOrNull(0)?.toIntOrNull()
                    val r = parts.getOrNull(1)?.toIntOrNull()
                    if (addr.isNotBlank() && c != null && r != null) {
                        restoredDims[addr] = c to r
                    }
                }
            }
            _savedDesktopDims.value = restoredDims
            val resolvedHost = savedHost?.takeIf { it.isNotBlank() } ?: current.host
            val defaultZtHost = _appSettings.value.defaultZeroTierHost
            val savedUseZt = explicitUseZt ?: (resolvedHost == defaultZtHost)
            current = current.copy(
                host = resolvedHost,
                port = savedPort,
                username = savedUser?.takeIf { it.isNotBlank() } ?: current.username,
                expectedHostKeyFingerprint = savedFp?.takeIf { it.isNotBlank() } ?: current.expectedHostKeyFingerprint,
                hcExecutablePath = savedHcPath?.takeIf { it.isNotBlank() } ?: current.hcExecutablePath,
                privateKeyPem = savedPem ?: current.privateKeyPem,
                privateKeyPathHint = savedKeyPath?.takeIf { it.isNotBlank() } ?: current.privateKeyPathHint,
                useBuiltInZeroTier = savedUseZt,
                zeroTierNetworkId = savedZtNetId.orEmpty(),
                zeroTierIdentitySecret = savedZtSecret.orEmpty(),
                zeroTierCustomPlanetPath = savedZtPlanetPath.orEmpty()
            )
            checkForUpdates(silentIfUpToDate = true)
        }

        val candidateFiles = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "id_ed25519_herdr_android") },
            File(context.filesDir, "id_ed25519_herdr_android")
        )
        val localKeyFile = candidateFiles.firstOrNull { it.isFile && it.canRead() }
        if (localKeyFile != null) {
            val loadedPem = runCatching { localKeyFile.readText().trim() }.getOrNull().orEmpty()
            current = current.copy(
                privateKeyPathHint = localKeyFile.absolutePath,
                privateKeyPem = current.privateKeyPem.ifBlank { loadedPem }
            )
        }

        val ztDir = File(context.filesDir, "zerotier_node")
        if (current.zeroTierCustomPlanetPath.isBlank()) {
            val persistedPlanet = listOf(
                File(ztDir, "planet.custom"),
                File(ztDir, "roots")
            ).firstOrNull { it.isFile && it.length() > 0L }
            if (persistedPlanet != null) {
                current = current.copy(zeroTierCustomPlanetPath = persistedPlanet.absolutePath)
            }
        }
        if (current.zeroTierIdentitySecret.isBlank()) {
            val persistedSecretFile = File(ztDir, "identity.secret")
            if (persistedSecretFile.isFile && persistedSecretFile.canRead()) {
                val loadedSecret = runCatching { persistedSecretFile.readText().trim() }.getOrNull().orEmpty()
                if (loadedSecret.isNotBlank()) {
                    current = current.copy(zeroTierIdentitySecret = loadedSecret)
                }
            }
        }

        val extraHost = intent?.getStringExtra("host")?.trim()
        val extraUser = intent?.getStringExtra("username")?.trim()
        val extraFp = intent?.getStringExtra("fingerprint")?.trim()
        val extraKeyPath = intent?.getStringExtra("key_path")?.trim()
        val extraZtSecret = intent?.getStringExtra("zt_identity_secret")?.trim()
        if (!extraHost.isNullOrBlank()) {
            val defaultZtHost = _appSettings.value.defaultZeroTierHost
            current = current.copy(
                host = extraHost,
                useBuiltInZeroTier = if (intent.hasExtra("use_builtin_zt")) {
                    intent.getBooleanExtra("use_builtin_zt", extraHost == defaultZtHost)
                } else {
                    extraHost == defaultZtHost
                }
            )
        }
        if (!extraUser.isNullOrBlank()) current = current.copy(username = extraUser)
        if (!extraFp.isNullOrBlank()) current = current.copy(expectedHostKeyFingerprint = extraFp)
        if (!extraZtSecret.isNullOrBlank()) current = current.copy(zeroTierIdentitySecret = extraZtSecret)
        if (!extraKeyPath.isNullOrBlank()) {
            val keyFile = File(extraKeyPath)
            val pem = if (keyFile.isFile && keyFile.canRead()) {
                runCatching { keyFile.readText().trim() }.getOrNull().orEmpty()
            } else ""
            current = current.copy(
                privateKeyPathHint = extraKeyPath,
                privateKeyPem = pem.ifBlank { current.privateKeyPem }
            )
        }

        _profile.value = current
        saveProfile(current)

        val explicitAutoConnect = intent?.getBooleanExtra("auto_connect", false) == true
        if (explicitAutoConnect) {
            intent?.removeExtra("auto_connect")
        }
        val canAutoConnectSilently = current.privateKeyPem.contains("PRIVATE KEY") || current.host.startsWith("demo://")
        if (explicitAutoConnect || (canAutoConnectSilently && controller.connectionState.value is ConnectionState.Disconnected)) {
            connectNow()
        }
    }

    private fun saveProfile(p: ConnectionProfile) {
        prefs?.edit()?.apply {
            putString("host", p.host)
            putInt("port", p.port)
            putString("username", p.username)
            putString("fingerprint", p.expectedHostKeyFingerprint.orEmpty())
            putString("hc_path", p.hcExecutablePath)
            putString("private_key_pem", p.privateKeyPem)
            putString("private_key_path", p.privateKeyPathHint)
            putBoolean("use_builtin_zt", p.useBuiltInZeroTier)
            putString("zt_network_id", p.zeroTierNetworkId)
            putString("zt_identity_secret", p.zeroTierIdentitySecret)
            putString("zt_custom_planet_path", p.zeroTierCustomPlanetPath)
            apply()
        }
    }

    fun selectTab(tab: WorkspaceTab) {
        val current = _selectedTab.value
        if (tab == current) return
        if (tab == WorkspaceTab.AGENTS) {
            _navHistory.value = emptyList()
        } else {
            val updatedHistory = _navHistory.value.toMutableList()
            if (updatedHistory.lastOrNull() != current) {
                updatedHistory.add(current)
            }
            // Prevent cycles in history stack
            updatedHistory.removeAll { it == tab }
            _navHistory.value = updatedHistory
        }
        _selectedTab.value = tab
    }

    fun navigateBack(): Boolean {
        val history = _navHistory.value
        if (history.isNotEmpty()) {
            val previous = history.last()
            _navHistory.value = history.dropLast(1)
            _selectedTab.value = previous
            return true
        }
        if (_selectedTab.value != WorkspaceTab.AGENTS) {
            _selectedTab.value = WorkspaceTab.AGENTS
            return true
        }
        return false
    }

    fun updateAppSettings(transform: (HerdrAppSettings) -> HerdrAppSettings) {
        val previous = _appSettings.value
        val updated = transform(previous)
        HerdrColors.applyPreset(updated.themePreset, updated.themeTriad)
        _appSettings.value = updated
        if (updated.defaultLanHost != previous.defaultLanHost &&
            !_profile.value.useBuiltInZeroTier &&
            !_profile.value.host.startsWith("demo://") &&
            _profile.value.host == previous.defaultLanHost
        ) {
            updateProfile { it.copy(host = updated.defaultLanHost) }
        }
        if (updated.defaultZeroTierHost != previous.defaultZeroTierHost &&
            _profile.value.useBuiltInZeroTier &&
            _profile.value.host == previous.defaultZeroTierHost
        ) {
            updateProfile { it.copy(host = updated.defaultZeroTierHost) }
        }
        settingsSaveJob?.cancel()
        settingsSaveJob = viewModelScope.launch {
            delay(250)
            val latest = _appSettings.value
            prefs?.edit()?.apply {
                putString("theme_preset", latest.themePreset.id)
                putString("theme_canvas_hex", latest.themeTriad.canvasHex)
                putString("theme_accent_hex", latest.themeTriad.accentHex)
                putString("theme_secondary_hex", latest.themeTriad.secondaryHex)
                putString("default_lan_host", latest.defaultLanHost)
                putString("default_zt_host", latest.defaultZeroTierHost)
                putInt("default_desktop_fallback_cols", latest.defaultDesktopFallbackCols)
                putString("default_attach_mode", latest.defaultAttachMode.name)
                putBoolean("auto_hide_terminal_topbar", latest.autoHideTerminalTopBar)
                putBoolean("auto_hide_observe_keystrip", latest.autoHideObserveKeyStrip)
                putBoolean("keep_alive_in_background", latest.keepAliveInBackground)
                apply()
            }
        }
        if (updated.keepAliveInBackground != previous.keepAliveInBackground) {
            syncKeepAliveNotification()
        }
    }

    fun setDefaultMobilePtyCols(cols: Int) {
        val clamped = cols.coerceIn(48, 160)
        _mobilePtyTargetCols.value = clamped
        prefs?.edit()?.putInt("mobile_pty_target_cols", clamped)?.apply()
    }

    fun updateProfile(transform: (ConnectionProfile) -> ConnectionProfile) {
        val updated = transform(_profile.value)
        _profile.value = updated
        saveProfile(updated)
    }

    fun applyLanPreset() {
        val lanHost = _appSettings.value.defaultLanHost.ifBlank { "192.168.1.100" }
        val updated = _profile.value.copy(
            displayName = "Windows 主力机 (LAN)",
            host = lanHost,
            port = _profile.value.port.takeIf { it in 1..65535 } ?: 22,
            useBuiltInZeroTier = false
        )
        _profile.value = updated
        saveProfile(updated)
    }

    fun applyPreset(isDemoPreview: Boolean) {
        val ztHost = _appSettings.value.defaultZeroTierHost.ifBlank { "10.147.17.100" }
        val updated = if (isDemoPreview) {
            _profile.value.copy(
                displayName = "本机多 Agent 拓扑预览 (t1q ⇄ t1r)",
                host = "demo://preview",
                useBuiltInZeroTier = false
            )
        } else {
            _profile.value.copy(
                displayName = "Windows 主力机 (内置 ZeroTier)",
                host = ztHost,
                port = _profile.value.port.takeIf { it in 1..65535 } ?: 22,
                platform = HostPlatform.WINDOWS,
                useBuiltInZeroTier = true,
                zeroTierNetworkId = _profile.value.zeroTierNetworkId
            )
        }
        _profile.value = updated
        saveProfile(updated)
    }

    fun importZeroTierIdentity(secretText: String) {
        val trimmed = secretText.trim()
        if (trimmed.isBlank()) {
            _toastBanner.value = "identity.secret 内容不能为空"
            return
        }
        viewModelScope.launch {
            val transport = ZeroTierOverlayRegistry.transport
            if (transport == null) {
                updateProfile { it.copy(zeroTierIdentitySecret = trimmed) }
                _toastBanner.value = "已保存 Identity Secret，将在启动内置 ZeroTier 时生效"
                return@launch
            }
            transport.importIdentitySecret(trimmed)
                .onSuccess { nodeId ->
                    updateProfile { it.copy(zeroTierIdentitySecret = trimmed) }
                    _toastBanner.value = "已导入 ZeroTier 节点身份：$nodeId"
                }
                .onFailure { err ->
                    _toastBanner.value = "导入 Identity 失败：${err.message}"
                }
        }
    }

    fun exportZeroTierIdentity(onExported: (String) -> Unit) {
        viewModelScope.launch {
            val transport = ZeroTierOverlayRegistry.transport
            if (transport == null) {
                val saved = _profile.value.zeroTierIdentitySecret
                if (saved.isNotBlank()) {
                    onExported(saved)
                } else {
                    _toastBanner.value = "请先启动一次内置 ZeroTier 节点以生成身份"
                }
                return@launch
            }
            transport.exportIdentitySecret()
                .onSuccess { secret ->
                    onExported(secret)
                    _toastBanner.value = "已导出当前节点 identity.secret"
                }
                .onFailure { err ->
                    _toastBanner.value = "导出 Identity 失败：${err.message}"
                }
        }
    }

    fun importCustomPlanet(bytes: ByteArray, fileName: String) {
        viewModelScope.launch {
            val transport = ZeroTierOverlayRegistry.transport
            if (transport == null) {
                _toastBanner.value = "ZeroTier 引擎尚未初始化"
                return@launch
            }
            transport.importCustomPlanet(bytes, fileName)
                .onSuccess {
                    _toastBanner.value = "已切换为自定义 Planet：$fileName (${bytes.size} B)"
                }
                .onFailure { err ->
                    _toastBanner.value = "导入 Planet 失败：${err.message}"
                }
        }
    }

    fun resetBuiltInPlanet() {
        viewModelScope.launch {
            val transport = ZeroTierOverlayRegistry.transport ?: return@launch
            transport.resetToBuiltInPlanet()
                .onSuccess {
                    updateProfile { it.copy(zeroTierCustomPlanetPath = "") }
                    _toastBanner.value = "已恢复内置/默认 Planet"
                }
                .onFailure { err ->
                    _toastBanner.value = "恢复内置 Planet 失败：${err.message}"
                }
        }
    }

    fun applyHostTarget(host: String, displayName: String) {
        val updated = _profile.value.copy(
            displayName = displayName,
            host = host
        )
        _profile.value = updated
        saveProfile(updated)
    }

    fun checkForUpdates(silentIfUpToDate: Boolean = false) {
        if (_appUpdateState.value.status == UpdateStatus.CHECKING ||
            _appUpdateState.value.status == UpdateStatus.DOWNLOADING
        ) {
            return
        }
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _appUpdateState.value = _appUpdateState.value.copy(
                status = UpdateStatus.CHECKING,
                statusMessage = "正在查询 GitHub Release 最新版本..."
            )
            AppUpdateManager.fetchLatestRelease()
                .onSuccess { release ->
                    val currentVer = _appUpdateState.value.currentVersion
                    if (release == null) {
                        _appUpdateState.value = _appUpdateState.value.copy(
                            status = UpdateStatus.UP_TO_DATE,
                            latestRelease = null,
                            statusMessage = "已是最新版本 (v$currentVer · 仓库暂无已发布 Release)"
                        )
                        if (!silentIfUpToDate) {
                            _toastBanner.value = "当前已是最新版本 v$currentVer"
                        }
                    } else if (AppUpdateManager.isNewerVersion(release.versionName, currentVer)) {
                        _appUpdateState.value = _appUpdateState.value.copy(
                            status = UpdateStatus.AVAILABLE,
                            latestRelease = release,
                            downloadProgressPercent = 0,
                            statusMessage = "发现新版本 ${release.tagName} (${release.releaseTitle})"
                        )
                        _toastBanner.value = "发现新版本 ${release.tagName}，可点击一键更新（保留全部现有配置）"
                    } else {
                        _appUpdateState.value = _appUpdateState.value.copy(
                            status = UpdateStatus.UP_TO_DATE,
                            latestRelease = release,
                            statusMessage = "当前已是最新版本 v$currentVer (远端最新 ${release.tagName})"
                        )
                        if (!silentIfUpToDate) {
                            _toastBanner.value = "当前已是最新版本 v$currentVer"
                        }
                    }
                }
                .onFailure { err ->
                    _appUpdateState.value = _appUpdateState.value.copy(
                        status = UpdateStatus.ERROR,
                        statusMessage = "检查更新失败：${err.message ?: "网络异常"}"
                    )
                    if (!silentIfUpToDate) {
                        _toastBanner.value = "检查更新失败：${err.message}"
                    }
                }
        }
    }

    fun downloadAndInstallUpdate(context: Context) {
        val currentState = _appUpdateState.value
        if (currentState.status == UpdateStatus.DOWNLOADING) return
        val existingPath = currentState.downloadedFilePath
        if (currentState.status == UpdateStatus.READY_TO_INSTALL && !existingPath.isNullOrBlank()) {
            val file = File(existingPath)
            if (file.isFile && file.length() > 0L) {
                triggerApkInstall(context, file)
                return
            }
        }
        val release = currentState.latestRelease
        if (release == null || release.apkDownloadUrl.isBlank()) {
            _toastBanner.value = "该版本 Release 未包含 APK 安装包"
            return
        }
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _appUpdateState.value = currentState.copy(
                status = UpdateStatus.DOWNLOADING,
                downloadProgressPercent = 0,
                statusMessage = "正在从 GitHub Release 下载 ${release.apkAssetName} (0%)..."
            )
            AppUpdateManager.downloadReleaseApk(context, release) { pct ->
                _appUpdateState.value = _appUpdateState.value.copy(
                    status = UpdateStatus.DOWNLOADING,
                    downloadProgressPercent = pct,
                    statusMessage = "正在下载 ${release.tagName} 更新包 ($pct%)..."
                )
            }.onSuccess { apkFile ->
                _appUpdateState.value = _appUpdateState.value.copy(
                    status = UpdateStatus.READY_TO_INSTALL,
                    downloadProgressPercent = 100,
                    downloadedFilePath = apkFile.absolutePath,
                    statusMessage = "已下载 ${release.tagName} (${apkFile.length() / 1024} KB) · 覆盖升级不会修改任何已有配置"
                )
                triggerApkInstall(context, apkFile)
            }.onFailure { err ->
                _appUpdateState.value = _appUpdateState.value.copy(
                    status = UpdateStatus.ERROR,
                    statusMessage = "下载更新包失败：${err.message}"
                )
                _toastBanner.value = "下载更新失败：${err.message}"
            }
        }
    }

    private fun triggerApkInstall(context: Context, apkFile: File) {
        AppUpdateManager.installDownloadedApk(context, apkFile)
            .onSuccess { launchedInstaller ->
                if (!launchedInstaller) {
                    _toastBanner.value = "请先开启「允许安装未知应用」权限，返回后再次点击「安装更新」"
                }
            }
            .onFailure { err ->
                _toastBanner.value = "拉起系统安装器失败：${err.message}"
            }
    }

    fun setStatusFilter(filter: AgentStateWord?) {
        _statusFilter.value = filter
    }

    fun clearBanner() {
        _toastBanner.value = null
    }

    private fun isUnrecoverableErrorKind(kind: String): Boolean {
        return kind in setOf(
            "host_key_untrusted",
            "host_key_changed",
            "private_key_unavailable",
            "invalid_profile",
            "windows_session_permission",
            "hc_missing",
            "hc_not_running"
        )
    }

    private fun syncKeepAliveNotification() {
        val ctx = appContext ?: return
        val keepAliveEnabled = _appSettings.value.keepAliveInBackground
        val state = controller.connectionState.value
        val activeTerm = _activeTerminalSummary.value
        val termCount = controller.terminals.value.count { it.state == "live" }
        val host = (state as? ConnectionState.Connected)?.profile?.host ?: _profile.value.host

        val shouldRunService = keepAliveEnabled && (
            state is ConnectionState.Connected ||
                state is ConnectionState.Connecting ||
                HerdrKeepAliveCoordinator.isReconnectingInBackground
            )

        if (!shouldRunService) {
            HerdrKeepAliveCoordinator.updateStatus(ctx, active = false, title = "", subtitle = "")
            return
        }

        val (title, subtitle) = when (state) {
            is ConnectionState.Connected -> {
                val live = controller.terminals.value.filter { it.state == "live" }
                val blockedList = live.filter {
                    it.agentStatus == AgentStateWord.BLOCKED || it.agentStatus == AgentStateWord.ERROR
                }
                val ackMap = _acknowledgedDoneTurns.value
                val unackedDoneCount = live.count {
                    it.agentStatus == AgentStateWord.DONE && (ackMap[it.address] ?: -1) < it.turn
                }
                val workingCount = live.count { it.agentStatus == AgentStateWord.WORKING }
                val topHeadline = when {
                    blockedList.isNotEmpty() -> {
                        val names = blockedList.take(2).joinToString("、") { it.name ?: it.address }
                        "⚠️ ${blockedList.size} 个等你拍板 ($names)"
                    }
                    unackedDoneCount > 0 -> "✓ ${unackedDoneCount} 个刚完成待派活 · ${workingCount} 个运行中"
                    workingCount > 0 -> "Herdr 牧群运行中 · ${workingCount} 个 Agent 正在工作"
                    else -> "Herdr 牧群在线 · 共 ${termCount} 个终端待命"
                }
                val subText = buildString {
                    append("$host · 共 ${termCount} 个节点")
                    if (activeTerm != null) {
                        append(" · 当前在看 ${activeTerm.address}")
                    }
                }
                topHeadline to subText
            }
            is ConnectionState.Connecting -> {
                "Herdr 正在同步远端会话 · $host" to "正在执行：${state.activeStage.title}"
            }
            is ConnectionState.Failed -> {
                if (HerdrKeepAliveCoordinator.isReconnectingInBackground) {
                    "Herdr 后台自动重连中 · $host" to "检测到连接中断，正在后台静默恢复会话..."
                } else {
                    "Herdr 连接受阻 · $host" to state.message
                }
            }
            ConnectionState.Disconnected -> {
                "Herdr 后台连接待命 · $host" to "准备恢复远端连接..."
            }
        }
        HerdrKeepAliveCoordinator.updateStatus(ctx, active = true, title = title, subtitle = subtitle)
    }

    private fun ensureKeepAliveMonitor() {
        if (keepAliveWatchJob?.isActive == true) return
        keepAliveWatchJob = HerdrKeepAliveCoordinator.processScope.launch {
            var wasConnectedBefore = controller.connectionState.value is ConnectionState.Connected
            controller.connectionState.collect { state ->
                syncKeepAliveNotification()
                when (state) {
                    is ConnectionState.Connected -> {
                        wasConnectedBefore = true
                        HerdrKeepAliveCoordinator.isReconnectingInBackground = false
                        // If there was an active terminal before a brief drop, restore it automatically
                        val targetSummary = _activeTerminalSummary.value
                        val currentSession = _activeSession.value
                        if (targetSummary != null && (currentSession == null || !currentSession.isAttached.value)) {
                            reattachSavedTerminalSilently(targetSummary, lastDesiredAttachMode)
                        }
                    }
                    is ConnectionState.Failed -> {
                        val shouldAutoReconnect =
                            HerdrKeepAliveCoordinator.userWantsConnectionAlive &&
                                _appSettings.value.keepAliveInBackground &&
                                wasConnectedBefore &&
                                !isUnrecoverableErrorKind(state.errorKind)
                        if (shouldAutoReconnect) {
                            triggerBackgroundAutoReconnect()
                        }
                    }
                    ConnectionState.Disconnected -> {
                        if (!HerdrKeepAliveCoordinator.isReconnectingInBackground) {
                            wasConnectedBefore = false
                        }
                    }
                    is ConnectionState.Connecting -> Unit
                }
            }
        }
    }

    private fun triggerBackgroundAutoReconnect() {
        if (autoReconnectJob?.isActive == true) return
        autoReconnectJob = HerdrKeepAliveCoordinator.processScope.launch {
            HerdrKeepAliveCoordinator.isReconnectingInBackground = true
            syncKeepAliveNotification()
            val delaysMs = listOf(600L, 1500L, 3000L, 6000L, 12000L, 25000L)
            val maxAttempts = 12
            var attemptIndex = 0
            val primaryProfile = _profile.value
            val ztHost = _appSettings.value.defaultZeroTierHost.ifBlank { "10.147.17.100" }
            val lanHost = _appSettings.value.defaultLanHost.ifBlank { "192.168.1.100" }
            val alternateProfile = if (primaryProfile.host.startsWith("demo://")) {
                null
            } else if (primaryProfile.useBuiltInZeroTier) {
                if (lanHost.isNotBlank() && lanHost != primaryProfile.host && !lanHost.startsWith("demo://")) {
                    primaryProfile.copy(
                        displayName = "Windows 主力机 (LAN)",
                        host = lanHost,
                        useBuiltInZeroTier = false
                    )
                } else {
                    null
                }
            } else {
                if (primaryProfile.zeroTierNetworkId.length == 16 && ztHost.isNotBlank()) {
                    primaryProfile.copy(
                        displayName = "Windows 主力机 (ZeroTier)",
                        host = ztHost,
                        useBuiltInZeroTier = true
                    )
                } else {
                    null
                }
            }
            while (isActive &&
                attemptIndex < maxAttempts &&
                HerdrKeepAliveCoordinator.userWantsConnectionAlive &&
                _appSettings.value.keepAliveInBackground &&
                controller.connectionState.value !is ConnectionState.Connected
            ) {
                val waitMs = delaysMs[minOf(attemptIndex, delaysMs.lastIndex)]
                delay(waitMs)
                if (!HerdrKeepAliveCoordinator.userWantsConnectionAlive || !_appSettings.value.keepAliveInBackground) {
                    break
                }
                if (controller.connectionState.value is ConnectionState.Connected ||
                    controller.connectionState.value is ConnectionState.Connecting
                ) {
                    break
                }
                val candidate = if (alternateProfile != null && attemptIndex % 2 == 1) {
                    alternateProfile
                } else {
                    primaryProfile
                }
                val result = controller.connect(candidate)
                if (result.isSuccess) {
                    HerdrKeepAliveCoordinator.isReconnectingInBackground = false
                    if (candidate != _profile.value) {
                        _profile.value = candidate
                        saveProfile(candidate)
                        _toastBanner.value = if (candidate.useBuiltInZeroTier) {
                            "后台保活已切换至 ZeroTier 并恢复连接"
                        } else {
                            "后台保活已切换至 LAN 直连并恢复连接"
                        }
                    } else {
                        _toastBanner.value = "后台保活已自动恢复远端连接"
                    }
                    val savedSummary = _activeTerminalSummary.value
                    if (savedSummary != null) {
                        reattachSavedTerminalSilently(savedSummary, lastDesiredAttachMode)
                    }
                    break
                } else {
                    val currentState = controller.connectionState.value
                    if (currentState is ConnectionState.Failed &&
                        isUnrecoverableErrorKind(currentState.errorKind) &&
                        alternateProfile == null
                    ) {
                        break
                    }
                }
                attemptIndex++
            }
            HerdrKeepAliveCoordinator.isReconnectingInBackground = false
            syncKeepAliveNotification()
        }
    }

    private fun reattachSavedTerminalSilently(summary: TerminalSummary, mode: AttachMode) {
        sessionJob?.cancel()
        sessionJob = HerdrKeepAliveCoordinator.processScope.launch {
            val refreshedSummary = controller.terminals.value.find { it.address == summary.address } ?: summary
            _activeTerminalSummary.value = refreshedSummary
            controller.openTerminalSession(refreshedSummary.address, mode)
                .onSuccess { session ->
                    _activeSession.value = session
                    watchSessionAttachment(session, refreshedSummary)
                    syncKeepAliveNotification()
                }
        }
    }

    private fun watchSessionAttachment(session: TerminalSession, summary: TerminalSummary) {
        sessionWatchJob?.cancel()
        sessionWatchJob = HerdrKeepAliveCoordinator.processScope.launch {
            session.isAttached.collect { attached ->
                if (!attached &&
                    _activeSession.value === session &&
                    HerdrKeepAliveCoordinator.userWantsConnectionAlive &&
                    _appSettings.value.keepAliveInBackground &&
                    controller.connectionState.value is ConnectionState.Connected
                ) {
                    delay(600)
                    if (_activeSession.value === session &&
                        HerdrKeepAliveCoordinator.userWantsConnectionAlive &&
                        controller.connectionState.value is ConnectionState.Connected
                    ) {
                        reattachSavedTerminalSilently(summary, session.mode.value)
                    }
                }
            }
        }
    }

    fun connectNow() {
        autoReconnectJob?.cancel()
        connectJob?.cancel()
        HerdrKeepAliveCoordinator.userWantsConnectionAlive = true
        HerdrKeepAliveCoordinator.isReconnectingInBackground = false
        ensureKeepAliveMonitor()
        syncKeepAliveNotification()
        connectJob = HerdrKeepAliveCoordinator.processScope.launch {
            val initialProfile = _profile.value
            val result = controller.connect(initialProfile)
            if (result.isSuccess) {
                syncKeepAliveNotification()
                return@launch
            }
            val failedState = controller.connectionState.value
            // Automatic LAN -> ZeroTier failover if LAN is unreachable and ZeroTier is configured
            val canFailoverToZt = !initialProfile.useBuiltInZeroTier &&
                !initialProfile.host.startsWith("demo://") &&
                initialProfile.zeroTierNetworkId.length == 16 &&
                failedState is ConnectionState.Failed &&
                !isUnrecoverableErrorKind(failedState.errorKind)
            if (canFailoverToZt) {
                val ztHost = _appSettings.value.defaultZeroTierHost.ifBlank { "10.147.17.100" }
                val ztProfile = initialProfile.copy(
                    displayName = "Windows 主力机 (ZeroTier 自动切换)",
                    host = ztHost,
                    useBuiltInZeroTier = true
                )
                _profile.value = ztProfile
                saveProfile(ztProfile)
                _toastBanner.value = "局域网未响应，已自动切换至内置 ZeroTier 尝试连接..."
                val ztResult = controller.connect(ztProfile)
                if (ztResult.isSuccess) {
                    syncKeepAliveNotification()
                    _toastBanner.value = "已通过 ZeroTier 自动连入远端牧群"
                    return@launch
                }
            }
            val finalState = controller.connectionState.value
            if (finalState is ConnectionState.Failed && isUnrecoverableErrorKind(finalState.errorKind)) {
                HerdrKeepAliveCoordinator.userWantsConnectionAlive = false
            }
            syncKeepAliveNotification()
        }
    }

    fun disconnectNow() {
        sequenceCoordinator.cancel()
        HerdrKeepAliveCoordinator.userWantsConnectionAlive = false
        HerdrKeepAliveCoordinator.isReconnectingInBackground = false
        autoReconnectJob?.cancel()
        connectJob?.cancel()
        sessionWatchJob?.cancel()
        sessionJob?.cancel()
        transcriptPendingResync.clear()
        transcriptPendingForce.clear()
        transcriptLocateRetryAtMs.clear()
        _transcriptStatesByAddress.value = emptyMap()
        syncKeepAliveNotification()
        HerdrKeepAliveCoordinator.processScope.launch {
            runCatching { _activeSession.value?.detach() }
            _activeSession.value = null
            _activeTerminalSummary.value = null
            controller.disconnect()
            syncKeepAliveNotification()
            _toastBanner.value = "已安全断开手机端连接（远端终端与 Agent 继续保持运行）"
        }
    }

    fun refreshTerminals() {
        viewModelScope.launch {
            controller.refreshTerminals()
                .onSuccess {
                    syncKeepAliveNotification()
                    _toastBanner.value = "已刷新 ${it.size} 个在线终端"
                }
                .onFailure { _toastBanner.value = "刷新失败：${it.message}" }
        }
    }

    fun attachTerminal(summary: TerminalSummary, initialMode: AttachMode = AttachMode.OBSERVE) {
        if (_activeSession.value?.address != summary.address) {
            sequenceCoordinator.cancel()
        }
        sessionJob?.cancel()
        lastDesiredAttachMode = initialMode
        sessionJob = HerdrKeepAliveCoordinator.processScope.launch {
            runCatching { _activeSession.value?.detach() }
            _activeTerminalSummary.value = summary
            if (summary.cols > 88) {
                rememberDesktopDimensions(summary.address, summary.cols, summary.rows)
            }
            val opened = controller.openTerminalSession(summary.address, initialMode)
            opened.onSuccess { session ->
                _activeSession.value = session
                watchSessionAttachment(session, summary)
                syncKeepAliveNotification()
                selectTab(WorkspaceTab.TERMINAL)
                val curCols = session.dimensions.value.first
                _toastBanner.value = if (curCols <= 88) {
                    "已连接 ${summary.address}（当前保持手机 PTY 宽度 ${curCols}列，切后台不断流）"
                } else if (initialMode == AttachMode.OBSERVE) {
                    "已进入 ${summary.address} 只读模式（${summary.cols}×${summary.rows}，后台保活中）"
                } else {
                    "已进入 ${summary.address} 互动接管模式（后台保活中）"
                }
            }.onFailure { err ->
                _toastBanner.value = "打开终端流失败：${err.message}"
            }
        }
    }

    private fun rememberDesktopDimensions(address: String, cols: Int, rows: Int) {
        if (cols <= 88 || rows <= 0) return
        val updated = _savedDesktopDims.value.toMutableMap()
        updated[address] = cols to rows
        _savedDesktopDims.value = updated
        prefs?.edit()?.putString("desktop_dims_$address", "$cols,$rows")?.apply()
    }

    fun takeoverPtyWidth(targetCols: Int = _mobilePtyTargetCols.value) {
        val session = _activeSession.value ?: return
        val clampedCols = targetCols.coerceIn(48, 160)
        _mobilePtyTargetCols.value = clampedCols
        prefs?.edit()?.putInt("mobile_pty_target_cols", clampedCols)?.apply()

        viewModelScope.launch {
            val (curCols, curRows) = session.dimensions.value
            if (curCols > 88) {
                rememberDesktopDimensions(session.address, curCols, curRows)
                modeBeforePtyResize = session.mode.value
            }
            if (session.mode.value == AttachMode.OBSERVE) {
                runCatching { session.setMode(AttachMode.TAKEOVER) }
            }
            val safeRows = curRows.coerceIn(24, 160)
            session.requestResize(clampedCols, safeRows)
                .onSuccess {
                    val orig = _savedDesktopDims.value[session.address]
                    val origHint = if (orig != null) "（点「释放」还原 ${orig.first}×${orig.second}）" else ""
                    _toastBanner.value = "已同步 ${session.address} 列宽为 ${clampedCols}×${safeRows}$origHint"
                }
                .onFailure { err ->
                    _toastBanner.value = "PTY 宽度同步失败：${err.message}"
                }
        }
    }

    fun releasePtyWidth() {
        val session = _activeSession.value ?: return
        viewModelScope.launch {
            val (_, curRows) = session.dimensions.value
            val fallbackCols = _appSettings.value.defaultDesktopFallbackCols.coerceIn(100, 240)
            val (origCols, origRows) = _savedDesktopDims.value[session.address] ?: (fallbackCols to curRows.coerceAtLeast(44))
            val restoreMode = modeBeforePtyResize ?: lastDesiredAttachMode
            if (session.mode.value == AttachMode.OBSERVE) {
                runCatching { session.setMode(AttachMode.TAKEOVER) }
            }
            val resizeRes = session.requestResize(origCols, origRows)
            if (restoreMode == AttachMode.OBSERVE) {
                runCatching { session.setMode(AttachMode.OBSERVE) }
            }
            modeBeforePtyResize = null
            resizeRes.onSuccess {
                _toastBanner.value = "已释放 ${session.address} PTY 并还原桌面尺寸 ${origCols}×${origRows}"
            }.onFailure { err ->
                _toastBanner.value = "释放 PTY 失败：${err.message}"
            }
        }
    }

    fun toggleAttachMode() {
        val current = _activeSession.value ?: return
        viewModelScope.launch {
            val next = if (current.mode.value == AttachMode.OBSERVE) {
                AttachMode.TAKEOVER
            } else {
                AttachMode.OBSERVE
            }
            lastDesiredAttachMode = next
            current.setMode(next)
            _toastBanner.value = if (next == AttachMode.OBSERVE) {
                "已切换为只读观看模式"
            } else {
                "已切换为接管模式（可输入与发送按键）"
            }
        }
    }

    fun markTerminalDoneAcknowledged(summary: TerminalSummary) {
        val updated = _acknowledgedDoneTurns.value.toMutableMap()
        updated[summary.address] = summary.turn
        _acknowledgedDoneTurns.value = updated
        syncKeepAliveNotification()
    }

    fun getDraftForTerminal(address: String): String = _draftsByAddress.value[address].orEmpty()

    fun updateDraftForTerminal(address: String, draft: String) {
        if (_draftsByAddress.value[address].orEmpty() == draft) return
        val updated = _draftsByAddress.value.toMutableMap()
        if (draft.isEmpty()) {
            updated.remove(address)
        } else {
            updated[address] = draft
        }
        _draftsByAddress.value = updated
    }

    fun openTerminalAndFocusInput(summary: TerminalSummary) {
        markTerminalDoneAcknowledged(summary)
        attachTerminal(summary, AttachMode.TAKEOVER)
        _requestInputFocusSignal.value = android.os.SystemClock.elapsedRealtime()
    }

    fun quickDispatchToTerminal(
        summary: TerminalSummary,
        payload: String,
        isSpecialKey: Boolean = false
    ) {
        if (payload.isEmpty()) return
        markTerminalDoneAcknowledged(summary)
        val displayLabel = if (isSpecialKey) payload.uppercase() else payload
        _dispatchedReceipts.value = _dispatchedReceipts.value + (
            summary.address to DispatchedReceipt(
                address = summary.address,
                text = displayLabel,
                sentAtTurn = summary.turn
            )
            )

        val currentSession = _activeSession.value
        if (currentSession != null && currentSession.address == summary.address) {
            viewModelScope.launch {
                if (currentSession.mode.value == AttachMode.OBSERVE) {
                    runCatching { currentSession.setMode(AttachMode.TAKEOVER) }
                }
                val res = if (isSpecialKey) {
                    currentSession.sendSpecialKey(payload)
                } else {
                    currentSession.sendLine(payload)
                }
                res.onSuccess {
                    _toastBanner.value = "✓ 已向 ${summary.address} 发送批示：$displayLabel"
                }.onFailure { err ->
                    _toastBanner.value = "发送失败：${err.message}"
                }
            }
            return
        }

        // Dispatch directly from Inbox without forcing navigation
        HerdrKeepAliveCoordinator.processScope.launch {
            val opened = controller.openTerminalSession(summary.address, AttachMode.TAKEOVER)
            opened.onSuccess { tempSession ->
                val sendRes = if (isSpecialKey) {
                    tempSession.sendSpecialKey(payload)
                } else {
                    tempSession.sendLine(payload)
                }
                delay(220)
                runCatching { tempSession.detach() }
                controller.refreshTerminals()
                syncKeepAliveNotification()
                sendRes.onSuccess {
                    _toastBanner.value = "✓ 已向 ${summary.address} 下达批示：$displayLabel"
                }.onFailure { err ->
                    _toastBanner.value = "发送失败：${err.message}"
                }
            }.onFailure { err ->
                _toastBanner.value = "无法连接 ${summary.address}：${err.message}"
            }
        }
    }

    fun uploadImagesToActiveTerminal(context: Context, uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val session = _activeSession.value
        val curSummary = _activeTerminalSummary.value
        if (session == null || curSummary == null) {
            _toastBanner.value = "请先打开一个终端再上传图片"
            return
        }
        val targetAddress = session.address
        val appCtx = context.applicationContext
        markTerminalDoneAcknowledged(curSummary)

        viewModelScope.launch {
            if (session.mode.value == AttachMode.OBSERVE) {
                runCatching { session.setMode(AttachMode.TAKEOVER) }
            }
            for ((idx, uri) in uris.withIndex()) {
                val attachmentId = java.util.UUID.randomUUID().toString()
                val placeholder = StagedImageAttachment(
                    id = attachmentId,
                    displayName = "图片 ${idx + 1}",
                    uploading = true
                )
                updateAttachmentsForAddress(targetAddress) { it + placeholder }

                val preparedRes = ImageUploadPreparer.prepare(appCtx, uri)
                if (preparedRes.isFailure) {
                    val errMsg = preparedRes.exceptionOrNull()?.message ?: "读取失败"
                    updateAttachmentsForAddress(targetAddress) { list ->
                        list.map {
                            if (it.id == attachmentId) it.copy(uploading = false, error = errMsg) else it
                        }
                    }
                    _toastBanner.value = "图片处理失败：$errMsg"
                    continue
                }

                val prepared = preparedRes.getOrThrow()
                updateAttachmentsForAddress(targetAddress) { list ->
                    list.map {
                        if (it.id == attachmentId) {
                            it.copy(displayName = prepared.fileName, sizeBytes = prepared.bytes.size.toLong())
                        } else {
                            it
                        }
                    }
                }

                val putRes = session.putFile(prepared.fileName, prepared.bytes)
                putRes.onSuccess { landed ->
                    updateAttachmentsForAddress(targetAddress) { list ->
                        list.map {
                            if (it.id == attachmentId) {
                                it.copy(
                                    sizeBytes = landed.bytes,
                                    remotePath = landed.path,
                                    uploading = false,
                                    error = null
                                )
                            } else {
                                it
                            }
                        }
                    }
                }.onFailure { err ->
                    val errMsg = err.message ?: "上传失败"
                    updateAttachmentsForAddress(targetAddress) { list ->
                        list.map {
                            if (it.id == attachmentId) it.copy(uploading = false, error = errMsg) else it
                        }
                    }
                    _toastBanner.value = "图片上传失败：$errMsg"
                }
            }
        }
    }

    fun removeStagedAttachment(address: String, attachmentId: String) {
        updateAttachmentsForAddress(address) { list -> list.filterNot { it.id == attachmentId } }
    }

    private fun updateAttachmentsForAddress(
        address: String,
        transform: (List<StagedImageAttachment>) -> List<StagedImageAttachment>
    ) {
        val currentMap = _stagedAttachmentsByAddress.value.toMutableMap()
        val updated = transform(currentMap[address].orEmpty())
        if (updated.isEmpty()) {
            currentMap.remove(address)
        } else {
            currentMap[address] = updated
        }
        _stagedAttachmentsByAddress.value = currentMap
    }

    fun sendTerminalSubmission(text: String, isAgentTui: Boolean, submitWithEnter: Boolean = true) {
        val session = _activeSession.value ?: return
        val curSummary = _activeTerminalSummary.value
        val attachments = _stagedAttachmentsByAddress.value[session.address].orEmpty()
        if (attachments.any { it.uploading }) {
            _toastBanner.value = "图片正在上传中，请稍候…"
            return
        }
        val ready = attachments.filter { !it.remotePath.isNullOrBlank() }
        if (ready.isEmpty() && submitWithEnter) {
            sendTerminalLine(text)
            return
        }
        if (ready.isEmpty() && text.isBlank()) return

        updateAttachmentsForAddress(session.address) { emptyList() }
        val targetPaths = ready.mapNotNull { it.remotePath }

        if (submitWithEnter) {
            if (curSummary != null) {
                markTerminalDoneAcknowledged(curSummary)
                val receiptLabel = listOf(
                    text.trim(),
                    ready.joinToString(" ") { "[📎 ${it.displayName}]" }
                ).filter { it.isNotEmpty() }.joinToString(" ")
                _dispatchedReceipts.value = _dispatchedReceipts.value + (
                    session.address to DispatchedReceipt(
                        address = session.address,
                        text = receiptLabel,
                        sentAtTurn = curSummary.turn
                    )
                    )
            }
            updateDraftForTerminal(session.address, "")
        }

        viewModelScope.launch {
            if (session.mode.value == AttachMode.OBSERVE) {
                runCatching { session.setMode(AttachMode.TAKEOVER) }
            }
            session.sendSubmission(
                text = text,
                attachmentPaths = targetPaths,
                isAgentTui = isAgentTui,
                submitWithEnter = submitWithEnter
            ).onSuccess {
                _toastBanner.value = if (submitWithEnter) {
                    "✓ 已发送 ${targetPaths.size} 张图片与指令给 ${session.address}"
                } else {
                    "✓ 已将 ${targetPaths.size} 张图片路径填入 ${session.address} 输入框"
                }
                if (submitWithEnter) {
                    delay(400)
                    controller.refreshTerminals()
                    delay(1400)
                    controller.refreshTerminals()
                }
            }.onFailure { err ->
                _toastBanner.value = err.message ?: "发送失败"
            }
        }
    }

    fun sendTerminalLine(line: String, asRawBytes: Boolean = false) {
        if (line.isEmpty()) return
        val session = _activeSession.value ?: return
        val curSummary = _activeTerminalSummary.value
        if (curSummary != null) {
            markTerminalDoneAcknowledged(curSummary)
            _dispatchedReceipts.value = _dispatchedReceipts.value + (
                session.address to DispatchedReceipt(
                    address = session.address,
                    text = line,
                    sentAtTurn = curSummary.turn
                )
                )
        }
        updateDraftForTerminal(session.address, "")
        viewModelScope.launch {
            if (session.mode.value == AttachMode.OBSERVE) {
                runCatching { session.setMode(AttachMode.TAKEOVER) }
            }
            val result = if (asRawBytes) {
                session.sendRawInput((line + "\r").encodeToByteArray())
            } else {
                session.sendLine(line)
            }
            result.onSuccess {
                _toastBanner.value = "✓ 已发送给 ${session.address}"
                delay(400)
                controller.refreshTerminals()
                delay(1400)
                controller.refreshTerminals()
            }.onFailure { err ->
                _toastBanner.value = err.message ?: "发送失败"
            }
        }
    }

    fun cancelActiveSequence() {
        sequenceCoordinator.cancel()
    }

    fun enqueuePromptForActiveTerminal(
        text: String,
        isAgentTui: Boolean,
        mode: QueuedDispatchMode = QueuedDispatchMode.FOLLOW_UP
    ) {
        val session = _activeSession.value ?: return
        val curSummary = _activeTerminalSummary.value
        val attachments = _stagedAttachmentsByAddress.value[session.address].orEmpty()
        if (attachments.any { it.uploading }) {
            _toastBanner.value = "图片正在上传中，请稍候…"
            return
        }
        val ready = attachments.filter { !it.remotePath.isNullOrBlank() }
        val cleanText = text.trim()
        if (cleanText.isEmpty() && ready.isEmpty()) return

        if (ready.isNotEmpty()) {
            updateAttachmentsForAddress(session.address) { emptyList() }
        }
        updateDraftForTerminal(session.address, "")
        if (curSummary != null) {
            markTerminalDoneAcknowledged(curSummary)
        }
        if (latestStatusByAddress[session.address] == AgentStateWord.WORKING ||
            curSummary?.agentStatus == AgentStateWord.WORKING
        ) {
            sawWorkingByAddress[session.address] = true
        }

        val item = QueuedPromptItem(
            address = session.address,
            text = cleanText,
            attachmentPaths = ready.mapNotNull { it.remotePath },
            attachmentNames = ready.map { it.displayName },
            isAgentTui = isAgentTui,
            mode = mode,
            createdAtMs = System.currentTimeMillis(),
            enqueuedAtTurn = curSummary?.turn ?: 0
        )
        val currentMap = _queuedPromptsByAddress.value.toMutableMap()
        currentMap[session.address] = currentMap[session.address].orEmpty() + item
        _queuedPromptsByAddress.value = currentMap
        _toastBanner.value = if (mode == QueuedDispatchMode.FOLLOW_UP) {
            "⏳ 已加入待发队列，本轮结束后将自动发送"
        } else {
            "📌 已放入待发队列"
        }
    }

    fun sendQueuedPromptNow(address: String, itemId: String) {
        val list = _queuedPromptsByAddress.value[address].orEmpty()
        val item = list.firstOrNull { it.id == itemId } ?: return
        val remaining = list.filterNot { it.id == itemId }
        updateQueueForAddress(address, remaining)
        queueDispatchJobs.remove(address)?.cancel()
        sawWorkingByAddress[address] = false
        dispatchQueuedItem(address, item, isSteer = true)
    }

    fun editQueuedPrompt(address: String, itemId: String) {
        val list = _queuedPromptsByAddress.value[address].orEmpty()
        val item = list.firstOrNull { it.id == itemId } ?: return
        val remaining = list.filterNot { it.id == itemId }
        updateQueueForAddress(address, remaining)
        if (item.attachmentPaths.isNotEmpty()) {
            val restored = item.attachmentPaths.mapIndexed { idx, remotePath ->
                StagedImageAttachment(
                    id = java.util.UUID.randomUUID().toString(),
                    displayName = item.attachmentNames.getOrElse(idx) { "图片 ${idx + 1}" },
                    remotePath = remotePath,
                    uploading = false
                )
            }
            updateAttachmentsForAddress(address) { it + restored }
        }
        if (item.text.isNotBlank()) {
            updateDraftForTerminal(address, item.text)
        }
        _requestInputFocusSignal.value = android.os.SystemClock.elapsedRealtime()
    }

    fun removeQueuedPrompt(address: String, itemId: String) {
        val list = _queuedPromptsByAddress.value[address].orEmpty()
        if (list.none { it.id == itemId }) return
        updateQueueForAddress(address, list.filterNot { it.id == itemId })
    }

    fun moveQueuedPromptToTop(address: String, itemId: String) {
        val list = _queuedPromptsByAddress.value[address].orEmpty()
        val target = list.firstOrNull { it.id == itemId } ?: return
        val reordered = listOf(target) + list.filterNot { it.id == itemId }
        updateQueueForAddress(address, reordered)
    }

    fun syncLiveAgentStatus(address: String, liveStatus: AgentStateWord, currentTurn: Int) {
        latestStatusByAddress[address] = liveStatus
        if (liveStatus == AgentStateWord.WORKING) {
            sawWorkingByAddress[address] = true
            queueDispatchJobs.remove(address)?.cancel()
            return
        }
        if (liveStatus == AgentStateWord.BLOCKED || liveStatus == AgentStateWord.ERROR) {
            queueDispatchJobs.remove(address)?.cancel()
            return
        }
        maybeScheduleQueueDispatch(address, liveStatus, currentTurn)
    }

    private fun maybeScheduleQueueDispatch(address: String, status: AgentStateWord, currentTurn: Int) {
        if (status != AgentStateWord.DONE && status != AgentStateWord.IDLE) return
        val head = _queuedPromptsByAddress.value[address]
            .orEmpty()
            .firstOrNull { it.mode == QueuedDispatchMode.FOLLOW_UP }
            ?: return
        val sawWorking = sawWorkingByAddress[address] == true
        val elapsedSinceEnqueue = System.currentTimeMillis() - head.createdAtMs
        val turnAdvanced = currentTurn > head.enqueuedAtTurn
        if (!turnAdvanced && !(sawWorking && elapsedSinceEnqueue >= 650L)) {
            return
        }
        if (sequenceStatus.value?.address == address) return
        if (queueDispatchJobs[address]?.isActive == true) return

        queueDispatchJobs[address] = HerdrKeepAliveCoordinator.processScope.launch {
            delay(650)
            val recheckedStatus = latestStatusByAddress[address]
                ?: controller.terminals.value.firstOrNull { it.address == address }?.agentStatus
                ?: status
            if (recheckedStatus != AgentStateWord.DONE && recheckedStatus != AgentStateWord.IDLE) {
                queueDispatchJobs.remove(address)
                return@launch
            }
            if (sequenceStatus.value?.address == address) {
                queueDispatchJobs.remove(address)
                return@launch
            }
            val activeSess = _activeSession.value?.takeIf { it.address == address }
            val latestScreen = if (activeSess != null) {
                activeSess.refreshScreenNow(screenOnly = true).getOrNull() ?: activeSess.screenSnapshot.value
            } else {
                controller.readTerminalPreview(address).getOrNull()
            }
            val termSummary = controller.terminals.value.firstOrNull { it.address == address }
                ?: _activeTerminalSummary.value?.takeIf { it.address == address }
            if (!latestScreen.isNullOrBlank() &&
                StructuredTerminalReader.hasInteractiveDialogOnScreen(latestScreen, termSummary)
            ) {
                _toastBanner.value = "终端存在待处理交互面板，已暂停队列自动发送"
                queueDispatchJobs.remove(address)
                return@launch
            }
            val currentList = _queuedPromptsByAddress.value[address].orEmpty()
            val toSend = currentList.firstOrNull { it.mode == QueuedDispatchMode.FOLLOW_UP }
            if (toSend == null) {
                queueDispatchJobs.remove(address)
                return@launch
            }
            updateQueueForAddress(address, currentList.filterNot { it.id == toSend.id })
            sawWorkingByAddress[address] = false
            queueDispatchJobs.remove(address)
            dispatchQueuedItem(address, toSend, isSteer = false)
        }
    }

    private fun updateQueueForAddress(address: String, updatedList: List<QueuedPromptItem>) {
        val map = _queuedPromptsByAddress.value.toMutableMap()
        if (updatedList.isEmpty()) {
            map.remove(address)
        } else {
            map[address] = updatedList
        }
        _queuedPromptsByAddress.value = map
    }

    private fun dispatchQueuedItem(address: String, item: QueuedPromptItem, isSteer: Boolean) {
        val termSummary = controller.terminals.value.firstOrNull { it.address == address }
            ?: _activeTerminalSummary.value?.takeIf { it.address == address }
        if (termSummary != null) {
            markTerminalDoneAcknowledged(termSummary)
        }
        val receiptLabel = buildList {
            if (item.text.isNotBlank()) add(item.text)
            item.attachmentNames.forEach { add("[📎 $it]") }
        }.joinToString(" ")
        if (receiptLabel.isNotBlank()) {
            _dispatchedReceipts.value = _dispatchedReceipts.value + (
                address to DispatchedReceipt(
                    address = address,
                    text = receiptLabel,
                    sentAtTurn = termSummary?.turn ?: item.enqueuedAtTurn
                )
                )
        }

        val activeSess = _activeSession.value?.takeIf { it.address == address }
        HerdrKeepAliveCoordinator.processScope.launch {
            val sendRes = if (activeSess != null) {
                if (activeSess.mode.value == AttachMode.OBSERVE) {
                    runCatching { activeSess.setMode(AttachMode.TAKEOVER) }
                }
                if (item.attachmentPaths.isNotEmpty()) {
                    activeSess.sendSubmission(
                        text = item.text,
                        attachmentPaths = item.attachmentPaths,
                        isAgentTui = item.isAgentTui,
                        submitWithEnter = true
                    )
                } else {
                    activeSess.sendLine(item.text)
                }
            } else {
                val opened = controller.openTerminalSession(address, AttachMode.TAKEOVER)
                opened.mapCatching { tempSession ->
                    try {
                        if (item.attachmentPaths.isNotEmpty()) {
                            tempSession.sendSubmission(
                                text = item.text,
                                attachmentPaths = item.attachmentPaths,
                                isAgentTui = item.isAgentTui,
                                submitWithEnter = true
                            ).getOrThrow()
                        } else {
                            tempSession.sendLine(item.text).getOrThrow()
                        }
                        delay(220)
                    } finally {
                        runCatching { tempSession.detach() }
                    }
                }
            }
            sendRes.onSuccess {
                _toastBanner.value = if (isSteer) {
                    "⚡ 已立即插话发送给 $address"
                } else {
                    "✓ 队列自动续发给 $address"
                }
                delay(400)
                controller.refreshTerminals()
                delay(1400)
                controller.refreshTerminals()
            }.onFailure { err ->
                _toastBanner.value = "队列发送失败：${err.message}"
            }
        }
    }

    fun submitAgyAskAnswer(
        questionIndex: Int,
        selectedNumbers: Set<Int>,
        writeInText: String?
    ) {
        val session = _activeSession.value ?: return
        lastDesiredAttachMode = AttachMode.TAKEOVER
        sequenceCoordinator.execute(
            address = session.address,
            label = "提交问答 Q$questionIndex",
            operations = listOf(
                SequenceOperation.SubmitAskAnswer(
                    questionIndex = questionIndex,
                    selectedNumbers = selectedNumbers,
                    writeInText = writeInText?.takeIf { it.isNotBlank() }
                )
            )
        )
    }

    fun navigateAgyAsk(direction: AskNavDirection, fromQuestionIndex: Int?) {
        val session = _activeSession.value ?: return
        lastDesiredAttachMode = AttachMode.TAKEOVER
        sequenceCoordinator.execute(
            address = session.address,
            label = direction.label,
            operations = listOf(
                SequenceOperation.NavigateAskQuestion(
                    direction = direction,
                    expectedFromQuestionIndex = fromQuestionIndex
                )
            )
        )
    }

    fun cycleAgyModeTo(targetMode: AgyAgentMode) {
        val session = _activeSession.value ?: return
        lastDesiredAttachMode = AttachMode.TAKEOVER
        sequenceCoordinator.execute(
            address = session.address,
            label = "切换模式 · ${targetMode.label}",
            operations = listOf(
                SequenceOperation.EnsureComposerReady,
                SequenceOperation.CycleAgentModeTo(targetMode)
            )
        )
    }

    fun runAgySlashCommand(commandText: String, expectModal: AgySlashModalKind?) {
        val trimmed = commandText.trim()
        if (trimmed.isBlank()) return
        if (trimmed.equals("/btw", ignoreCase = true) || trimmed.startsWith("/btw ", ignoreCase = true)) {
            val btwQuestion = trimmed.drop(4).trim()
            sendBtwQuestion(btwQuestion)
            return
        }
        val session = _activeSession.value ?: return
        if (trimmed.equals("/clear", ignoreCase = true) || trimmed.equals("/new", ignoreCase = true)) {
            readerHistoryByAddress.remove(session.address)
            transcriptLocateRetryAtMs.remove(session.address)
            _transcriptStatesByAddress.value = _transcriptStatesByAddress.value - session.address
        }
        lastDesiredAttachMode = AttachMode.TAKEOVER
        sequenceCoordinator.execute(
            address = session.address,
            label = "执行 $trimmed",
            operations = listOf(
                SequenceOperation.EnsureComposerReady,
                SequenceOperation.SendSlashCommand(command = trimmed, expectModal = expectModal)
            )
        )
        if (trimmed.equals("/clear", ignoreCase = true) || trimmed.equals("/new", ignoreCase = true)) {
            val targetAddr = session.address
            viewModelScope.launch {
                delay(1100)
                syncTerminalTranscript(targetAddr, TerminalDialect.ANTIGRAVITY, forceRelocate = true)
            }
        }
    }

    fun syncTerminalTranscript(
        address: String,
        dialectHint: TerminalDialect? = null,
        forceRelocate: Boolean = false
    ) {
        if (_profile.value.host.startsWith("demo://")) return
        if (controller.connectionState.value !is ConnectionState.Connected) return

        val summary = controller.terminals.value.firstOrNull { it.address == address }
            ?: _activeTerminalSummary.value?.takeIf { it.address == address }
        val agentKind = TranscriptAgentKind.fromAgentHint(summary?.agentApp, dialectHint?.name) ?: return

        val mutex = transcriptSyncMutexes[address] ?: transcriptSyncMutexes.computeIfAbsent(address) { Mutex() }
        if (mutex.isLocked) {
            transcriptPendingResync[address] = true
            if (forceRelocate) transcriptPendingForce[address] = true
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                var nextForce = forceRelocate
                while (true) {
                    runCatching {
                        syncTerminalTranscriptOnce(address, agentKind, nextForce)
                    }
                    val again = transcriptPendingResync.remove(address) == true
                    if (!again) break
                    nextForce = transcriptPendingForce.remove(address) == true
                }
            }
        }
    }

    private suspend fun syncTerminalTranscriptOnce(
        address: String,
        agentKind: TranscriptAgentKind,
        forceRelocate: Boolean
    ) {
        if (controller.connectionState.value !is ConnectionState.Connected) return

        var curState = _transcriptStatesByAddress.value[address]
        if (curState != null && curState.agentKind != agentKind) {
            curState = null
        }

        val nowMs = System.currentTimeMillis()
        val needsLocate = forceRelocate || curState == null || curState.path.isBlank()
        if (needsLocate) {
            val lastMissMs = transcriptLocateRetryAtMs[address] ?: 0L
            if (!forceRelocate && curState?.locateAttempted == true && nowMs - lastMissMs < 1_500L) {
                return
            }
            val detail = controller.getTerminalDetail(address).getOrNull()
                ?: controller.terminals.value.firstOrNull { it.address == address }
                ?: _activeTerminalSummary.value?.takeIf { it.address == address }
                ?: return
            val probePid = detail.rootPid ?: detail.foregroundPid
            val locResult = controller.locateTranscript(agentKind, probePid, detail.cwd)
            if (locResult.isFailure) {
                transcriptLocateRetryAtMs[address] = nowMs
                return
            }
            val loc = locResult.getOrNull()
            if (loc == null) {
                transcriptLocateRetryAtMs[address] = nowMs
                _transcriptStatesByAddress.value = _transcriptStatesByAddress.value + (
                    address to TerminalTranscriptUiState(
                        address = address,
                        agentKind = agentKind,
                        locateAttempted = true
                    )
                    )
                return
            }
            transcriptLocateRetryAtMs.remove(address)
            val sameSession = curState != null && curState.path == loc.path && curState.sessionId == loc.sessionId
            curState = if (sameSession && !forceRelocate) {
                curState!!.copy(locateAttempted = true, fileSize = loc.fileSize)
            } else {
                TerminalTranscriptUiState(
                    address = address,
                    agentKind = loc.agentKind,
                    sessionId = loc.sessionId,
                    path = loc.path,
                    fileSize = loc.fileSize,
                    locateAttempted = true
                )
            }
            _transcriptStatesByAddress.value = _transcriptStatesByAddress.value + (address to curState)
        }

        val activeState = curState ?: return
        if (activeState.path.isBlank()) return

        val chunk = controller.readTranscriptTail(
            agentKind = activeState.agentKind,
            path = activeState.path,
            offset = activeState.tailOffset
        ).getOrElse { return }

        val prev = _transcriptStatesByAddress.value[address] ?: activeState
        val isInitialRead = prev.tailOffset == 0L || chunk.resetRequired
        val mergedEvents = if (isInitialRead) {
            chunk.events
        } else if (chunk.events.isEmpty()) {
            prev.events
        } else {
            mergeTranscriptEvents(prev.events, chunk.events)
        }
        val nextHead = when {
            isInitialRead -> chunk.startOffset
            prev.headOffset == 0L && chunk.startOffset > 0L -> chunk.startOffset
            else -> prev.headOffset
        }
        val nextHasEarlier = when {
            isInitialRead -> chunk.hasEarlier
            else -> prev.hasEarlier || nextHead > 0L
        }
        val latestModel = findLatestModel(mergedEvents) ?: prev.activeModel

        val updated = prev.copy(
            headOffset = nextHead,
            tailOffset = chunk.nextOffset,
            fileSize = chunk.fileSize,
            hasEarlier = nextHasEarlier,
            events = mergedEvents,
            activeModel = latestModel,
            stepOutputs = if (chunk.resetRequired) emptyMap() else prev.stepOutputs
        )
        _transcriptStatesByAddress.value = _transcriptStatesByAddress.value + (address to updated)
    }

    fun loadEarlierTranscriptTurns(address: String) {
        val current = _transcriptStatesByAddress.value[address] ?: return
        if (!current.hasEarlier || current.isLoadingEarlier || current.path.isBlank() || current.headOffset <= 0L) {
            return
        }
        _transcriptStatesByAddress.value = _transcriptStatesByAddress.value + (
            address to current.copy(isLoadingEarlier = true)
            )
        viewModelScope.launch(Dispatchers.IO) {
            val res = controller.readTranscriptHead(
                agentKind = current.agentKind,
                path = current.path,
                beforeOffset = current.headOffset
            )
            val latest = _transcriptStatesByAddress.value[address] ?: return@launch
            res.onSuccess { chunk ->
                val merged = mergeTranscriptEvents(chunk.events, latest.events)
                val nextHead = minOf(latest.headOffset, chunk.startOffset)
                _transcriptStatesByAddress.value = _transcriptStatesByAddress.value + (
                    address to latest.copy(
                        headOffset = nextHead,
                        hasEarlier = chunk.hasEarlier && nextHead > 0L,
                        isLoadingEarlier = false,
                        events = merged,
                        activeModel = latest.activeModel ?: findLatestModel(merged)
                    )
                    )
            }.onFailure { err ->
                _transcriptStatesByAddress.value = _transcriptStatesByAddress.value + (
                    address to latest.copy(isLoadingEarlier = false)
                    )
                _toastBanner.value = "加载更早轮次失败：${err.message}"
            }
        }
    }

    fun loadTranscriptStepOutput(address: String, stepIndex: Int) {
        if (stepIndex < 0) return
        val current = _transcriptStatesByAddress.value[address] ?: return
        if (current.path.isBlank()) return
        if (stepIndex in current.stepOutputs || stepIndex in current.loadingSteps) return

        _transcriptStatesByAddress.value = _transcriptStatesByAddress.value + (
            address to current.copy(loadingSteps = current.loadingSteps + stepIndex)
            )
        viewModelScope.launch(Dispatchers.IO) {
            val res = controller.readTranscriptStepOutput(current.agentKind, current.path, stepIndex)
            val latest = _transcriptStatesByAddress.value[address] ?: return@launch
            res.onSuccess { text ->
                val display = text.ifBlank { "(该步骤无文本输出)" }
                _transcriptStatesByAddress.value = _transcriptStatesByAddress.value + (
                    address to latest.copy(
                        loadingSteps = latest.loadingSteps - stepIndex,
                        stepOutputs = latest.stepOutputs + (stepIndex to display)
                    )
                    )
            }.onFailure { err ->
                _transcriptStatesByAddress.value = _transcriptStatesByAddress.value + (
                    address to latest.copy(loadingSteps = latest.loadingSteps - stepIndex)
                    )
                _toastBanner.value = "读取步骤 #$stepIndex 输出失败：${err.message}"
            }
        }
    }

    private fun mergeTranscriptEvents(
        base: List<TranscriptRawEvent>,
        added: List<TranscriptRawEvent>
    ): List<TranscriptRawEvent> {
        if (base.isEmpty()) return added
        if (added.isEmpty()) return base
        val out = ArrayList<TranscriptRawEvent>(base.size + added.size)
        val seen = HashSet<String>(base.size + added.size)
        for (ev in (base + added)) {
            val extra = when (ev) {
                is TranscriptRawEvent.ToolStatusUpdate -> "err:${ev.callId}"
                is TranscriptRawEvent.UserMessage -> "u:${ev.text.hashCode()}"
                is TranscriptRawEvent.AssistantStep -> "a:${ev.stepIndex ?: ev.markdownContent.hashCode()}"
                is TranscriptRawEvent.TokenUpdate -> "t:${ev.model.orEmpty()}:${ev.outputTokens}"
            }
            val key = "${ev.lineByteOffset}#$extra"
            if (seen.add(key)) out.add(ev)
        }
        return out
    }

    private fun findLatestModel(events: List<TranscriptRawEvent>): String? {
        for (idx in events.lastIndex downTo 0) {
            when (val ev = events[idx]) {
                is TranscriptRawEvent.AssistantStep -> if (!ev.model.isNullOrBlank()) return ev.model
                is TranscriptRawEvent.TokenUpdate -> if (!ev.model.isNullOrBlank()) return ev.model
                is TranscriptRawEvent.UserMessage -> if (!ev.modelChange.isNullOrBlank()) return ev.modelChange
                is TranscriptRawEvent.ToolStatusUpdate -> Unit
            }
        }
        return null
    }

    fun syncLiveBtwCard(address: String, liveCard: AgyBtwCardState?) {
        val currentMap = _btwHistoryByAddress.value
        val existing = currentMap[address].orEmpty()
        if (liveCard == null) {
            if (existing.any { it.isRunning }) {
                val settled = existing.map {
                    if (it.isRunning) it.copy(isRunning = false, statusSummary = it.statusSummary.ifBlank { "已结束" }) else it
                }
                _btwHistoryByAddress.value = currentMap + (address to settled)
            }
            return
        }
        if (_viewingBtwHistoryAddress.value == address) {
            _viewingBtwHistoryAddress.value = null
        }
        val liveTurns = liveCard.turns.ifEmpty {
            listOf(
                AgyBtwTurn(
                    question = liveCard.question,
                    isRunning = liveCard.isRunning,
                    statusSummary = liveCard.statusSummary,
                    toolSteps = liveCard.toolSteps,
                    answerText = liveCard.answerText,
                    isError = liveCard.isError
                )
            )
        }
        val merged = existing.toMutableList()
        val tailSearchFloor = (merged.size - liveTurns.size.coerceAtLeast(1) - 1).coerceAtLeast(0)
        for (turn in liveTurns) {
            val normQ = turn.question.trim()
            val matchIdx = (merged.lastIndex downTo tailSearchFloor).firstOrNull { idx ->
                merged[idx].question.trim().equals(normQ, ignoreCase = true)
            }
            if (matchIdx != null) {
                val prev = merged[matchIdx]
                merged[matchIdx] = turn.copy(
                    statusSummary = turn.statusSummary.ifBlank { prev.statusSummary },
                    toolSteps = if (turn.toolSteps.isNotEmpty()) turn.toolSteps else prev.toolSteps,
                    answerText = if (turn.answerText.isNotBlank() || turn.isRunning) turn.answerText else prev.answerText
                )
            } else {
                merged.add(turn)
            }
        }
        val capped = if (merged.size > 30) merged.takeLast(30) else merged
        if (capped != existing) {
            _btwHistoryByAddress.value = currentMap + (address to capped)
        }
    }

    fun openBtwHistoryViewer() {
        val addr = _activeSession.value?.address ?: return
        _viewingBtwHistoryAddress.value = addr
    }

    fun sendBtwQuestion(question: String) {
        val cleanQuestion = question.trim()
        if (cleanQuestion.isEmpty()) {
            _toastBanner.value = "请输入 /btw 问题内容"
            return
        }
        val session = _activeSession.value ?: return
        if (_viewingBtwHistoryAddress.value == session.address) {
            _viewingBtwHistoryAddress.value = null
        }
        lastDesiredAttachMode = AttachMode.TAKEOVER
        val liveBtw = AgyInteractiveParser.inspectRaw(session.screenSnapshot.value).activeBtwCard
        val sameQuestionActive = liveBtw != null && liveBtw.question.trim().equals(cleanQuestion, ignoreCase = true)
        val ops = buildList {
            add(SequenceOperation.EnsureComposerReady)
            if (sameQuestionActive) {
                add(SequenceOperation.DismissBtwCard)
                add(SequenceOperation.SendBtwQuestion(cleanQuestion))
            } else {
                add(
                    SequenceOperation.SendBtwQuestion(
                        question = cleanQuestion,
                        previousQuestion = liveBtw?.question,
                        previousTurnCount = liveBtw?.turns?.size ?: 0
                    )
                )
            }
        }
        sequenceCoordinator.execute(
            address = session.address,
            label = "旁路快问 /btw",
            operations = ops
        )
    }

    fun dismissBtwCard() {
        val session = _activeSession.value ?: return
        if (_viewingBtwHistoryAddress.value == session.address) {
            _viewingBtwHistoryAddress.value = null
        }
        val hasLiveBtw = AgyInteractiveParser.inspectRaw(session.screenSnapshot.value).activeBtwCard != null
        if (!hasLiveBtw) return
        lastDesiredAttachMode = AttachMode.TAKEOVER
        sequenceCoordinator.execute(
            address = session.address,
            label = "关闭旁路快问",
            operations = listOf(SequenceOperation.DismissBtwCard)
        )
    }

    fun toggleBtwExpand() {
        sendSpecialKey("tab")
    }

    fun selectAgyModalItem(
        expectedKind: AgySlashModalKind,
        targetIndex: Int,
        confirmWithEnter: Boolean,
        titleHint: String?
    ) {
        val session = _activeSession.value ?: return
        lastDesiredAttachMode = AttachMode.TAKEOVER
        sequenceCoordinator.execute(
            address = session.address,
            label = titleHint?.let { "选择 $it" } ?: "选择面板项 #${targetIndex + 1}",
            operations = listOf(
                SequenceOperation.ModalSelectItem(
                    expectedKind = expectedKind,
                    targetIndex = targetIndex,
                    confirmWithEnter = confirmWithEnter,
                    targetTitleHint = titleHint
                )
            )
        )
    }

    fun stepAgyModalValue(
        expectedKind: AgySlashModalKind,
        targetIndex: Int,
        moveRight: Boolean,
        labelHint: String?
    ) {
        val session = _activeSession.value ?: return
        lastDesiredAttachMode = AttachMode.TAKEOVER
        sequenceCoordinator.execute(
            address = session.address,
            label = labelHint?.let { "调整 $it" } ?: "调整面板设置",
            operations = listOf(
                SequenceOperation.ModalStepItemValue(
                    expectedKind = expectedKind,
                    targetIndex = targetIndex,
                    moveRight = moveRight,
                    itemLabelHint = labelHint
                )
            )
        )
    }

    fun switchAgyModalTab(expectedKind: AgySlashModalKind, forward: Boolean) {
        val session = _activeSession.value ?: return
        lastDesiredAttachMode = AttachMode.TAKEOVER
        sequenceCoordinator.execute(
            address = session.address,
            label = if (forward) "下一分页" else "上一分页",
            operations = listOf(
                SequenceOperation.ModalSwitchTab(expectedKind = expectedKind, forward = forward)
            )
        )
    }

    fun filterAgyModal(expectedKind: AgySlashModalKind, query: String) {
        val session = _activeSession.value ?: return
        lastDesiredAttachMode = AttachMode.TAKEOVER
        sequenceCoordinator.execute(
            address = session.address,
            label = "筛选 ${query.trim()}",
            operations = listOf(
                SequenceOperation.ModalFilterText(expectedKind = expectedKind, query = query)
            )
        )
    }

    fun sendAgyModalKey(expectedKind: AgySlashModalKind, keyOrText: String, isSpecialKey: Boolean) {
        val session = _activeSession.value ?: return
        lastDesiredAttachMode = AttachMode.TAKEOVER
        sequenceCoordinator.execute(
            address = session.address,
            label = "操作 $keyOrText",
            operations = listOf(
                SequenceOperation.ModalRawKey(
                    expectedKind = expectedKind,
                    keyOrText = keyOrText,
                    isSpecialKey = isSpecialKey
                )
            )
        )
    }

    fun closeAgyModal() {
        val session = _activeSession.value ?: return
        lastDesiredAttachMode = AttachMode.TAKEOVER
        sequenceCoordinator.execute(
            address = session.address,
            label = "退出当前面板",
            operations = listOf(SequenceOperation.CloseSlashModal)
        )
    }

    fun sendSpecialKey(keyName: String) {
        val session = _activeSession.value ?: return
        val curSummary = _activeTerminalSummary.value
        if (curSummary != null) {
            markTerminalDoneAcknowledged(curSummary)
        }
        viewModelScope.launch {
            if (session.mode.value == AttachMode.OBSERVE) {
                runCatching { session.setMode(AttachMode.TAKEOVER) }
            }
            session.sendSpecialKey(keyName).onFailure { err ->
                _toastBanner.value = err.message ?: "按键发送被拒绝"
            }
        }
    }

    fun interruptActiveTerminal() {
        val session = _activeSession.value ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastInterruptTargetAddress != session.address || now - lastInterruptTapMs > 2500L) {
            lastInterruptTargetAddress = session.address
            lastInterruptTapMs = now
            _toastBanner.value = "再按一次 CTRL+C 确认中断 ${session.address}"
            return
        }
        lastInterruptTapMs = 0L
        viewModelScope.launch {
            if (session.mode.value == AttachMode.OBSERVE) {
                runCatching { session.setMode(AttachMode.TAKEOVER) }
            }
            session.interrupt()
                .onSuccess { _toastBanner.value = "已向 ${session.address} 发送中断指令" }
                .onFailure { _toastBanner.value = "中断失败：${it.message}" }
        }
    }

    private fun rankOf(word: AgentStateWord): Int = when (word) {
        AgentStateWord.BLOCKED, AgentStateWord.ERROR -> 4
        AgentStateWord.DONE -> 3
        AgentStateWord.WORKING -> 2
        AgentStateWord.IDLE -> 1
        AgentStateWord.NONE -> 0
    }

    private fun matchesStatusFilter(status: AgentStateWord, filter: AgentStateWord): Boolean {
        return when (filter) {
            AgentStateWord.BLOCKED -> status == AgentStateWord.BLOCKED || status == AgentStateWord.ERROR
            AgentStateWord.IDLE -> status == AgentStateWord.IDLE || status == AgentStateWord.NONE
            else -> status == filter
        }
    }

    private fun buildAgentTree(
        allTerminals: List<TerminalSummary>,
        filter: AgentStateWord?
    ): List<AgentTreeNode> {
        val liveTerminals = allTerminals.filter { it.state == "live" }
        val byAddress = liveTerminals.associateBy { it.address }

        fun collectSubtree(parentAddress: String, lead: List<String>, depth: Int): List<AgentTreeNode> {
            val children = liveTerminals.filter { it.parent == parentAddress }
            return children.flatMapIndexed { index, child ->
                val isLast = index == children.lastIndex
                val connector = if (isLast) "└" else "├"
                val node = AgentTreeNode(
                    summary = child,
                    treeConnectors = lead + connector,
                    depth = depth,
                    effectiveUrgencyRank = rankOf(child.agentStatus)
                )
                val nextLead = lead + if (isLast) " " else "│"
                listOf(node) + collectSubtree(child.address, nextLead, depth + 1)
            }
        }

        val roots = liveTerminals.filter { it.parent == null || it.parent !in byAddress }
        val rootGroups = roots.map { root ->
            val kids = collectSubtree(root.address, emptyList(), 1)
            val maxUrgency = maxOf(
                rankOf(root.agentStatus),
                kids.maxOfOrNull { it.effectiveUrgencyRank } ?: 0
            )
            val rootNode = AgentTreeNode(
                summary = root,
                treeConnectors = emptyList(),
                depth = 0,
                effectiveUrgencyRank = maxUrgency
            )
            listOf(rootNode) + kids
        }.sortedWith(
            compareByDescending<List<AgentTreeNode>> { it.first().effectiveUrgencyRank }
                .thenByDescending { it.first().summary.turn }
        )

        val flattened = rootGroups.flatten()
        if (filter == null) return flattened
        val matched = flattened.filter { matchesStatusFilter(it.summary.agentStatus, filter) }
        val matchedAddresses = matched.map { it.summary.address }.toSet()
        return matched.map { node ->
            if (node.depth > 0 && node.summary.parent !in matchedAddresses) {
                node.copy(depth = 0, treeConnectors = emptyList())
            } else {
                node
            }
        }
    }
}
