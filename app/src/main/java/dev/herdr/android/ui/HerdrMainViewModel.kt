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
import dev.herdr.android.update.AppUpdateManager
import dev.herdr.android.update.AppUpdateState
import dev.herdr.android.update.UpdateStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class WorkspaceTab(val title: String, val badgeCode: String) {
    CONNECTION("连接与诊断", "CONN"),
    AGENTS("Agents 拓扑", "TREE"),
    TERMINAL("终端接管", "TERM"),
    SETTINGS("默认参数设置", "CONF")
}

data class HerdrAppSettings(
    val defaultLanHost: String = "192.168.1.100",
    val defaultZeroTierHost: String = "10.147.17.100",
    val defaultDesktopFallbackCols: Int = 138,
    val defaultAttachMode: AttachMode = AttachMode.OBSERVE,
    val autoHideTerminalTopBar: Boolean = true,
    val autoHideObserveKeyStrip: Boolean = true
)

data class AgentTreeNode(
    val summary: TerminalSummary,
    val treeConnectors: List<String> = emptyList(),
    val depth: Int = 0,
    val effectiveUrgencyRank: Int = 0
)

class HerdrMainViewModel(
    private val controller: HerdrConnectionController = HerdrConnectionControllerFactory.create()
) : ViewModel() {

    private var prefs: SharedPreferences? = null
    private var storageInitialized = false

    private val _selectedTab = MutableStateFlow(WorkspaceTab.CONNECTION)
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

    private val _statusFilter = MutableStateFlow<AgentStateWord?>(null)
    val statusFilter: StateFlow<AgentStateWord?> = _statusFilter.asStateFlow()

    val agentTreeNodes: StateFlow<List<AgentTreeNode>> = combine(
        controller.terminals,
        _statusFilter
    ) { allTerminals, filter ->
        buildAgentTree(allTerminals, filter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeSession = MutableStateFlow<TerminalSession?>(null)
    val activeSession: StateFlow<TerminalSession?> = _activeSession.asStateFlow()

    private val _activeTerminalSummary = MutableStateFlow<TerminalSummary?>(null)
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

    private var sessionJob: Job? = null
    private var ztStateJob: Job? = null
    private var updateJob: Job? = null

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
            _appSettings.value = HerdrAppSettings(
                defaultLanHost = migratedLanHost,
                defaultZeroTierHost = migratedZtHost,
                defaultDesktopFallbackCols = sp.getInt("default_desktop_fallback_cols", 138),
                defaultAttachMode = if (sp.getString("default_attach_mode", "OBSERVE") == "TAKEOVER") {
                    AttachMode.TAKEOVER
                } else {
                    AttachMode.OBSERVE
                },
                autoHideTerminalTopBar = sp.getBoolean("auto_hide_terminal_topbar", true),
                autoHideObserveKeyStrip = sp.getBoolean("auto_hide_observe_keystrip", true)
            )
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

        if (intent?.getBooleanExtra("auto_connect", false) == true) {
            intent.removeExtra("auto_connect")
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
        if (tab == WorkspaceTab.CONNECTION) {
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
        if (_selectedTab.value != WorkspaceTab.CONNECTION) {
            _selectedTab.value = WorkspaceTab.CONNECTION
            return true
        }
        return false
    }

    fun updateAppSettings(transform: (HerdrAppSettings) -> HerdrAppSettings) {
        val updated = transform(_appSettings.value)
        _appSettings.value = updated
        prefs?.edit()?.apply {
            putString("default_lan_host", updated.defaultLanHost)
            putString("default_zt_host", updated.defaultZeroTierHost)
            putInt("default_desktop_fallback_cols", updated.defaultDesktopFallbackCols)
            putString("default_attach_mode", updated.defaultAttachMode.name)
            putBoolean("auto_hide_terminal_topbar", updated.autoHideTerminalTopBar)
            putBoolean("auto_hide_observe_keystrip", updated.autoHideObserveKeyStrip)
            apply()
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

    fun connectNow() {
        viewModelScope.launch {
            val result = controller.connect(_profile.value)
            result.onSuccess {
                _toastBanner.value = "已通过 hc connect 同步远端终端拓扑"
            }.onFailure { err ->
                _toastBanner.value = "连接未完成：${err.message ?: "请检查四步诊断详情"}"
            }
        }
    }

    fun disconnectNow() {
        viewModelScope.launch {
            _activeSession.value?.detach()
            _activeSession.value = null
            controller.disconnect()
            _toastBanner.value = "已安全断开手机端连接（远端终端与 Agent 继续保持运行）"
        }
    }

    fun refreshTerminals() {
        viewModelScope.launch {
            controller.refreshTerminals()
                .onSuccess { _toastBanner.value = "已刷新 ${it.size} 个在线终端" }
                .onFailure { _toastBanner.value = "刷新失败：${it.message}" }
        }
    }

    fun attachTerminal(summary: TerminalSummary, initialMode: AttachMode = AttachMode.OBSERVE) {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            _activeSession.value?.detach()
            _activeTerminalSummary.value = summary
            if (summary.cols > 88) {
                rememberDesktopDimensions(summary.address, summary.cols, summary.rows)
            }
            val opened = controller.openTerminalSession(summary.address, initialMode)
            opened.onSuccess { session ->
                _activeSession.value = session
                selectTab(WorkspaceTab.TERMINAL)
                val curCols = session.dimensions.value.first
                _toastBanner.value = if (curCols <= 88) {
                    "已连接 ${summary.address}（当前保持手机 PTY 宽度 ${curCols}列，断联不重置）"
                } else if (initialMode == AttachMode.OBSERVE) {
                    "已进入 ${summary.address} 只读模式（${summary.cols}×${summary.rows}，点「PTY 接管」可同步手机宽度）"
                } else {
                    "已进入 ${summary.address} 互动接管模式"
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
            }
            if (session.mode.value == AttachMode.OBSERVE) {
                runCatching { session.setMode(AttachMode.TAKEOVER) }
            }
            val safeRows = curRows.coerceIn(24, 160)
            session.requestResize(clampedCols, safeRows)
                .onSuccess {
                    val orig = _savedDesktopDims.value[session.address]
                    val origHint = if (orig != null) "（点「释放 PTY」可还原 ${orig.first}×${orig.second}）" else ""
                    _toastBanner.value = "已接管 ${session.address} PTY 宽度为 ${clampedCols}×${safeRows}$origHint"
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
            if (session.mode.value == AttachMode.OBSERVE) {
                runCatching { session.setMode(AttachMode.TAKEOVER) }
            }
            val resizeRes = session.requestResize(origCols, origRows)
            runCatching { session.setMode(AttachMode.OBSERVE) }
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
            current.setMode(next)
            _toastBanner.value = if (next == AttachMode.OBSERVE) {
                "已切换为 Observe 只读观看模式"
            } else {
                "已切换为 Takeover 接管模式（可输入与发送按键）"
            }
        }
    }

    fun sendTerminalLine(line: String, asRawBytes: Boolean = false) {
        if (line.isEmpty()) return
        val session = _activeSession.value ?: return
        viewModelScope.launch {
            val result = if (asRawBytes) {
                session.sendRawInput((line + "\r").encodeToByteArray())
            } else {
                session.sendLine(line)
            }
            result.onFailure { err ->
                _toastBanner.value = err.message ?: "发送失败"
            }
        }
    }

    fun sendSpecialKey(keyName: String) {
        val session = _activeSession.value ?: return
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
        return flattened.filter { it.summary.agentStatus == filter }
    }
}
