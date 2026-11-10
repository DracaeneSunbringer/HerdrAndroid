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
import dev.herdr.android.connection.ZeroTierOverlayRegistry
import dev.herdr.android.connection.ZeroTierOverlayState
import dev.herdr.android.connection.ZeroTierOverlayTransport
import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.AttachMode
import dev.herdr.android.protocol.MachineSummary
import dev.herdr.android.protocol.TerminalSession
import dev.herdr.android.protocol.TerminalSummary
import dev.herdr.android.service.HerdrKeepAliveCoordinator
import dev.herdr.android.ui.theme.HerdrColors
import dev.herdr.android.ui.theme.HerdrThemePreset
import dev.herdr.android.ui.theme.HerdrThemeTriad
import dev.herdr.android.update.AppUpdateManager
import dev.herdr.android.update.AppUpdateState
import dev.herdr.android.update.UpdateStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
                if (_terminalPreviews.value.keys.any { it !in liveAddrs }) {
                    _terminalPreviews.value = _terminalPreviews.value.filterKeys { it in liveAddrs }
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
                for (t in live) {
                    val needsScreen = t.agentMessage.isNullOrBlank() ||
                        t.agentStatus == AgentStateWord.BLOCKED ||
                        t.agentStatus == AgentStateWord.ERROR
                    if (!needsScreen) continue

                    val key = "${t.turn}:${t.agentStatus.wireName}"
                    val lastMs = previewLastFetchMs[t.address] ?: 0L
                    val shouldRefreshWorking = t.agentStatus == AgentStateWord.WORKING && (nowMs - lastMs >= 8_000L)
                    if (previewCacheKeys[t.address] == key && !shouldRefreshWorking) continue

                    previewCacheKeys[t.address] = key
                    previewLastFetchMs[t.address] = nowMs
                    controller.readTerminalPreview(t.address).onSuccess { snap ->
                        if (snap.isNotBlank()) {
                            _terminalPreviews.value = _terminalPreviews.value + (t.address to snap)
                        }
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
        prefs?.edit()?.apply {
            putString("theme_preset", updated.themePreset.id)
            putString("theme_canvas_hex", updated.themeTriad.canvasHex)
            putString("theme_accent_hex", updated.themeTriad.accentHex)
            putString("theme_secondary_hex", updated.themeTriad.secondaryHex)
            putString("default_lan_host", updated.defaultLanHost)
            putString("default_zt_host", updated.defaultZeroTierHost)
            putInt("default_desktop_fallback_cols", updated.defaultDesktopFallbackCols)
            putString("default_attach_mode", updated.defaultAttachMode.name)
            putBoolean("auto_hide_terminal_topbar", updated.autoHideTerminalTopBar)
            putBoolean("auto_hide_observe_keystrip", updated.autoHideObserveKeyStrip)
            putBoolean("keep_alive_in_background", updated.keepAliveInBackground)
            apply()
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
            val delaysMs = listOf(1500L, 3000L, 6000L, 12000L, 25000L, 45000L)
            val maxAttempts = 12
            var attemptIndex = 0
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
                val result = controller.connect(_profile.value)
                if (result.isSuccess) {
                    HerdrKeepAliveCoordinator.isReconnectingInBackground = false
                    _toastBanner.value = "后台保活已自动恢复远端连接"
                    val savedSummary = _activeTerminalSummary.value
                    if (savedSummary != null) {
                        reattachSavedTerminalSilently(savedSummary, lastDesiredAttachMode)
                    }
                    break
                } else {
                    val currentState = controller.connectionState.value
                    if (currentState is ConnectionState.Failed && isUnrecoverableErrorKind(currentState.errorKind)) {
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
        HerdrKeepAliveCoordinator.userWantsConnectionAlive = false
        HerdrKeepAliveCoordinator.isReconnectingInBackground = false
        autoReconnectJob?.cancel()
        connectJob?.cancel()
        sessionWatchJob?.cancel()
        sessionJob?.cancel()
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

    fun updateDraftForTerminal(address: String, draft: String) {
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
