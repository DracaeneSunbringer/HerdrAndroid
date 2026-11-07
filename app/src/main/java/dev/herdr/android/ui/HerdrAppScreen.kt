package dev.herdr.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.connection.ConnectionProfile
import dev.herdr.android.connection.ConnectionStage
import dev.herdr.android.connection.ConnectionState
import dev.herdr.android.connection.DiagnosticStageStatus
import dev.herdr.android.connection.HostPlatform
import dev.herdr.android.connection.StageCheckState
import dev.herdr.android.connection.ZeroTierNodePhase
import dev.herdr.android.connection.ZeroTierOverlayState
import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.AttachMode
import dev.herdr.android.protocol.TerminalSession
import dev.herdr.android.protocol.TerminalSummary
import dev.herdr.android.ui.terminal.AnsiTerminalParser
import dev.herdr.android.ui.theme.HerdrColors
import dev.herdr.android.update.AppUpdateManager
import dev.herdr.android.update.AppUpdateState
import dev.herdr.android.update.UpdateStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HerdrAppScreen(viewModel: HerdrMainViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val navHistory by viewModel.navHistory.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val agentNodes by viewModel.agentTreeNodes.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val activeSummary by viewModel.activeTerminalSummary.collectAsState()
    val savedDesktopDims by viewModel.savedDesktopDims.collectAsState()
    val mobilePtyTargetCols by viewModel.mobilePtyTargetCols.collectAsState()
    val zeroTierState by viewModel.zeroTierOverlayState.collectAsState()
    val appUpdateState by viewModel.appUpdateState.collectAsState()
    val toastBanner by viewModel.toastBanner.collectAsState()
    val context = LocalContext.current

    val canGoBack = navHistory.isNotEmpty() || selectedTab != WorkspaceTab.CONNECTION
    BackHandler(enabled = canGoBack) {
        viewModel.navigateBack()
    }

    // Auto-dismiss toast banner after 2.4 seconds so it never permanently occupies screen space
    LaunchedEffect(toastBanner) {
        if (toastBanner != null) {
            delay(2400)
            viewModel.clearBanner()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HerdrColors.Canvas)
            .imePadding()
    ) {
        // Hide global top chrome when inside Level-2 immersive Terminal view!
        AnimatedVisibility(visible = selectedTab != WorkspaceTab.TERMINAL) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopTelemetryBar(
                    connectionState = connectionState,
                    terminalCount = agentNodes.size,
                    hostLabel = profile.host,
                    canNavigateBack = canGoBack,
                    onNavigateBack = { viewModel.navigateBack() }
                )

                WorkspaceNavigationBar(
                    selectedTab = selectedTab,
                    agentCount = agentNodes.size,
                    activeTerminalAddress = activeSession?.address,
                    onSelectTab = viewModel::selectTab
                )

                if (appUpdateState.status == UpdateStatus.AVAILABLE ||
                    appUpdateState.status == UpdateStatus.DOWNLOADING ||
                    appUpdateState.status == UpdateStatus.READY_TO_INSTALL
                ) {
                    val rel = appUpdateState.latestRelease
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(HerdrColors.DoneSubtle)
                            .border(0.5.dp, HerdrColors.Done.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when (appUpdateState.status) {
                                UpdateStatus.DOWNLOADING -> "正在下载更新 ${rel?.tagName.orEmpty()} (${appUpdateState.downloadProgressPercent}%)..."
                                UpdateStatus.READY_TO_INSTALL -> "新版本 ${rel?.tagName.orEmpty()} 已就绪（保留全部现有配置）"
                                else -> "发现新版本 ${rel?.tagName.orEmpty()} · ${rel?.releaseTitle.orEmpty()}"
                            },
                            color = HerdrColors.Foreground,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (appUpdateState.status != UpdateStatus.DOWNLOADING) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(HerdrColors.Done)
                                    .clickable { viewModel.downloadAndInstallUpdate(context) }
                                    .padding(horizontal = 10.dp, vertical = 3.5.dp)
                            ) {
                                Text(
                                    text = if (appUpdateState.status == UpdateStatus.READY_TO_INSTALL) "安装更新" else "立即更新",
                                    color = Color(0xFF04131D),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = toastBanner != null) {
            toastBanner?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HerdrColors.AccentSubtle)
                        .border(0.5.dp, HerdrColors.Accent.copy(alpha = 0.45f))
                        .clickable { viewModel.clearBanner() }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(HerdrColors.Accent)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = msg,
                        color = HerdrColors.Foreground,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "×",
                        color = HerdrColors.Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        maxLines = 1
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                WorkspaceTab.CONNECTION -> ConnectionAndDiagnosticsPane(
                    profile = profile,
                    appSettings = appSettings,
                    connectionState = connectionState,
                    zeroTierState = zeroTierState,
                    agentCount = agentNodes.size,
                    onUpdateProfile = viewModel::updateProfile,
                    onApplyLanPreset = viewModel::applyLanPreset,
                    onApplyPreset = viewModel::applyPreset,
                    onOpenSettings = { viewModel.selectTab(WorkspaceTab.SETTINGS) },
                    onShowBanner = viewModel::showBanner,
                    onConnect = viewModel::connectNow,
                    onDisconnect = viewModel::disconnectNow,
                    onJumpToAgents = { viewModel.selectTab(WorkspaceTab.AGENTS) }
                )

                WorkspaceTab.AGENTS -> AgentsTopologyPane(
                    nodes = agentNodes,
                    currentFilter = statusFilter,
                    onSelectFilter = viewModel::setStatusFilter,
                    onRefresh = viewModel::refreshTerminals,
                    onObserve = { viewModel.attachTerminal(it, appSettings.defaultAttachMode) },
                    onTakeover = { viewModel.attachTerminal(it, AttachMode.TAKEOVER) }
                )

                WorkspaceTab.TERMINAL -> LiveTerminalAttachPane(
                    session = activeSession,
                    summary = activeSummary,
                    appSettings = appSettings,
                    savedDesktopDims = savedDesktopDims,
                    mobilePtyTargetCols = mobilePtyTargetCols,
                    onNavigateBack = { viewModel.navigateBack() },
                    onOpenSettings = { viewModel.selectTab(WorkspaceTab.SETTINGS) },
                    onToggleMode = viewModel::toggleAttachMode,
                    onTakeoverPtyWidth = viewModel::takeoverPtyWidth,
                    onReleasePtyWidth = viewModel::releasePtyWidth,
                    onSendLine = viewModel::sendTerminalLine,
                    onSendSpecialKey = viewModel::sendSpecialKey,
                    onInterrupt = viewModel::interruptActiveTerminal,
                    onPickTerminal = { viewModel.selectTab(WorkspaceTab.AGENTS) }
                )

                WorkspaceTab.SETTINGS -> DefaultSettingsPane(
                    profile = profile,
                    appSettings = appSettings,
                    mobilePtyTargetCols = mobilePtyTargetCols,
                    zeroTierState = zeroTierState,
                    appUpdateState = appUpdateState,
                    onUpdateProfile = viewModel::updateProfile,
                    onUpdateAppSettings = viewModel::updateAppSettings,
                    onSetDefaultMobilePtyCols = viewModel::setDefaultMobilePtyCols,
                    onImportZtIdentity = viewModel::importZeroTierIdentity,
                    onExportZtIdentity = viewModel::exportZeroTierIdentity,
                    onImportCustomPlanet = viewModel::importCustomPlanet,
                    onResetBuiltInPlanet = viewModel::resetBuiltInPlanet,
                    onCheckForUpdates = { viewModel.checkForUpdates(silentIfUpToDate = false) },
                    onDownloadAndInstallUpdate = { viewModel.downloadAndInstallUpdate(context) },
                    onShowBanner = viewModel::showBanner,
                    onNavigateBack = { viewModel.navigateBack() }
                )
            }
        }
    }
}

@Composable
private fun TopTelemetryBar(
    connectionState: ConnectionState,
    terminalCount: Int,
    hostLabel: String,
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HerdrColors.Chrome)
            .border(0.5.dp, HerdrColors.Border)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (canNavigateBack) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(HerdrColors.Raised)
                    .border(1.dp, HerdrColors.Border, RoundedCornerShape(7.dp))
                    .clickable { onNavigateBack() }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "← 返回",
                    color = HerdrColors.Accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    softWrap = false,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(HerdrColors.Raised)
                    .border(1.dp, HerdrColors.Accent.copy(alpha = 0.75f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "H",
                    color = HerdrColors.Accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    softWrap = false,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "HERDR",
                    color = HerdrColors.Foreground,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    softWrap = false,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "GEN-1 · SSH",
                    color = HerdrColors.Accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(HerdrColors.AccentSubtle)
                        .padding(horizontal = 6.dp, vertical = 1.5.dp)
                )
            }
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = when (connectionState) {
                    is ConnectionState.Connected ->
                        "Core ${connectionState.hello.version} (${connectionState.hello.coreName}) · $terminalCount 终端 · $hostLabel"
                    is ConnectionState.Connecting ->
                        "正在握手：${connectionState.activeStage.title}"
                    is ConnectionState.Failed ->
                        "诊断阻塞：${connectionState.failedStage.title}"
                    ConnectionState.Disconnected ->
                        "未连接远端 Core · 点连接建立加密通道"
                },
                color = HerdrColors.Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))

        val (dotColor, pillText) = when (connectionState) {
            is ConnectionState.Connected -> HerdrColors.Done to "LIVE"
            is ConnectionState.Connecting -> HerdrColors.Working to "SYNC"
            is ConnectionState.Failed -> HerdrColors.Blocked to "ERR"
            ConnectionState.Disconnected -> HerdrColors.Idle to "IDLE"
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(HerdrColors.Surface)
                .border(1.dp, dotColor.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.5.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = pillText,
                color = HerdrColors.Foreground,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                softWrap = false,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun WorkspaceNavigationBar(
    selectedTab: WorkspaceTab,
    agentCount: Int,
    activeTerminalAddress: String?,
    onSelectTab: (WorkspaceTab) -> Unit
) {
    val visibleTabs = remember(activeTerminalAddress) {
        if (activeTerminalAddress != null) {
            listOf(
                WorkspaceTab.CONNECTION,
                WorkspaceTab.AGENTS,
                WorkspaceTab.TERMINAL,
                WorkspaceTab.SETTINGS
            )
        } else {
            listOf(
                WorkspaceTab.CONNECTION,
                WorkspaceTab.AGENTS,
                WorkspaceTab.SETTINGS
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HerdrColors.Chrome)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(HerdrColors.Canvas)
                .border(1.dp, HerdrColors.Border, RoundedCornerShape(9.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            visibleTabs.forEach { tab ->
                val selected = tab == selectedTab
                val label = when (tab) {
                    WorkspaceTab.CONNECTION -> "连接台"
                    WorkspaceTab.AGENTS -> "拓扑 ($agentCount)"
                    WorkspaceTab.TERMINAL -> "终端 [${activeTerminalAddress ?: "-"}]"
                    WorkspaceTab.SETTINGS -> "设置 ⚙"
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) HerdrColors.Raised else Color.Transparent)
                        .then(
                            if (selected) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = HerdrColors.Accent.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 8.dp, horizontal = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) HerdrColors.Foreground else HerdrColors.Muted,
                        fontSize = 11.8.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionAndDiagnosticsPane(
    profile: ConnectionProfile,
    appSettings: HerdrAppSettings,
    connectionState: ConnectionState,
    zeroTierState: ZeroTierOverlayState,
    agentCount: Int,
    onUpdateProfile: ((ConnectionProfile) -> ConnectionProfile) -> Unit,
    onApplyLanPreset: () -> Unit,
    onApplyPreset: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onShowBanner: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onJumpToAgents: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var showQuickTargetOverride by remember { mutableStateOf(false) }

    val lanDefaultHost = appSettings.defaultLanHost.ifBlank { "192.168.1.100" }
    val ztDefaultHost = appSettings.defaultZeroTierHost.ifBlank { "10.147.17.100" }
    val isPreview = profile.host.startsWith("demo://")
    val isZeroTier = (!isPreview && (profile.useBuiltInZeroTier || profile.host == ztDefaultHost))
    val isLan = (!isPreview && !isZeroTier)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Level 1 Card: Target Selection & Hierarchical Status Summary
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(HerdrColors.Surface)
                .border(1.dp, HerdrColors.Border, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "选择连接链路",
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                    Text(
                        text = "一级工作台 · 低频凭据与默认参数已收拢至「设置」",
                        color = HerdrColors.Muted,
                        fontSize = 10.5.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                }
                TacticalActionPill(
                    text = "默认参数 ⚙",
                    accent = false,
                    onClick = onOpenSettings
                )
            }

            // 3 Segmented Target Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                PresetTargetCard(
                    title = "局域网",
                    subtitle = lanDefaultHost,
                    selected = isLan,
                    modifier = Modifier.weight(1f),
                    onClick = onApplyLanPreset
                )
                PresetTargetCard(
                    title = "ZeroTier",
                    subtitle = ztDefaultHost,
                    selected = isZeroTier,
                    modifier = Modifier.weight(1f),
                    onClick = { onApplyPreset(false) }
                )
                PresetTargetCard(
                    title = "离线演示",
                    subtitle = "本地拓扑预览",
                    selected = isPreview,
                    modifier = Modifier.weight(1f),
                    onClick = { onApplyPreset(true) }
                )
            }

            // Clean Layered Summary Strip for Current Target (No input clutter!)
            val hasKey = profile.privateKeyPem.contains("PRIVATE KEY")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(HerdrColors.Chrome)
                    .border(1.dp, HerdrColors.Border, RoundedCornerShape(9.dp))
                    .padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isPreview) {
                                "demo://preview (本地多 Agent 树演示)"
                            } else {
                                "${profile.username}@${profile.host}:${profile.port}"
                            },
                            color = HerdrColors.Foreground,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val keyStatusLabel = if (hasKey) "Ed25519 私钥就绪" else "未导入私钥 (点右侧去设置)"
                        val platLabel = when (profile.platform) {
                            HostPlatform.WINDOWS -> "Win OpenSSH"
                            HostPlatform.AUTO -> "Auto"
                            HostPlatform.UNIX -> "Unix"
                        }
                        Text(
                            text = "$platLabel · $keyStatusLabel",
                            color = if (hasKey || isPreview) HerdrColors.Muted else HerdrColors.Working,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TacticalActionPill(
                            text = if (showQuickTargetOverride) "收起微调 ▴" else "临时改地址 ▾",
                            accent = showQuickTargetOverride,
                            onClick = { showQuickTargetOverride = !showQuickTargetOverride }
                        )
                    }
                }

                AnimatedVisibility(visible = showQuickTargetOverride) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TacticalInputField(
                            label = "临时主机地址",
                            value = profile.host,
                            onValueChange = { host -> onUpdateProfile { it.copy(host = host.trim()) } },
                            placeholder = ztDefaultHost,
                            modifier = Modifier.weight(2.2f)
                        )
                        TacticalInputField(
                            label = "端口",
                            value = profile.port.toString(),
                            onValueChange = { p ->
                                val portNum = p.filter { it.isDigit() }.toIntOrNull() ?: 22
                                onUpdateProfile { it.copy(port = portNum) }
                            },
                            placeholder = "22",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(0.9f)
                        )
                    }
                }
            }

            // Built-in ZeroTier Userspace Summary Strip (when ZeroTier selected)
            if (isZeroTier) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(HerdrColors.Chrome)
                        .border(
                            width = 1.dp,
                            color = if (profile.useBuiltInZeroTier) {
                                HerdrColors.Accent.copy(alpha = 0.55f)
                            } else {
                                HerdrColors.Border
                            },
                            shape = RoundedCornerShape(9.dp)
                        )
                        .padding(11.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            val ztDotColor = when {
                                !profile.useBuiltInZeroTier -> HerdrColors.Muted
                                zeroTierState.phase == ZeroTierNodePhase.READY -> HerdrColors.Done
                                zeroTierState.phase == ZeroTierNodePhase.WAITING_AUTH ||
                                    zeroTierState.phase == ZeroTierNodePhase.ERROR -> HerdrColors.Blocked
                                zeroTierState.phase == ZeroTierNodePhase.STOPPED -> HerdrColors.Accent
                                else -> HerdrColors.Working
                            }
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(ztDotColor)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Column {
                                Text(
                                    text = "内置 ZeroTier 用户态流量管理 (libzt)",
                                    color = HerdrColors.Foreground,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (profile.useBuiltInZeroTier) {
                                        "免开 ZerotierFix · 不占系统 VPN 槽位 · 走现有代理分流"
                                    } else {
                                        "当前已关闭内置栈（需外部打开 ZerotierFix）"
                                    },
                                    color = HerdrColors.Muted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        TacticalActionPill(
                            text = if (profile.useBuiltInZeroTier) "内置已启用" else "点此启用",
                            accent = profile.useBuiltInZeroTier,
                            onClick = {
                                onUpdateProfile { it.copy(useBuiltInZeroTier = !it.useBuiltInZeroTier) }
                            }
                        )
                    }

                    if (profile.useBuiltInZeroTier) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(HerdrColors.Canvas)
                                .border(0.8.dp, HerdrColors.Border, RoundedCornerShape(7.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val nodeText = zeroTierState.nodeIdHex.ifBlank { "连接时生成" }
                                val ipText = zeroTierState.assignedIpv4.ifBlank { "待分配" }
                                val pathBadge = zeroTierState.peerPathMode.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                                Text(
                                    text = "Node: $nodeText | IP: $ipText$pathBadge",
                                    color = HerdrColors.Foreground,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "网络 ${profile.zeroTierNetworkId} · ${zeroTierState.phase.label}",
                                    color = if (zeroTierState.phase == ZeroTierNodePhase.WAITING_AUTH) {
                                        HerdrColors.Blocked
                                    } else {
                                        HerdrColors.Muted
                                    },
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (zeroTierState.nodeIdHex.isNotBlank()) {
                                    TacticalActionPill(
                                        text = "复制ID",
                                        accent = false,
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(zeroTierState.nodeIdHex))
                                            onShowBanner("已复制 ZeroTier Node ID: ${zeroTierState.nodeIdHex}")
                                        }
                                    )
                                }
                                TacticalActionPill(
                                    text = "身份/Planet →",
                                    accent = false,
                                    onClick = onOpenSettings
                                )
                            }
                        }
                    }
                }
            }

            // Primary Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isConnected = connectionState is ConnectionState.Connected
                Box(
                    modifier = Modifier
                        .weight(1.25f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(HerdrColors.Accent)
                        .clickable { onConnect() }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isConnected) "重新握手同步" else "建立 HC 加密连接",
                        color = Color(0xFF04131D),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                }

                if (isConnected) {
                    Box(
                        modifier = Modifier
                            .weight(1.15f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(HerdrColors.Raised)
                            .border(1.dp, HerdrColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .clickable { onJumpToAgents() }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "进入拓扑 ($agentCount) →",
                            color = HerdrColors.Foreground,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.2.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .height(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(HerdrColors.BlockedSubtle)
                            .border(1.dp, HerdrColors.Blocked.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                            .clickable { onDisconnect() }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "断开",
                            color = HerdrColors.Blocked,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Level 2 Card: Progressive-Disclosure 4-Stage Diagnostics Pipeline
        DiagnosticsPipelineCard(
            connectionState = connectionState,
            onTrustFingerprint = { fp ->
                onUpdateProfile { it.copy(expectedHostKeyFingerprint = fp) }
                onConnect()
            }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DefaultSettingsPane(
    profile: ConnectionProfile,
    appSettings: HerdrAppSettings,
    mobilePtyTargetCols: Int,
    zeroTierState: ZeroTierOverlayState,
    appUpdateState: AppUpdateState,
    onUpdateProfile: ((ConnectionProfile) -> ConnectionProfile) -> Unit,
    onUpdateAppSettings: ((HerdrAppSettings) -> HerdrAppSettings) -> Unit,
    onSetDefaultMobilePtyCols: (Int) -> Unit,
    onImportZtIdentity: (String) -> Unit,
    onExportZtIdentity: ((String) -> Unit) -> Unit,
    onImportCustomPlanet: (ByteArray, String) -> Unit,
    onResetBuiltInPlanet: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstallUpdate: () -> Unit,
    onShowBanner: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val keyPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { pem ->
                onUpdateProfile { it.copy(privateKeyPem = pem.trim()) }
                onShowBanner("已从文件导入 Ed25519 私钥")
            }
        }
    }

    var identityDraft by remember(profile.zeroTierIdentitySecret) {
        mutableStateOf(profile.zeroTierIdentitySecret)
    }

    val ztIdentityPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { secret ->
                identityDraft = secret.trim()
                onImportZtIdentity(secret.trim())
            }
        }
    }

    val planetPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                val label = uri.lastPathSegment?.substringAfterLast('/') ?: "custom_planet"
                onImportCustomPlanet(bytes, label)
            }
        }
    }

    var expandPtySection by remember { mutableStateOf(true) }
    var expandZtSection by remember { mutableStateOf(true) }
    var expandSshSection by remember { mutableStateOf(profile.privateKeyPem.isBlank()) }
    var expandUpdateSection by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section 1: Terminal & PTY Default Parameters
        SettingsSectionCard(
            title = "1. 终端接管与界面净化默认参数",
            subtitle = "PTY 默认列宽 · 顶栏自动隐藏 · 默认进入模式",
            expanded = expandPtySection,
            onToggleExpand = { expandPtySection = !expandPtySection }
        ) {
            // Mobile PTY Default Cols
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "手机 PTY 接管默认列宽",
                        color = HerdrColors.Foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "点「PTY 接管」时默认同步的列数（断联不重置）",
                        color = HerdrColors.Muted,
                        fontSize = 10.5.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(56, 68, 80).forEach { cols ->
                        TacticalActionPill(
                            text = "${cols}列",
                            accent = mobilePtyTargetCols == cols,
                            onClick = { onSetDefaultMobilePtyCols(cols) }
                        )
                    }
                }
            }

            // Desktop Fallback Cols on Release
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "释放 PTY 兜底桌面列宽",
                        color = HerdrColors.Foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "若某终端首次连接即处于窄屏，点「释放 PTY」时还原的列宽",
                        color = HerdrColors.Muted,
                        fontSize = 10.5.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(138, 200).forEach { cols ->
                        TacticalActionPill(
                            text = "${cols}列",
                            accent = appSettings.defaultDesktopFallbackCols == cols,
                            onClick = {
                                onUpdateAppSettings { it.copy(defaultDesktopFallbackCols = cols) }
                            }
                        )
                    }
                }
            }

            // Auto-hide Terminal Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "进入终端后自动隐藏顶栏",
                        color = HerdrColors.Foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "全屏沉浸展示终端，2.6 秒后自动折叠为单行极简状态条",
                        color = HerdrColors.Muted,
                        fontSize = 10.5.sp
                    )
                }
                TacticalActionPill(
                    text = if (appSettings.autoHideTerminalTopBar) "自动隐藏: 开" else "常驻展开",
                    accent = appSettings.autoHideTerminalTopBar,
                    onClick = {
                        onUpdateAppSettings { it.copy(autoHideTerminalTopBar = !it.autoHideTerminalTopBar) }
                    }
                )
            }

            // Auto-hide Special Keys in Observe Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "只读模式默认收起扩展按键栏",
                        color = HerdrColors.Foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "只读监工时隐藏 ESC/TAB/CTRL+O 按键排，点「按键 ▴」按需呼出",
                        color = HerdrColors.Muted,
                        fontSize = 10.5.sp
                    )
                }
                TacticalActionPill(
                    text = if (appSettings.autoHideObserveKeyStrip) "默认收起: 开" else "始终显示",
                    accent = appSettings.autoHideObserveKeyStrip,
                    onClick = {
                        onUpdateAppSettings { it.copy(autoHideObserveKeyStrip = !it.autoHideObserveKeyStrip) }
                    }
                )
            }

            // Default Attach Mode when clicking an Agent Card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "点击 Agent 卡片默认进入模式",
                        color = HerdrColors.Foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "监工场景推荐默认「只读观看」防误触",
                        color = HerdrColors.Muted,
                        fontSize = 10.5.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TacticalActionPill(
                        text = "只读观看",
                        accent = appSettings.defaultAttachMode == AttachMode.OBSERVE,
                        onClick = { onUpdateAppSettings { it.copy(defaultAttachMode = AttachMode.OBSERVE) } }
                    )
                    TacticalActionPill(
                        text = "互动接管",
                        accent = appSettings.defaultAttachMode == AttachMode.TAKEOVER,
                        onClick = { onUpdateAppSettings { it.copy(defaultAttachMode = AttachMode.TAKEOVER) } }
                    )
                }
            }
        }

        // Section 2: Built-in ZeroTier Userspace & Identity Settings
        SettingsSectionCard(
            title = "2. 内置 ZeroTier 流量管理与节点身份",
            subtitle = "自建 Planet (257B) · Network ID · 方案A Identity 导入/导出",
            expanded = expandZtSection,
            onToggleExpand = { expandZtSection = !expandZtSection }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "当前节点 ID: ${zeroTierState.nodeIdHex.ifBlank { "首次连接自动生成" }}",
                        color = HerdrColors.Foreground,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${zeroTierState.phase.label} · 虚拟IP: ${zeroTierState.assignedIpv4.ifBlank { "待分配" }}",
                        color = HerdrColors.Muted,
                        fontSize = 10.5.sp
                    )
                }
                if (zeroTierState.nodeIdHex.isNotBlank()) {
                    TacticalActionPill(
                        text = "复制 Node ID",
                        accent = false,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(zeroTierState.nodeIdHex))
                            onShowBanner("已复制 ZeroTier Node ID: ${zeroTierState.nodeIdHex}")
                        }
                    )
                }
            }

            TacticalInputField(
                label = "默认 ZeroTier Network ID (16位十六进制)",
                value = profile.zeroTierNetworkId,
                onValueChange = { nid ->
                    val clean = nid.trim().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(16)
                    onUpdateProfile { it.copy(zeroTierNetworkId = clean) }
                },
                placeholder = "8056c2e21c000001",
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Planet 根节点配置",
                        color = HerdrColors.Foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = zeroTierState.planetSummary,
                        color = HerdrColors.Muted,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TacticalActionPill(
                        text = "导入 Planet",
                        accent = false,
                        onClick = { planetPickerLauncher.launch(arrayOf("*/*")) }
                    )
                    TacticalActionPill(
                        text = "恢复内置",
                        accent = false,
                        onClick = onResetBuiltInPlanet
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "节点身份 identity.secret (方案A：支持复用已授权身份)",
                    color = HerdrColors.Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TacticalActionPill(
                        text = "选文件",
                        accent = false,
                        onClick = { ztIdentityPickerLauncher.launch(arrayOf("*/*")) }
                    )
                    TacticalActionPill(
                        text = "导出当前",
                        accent = false,
                        onClick = {
                            onExportZtIdentity { exported ->
                                identityDraft = exported
                                clipboardManager.setText(AnnotatedString(exported))
                            }
                        }
                    )
                    TacticalActionPill(
                        text = "保存应用",
                        accent = true,
                        onClick = { onImportZtIdentity(identityDraft) }
                    )
                }
            }

            TacticalInputField(
                label = null,
                value = identityDraft,
                onValueChange = { identityDraft = it },
                placeholder = "留空则自动生成持久化节点身份；或粘贴已有 identity.secret 免重新授权",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Section 3: SSH Credentials & Default Host Addresses
        SettingsSectionCard(
            title = "3. SSH 凭据与远端主机默认地址",
            subtitle = "默认 IP · 用户名/端口 · Ed25519 私钥与服务端指纹",
            expanded = expandSshSection,
            onToggleExpand = { expandSshSection = !expandSshSection }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TacticalInputField(
                    label = "局域网默认 IP",
                    value = appSettings.defaultLanHost,
                    onValueChange = { ip ->
                        onUpdateAppSettings { it.copy(defaultLanHost = ip.trim()) }
                    },
                    placeholder = "192.168.1.100",
                    modifier = Modifier.weight(1f)
                )
                TacticalInputField(
                    label = "ZeroTier 默认 IP",
                    value = appSettings.defaultZeroTierHost,
                    onValueChange = { ip ->
                        onUpdateAppSettings { it.copy(defaultZeroTierHost = ip.trim()) }
                    },
                    placeholder = "10.147.17.100",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                TacticalInputField(
                    label = "默认 SSH 用户名",
                    value = profile.username,
                    onValueChange = { u -> onUpdateProfile { it.copy(username = u.trim()) } },
                    placeholder = "herdr",
                    modifier = Modifier.weight(1.1f)
                )
                TacticalInputField(
                    label = "默认端口",
                    value = profile.port.toString(),
                    onValueChange = { p ->
                        val portNum = p.filter { it.isDigit() }.toIntOrNull() ?: 22
                        onUpdateProfile { it.copy(port = portNum) }
                    },
                    placeholder = "22",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(0.7f)
                )
                Column(
                    modifier = Modifier.weight(1.4f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "系统通道",
                        color = HerdrColors.Muted,
                        fontSize = 11.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(HerdrColors.Chrome)
                            .border(1.dp, HerdrColors.InputBorder, RoundedCornerShape(8.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HostPlatform.entries.forEach { plat ->
                            val shortLabel = when (plat) {
                                HostPlatform.WINDOWS -> "Win"
                                HostPlatform.AUTO -> "Auto"
                                else -> "Unix"
                            }
                            val active = profile.platform == plat
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (active) HerdrColors.AccentSubtle else Color.Transparent)
                                    .clickable { onUpdateProfile { it.copy(platform = plat) } },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = shortLabel,
                                    color = if (active) HerdrColors.Foreground else HerdrColors.Muted,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 10.5.sp,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            TacticalInputField(
                label = "服务端 HostKey 指纹 (SHA256)",
                value = profile.expectedHostKeyFingerprint.orEmpty(),
                onValueChange = { fp ->
                    onUpdateProfile { it.copy(expectedHostKeyFingerprint = fp.trim().ifBlank { null }) }
                },
                placeholder = "SHA256:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                modifier = Modifier.fillMaxWidth()
            )

            TacticalInputField(
                label = "远端 hc / herdr-core 可执行文件路径",
                value = profile.hcExecutablePath,
                onValueChange = { path -> onUpdateProfile { it.copy(hcExecutablePath = path) } },
                placeholder = "C:\\Users\\herdr\\AppData\\Local\\Programs\\herdr-core\\hc.exe",
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ed25519 私钥 (OpenSSH PEM)",
                    color = HerdrColors.Muted,
                    fontSize = 11.sp,
                    softWrap = false,
                    maxLines = 1
                )
                TacticalActionPill(
                    text = "从手机文件导入",
                    accent = true,
                    onClick = { keyPickerLauncher.launch(arrayOf("*/*")) }
                )
            }

            TacticalInputField(
                label = null,
                value = profile.privateKeyPem,
                onValueChange = { pem -> onUpdateProfile { it.copy(privateKeyPem = pem) } },
                placeholder = "粘贴 -----BEGIN OPENSSH PRIVATE KEY----- 或点击上方导入",
                singleLine = false,
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Section 4: App Version & GitHub Release Online Update
        SettingsSectionCard(
            title = "4. 版本与在线更新 (GitHub Release)",
            subtitle = "v${appUpdateState.currentVersion} · ${AppUpdateManager.GITHUB_OWNER}/${AppUpdateManager.GITHUB_REPO} · 升级保留全部既有配置",
            expanded = expandUpdateSection,
            onToggleExpand = { expandUpdateSection = !expandUpdateSection }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "当前版本 v${appUpdateState.currentVersion}",
                        color = HerdrColors.Foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = appUpdateState.statusMessage,
                        color = when (appUpdateState.status) {
                            UpdateStatus.AVAILABLE, UpdateStatus.READY_TO_INSTALL -> HerdrColors.Done
                            UpdateStatus.ERROR -> HerdrColors.Blocked
                            else -> HerdrColors.Muted
                        },
                        fontSize = 10.5.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TacticalActionPill(
                        text = if (appUpdateState.status == UpdateStatus.CHECKING) "检查中..." else "检查更新",
                        accent = false,
                        onClick = onCheckForUpdates
                    )
                    if (appUpdateState.status == UpdateStatus.AVAILABLE ||
                        appUpdateState.status == UpdateStatus.READY_TO_INSTALL
                    ) {
                        TacticalActionPill(
                            text = if (appUpdateState.status == UpdateStatus.READY_TO_INSTALL) {
                                "安装 ${appUpdateState.latestRelease?.tagName.orEmpty()}"
                            } else {
                                "下载更新 ${appUpdateState.latestRelease?.tagName.orEmpty()}"
                            },
                            accent = true,
                            onClick = onDownloadAndInstallUpdate
                        )
                    }
                }
            }

            appUpdateState.latestRelease?.let { rel ->
                if (rel.releaseNotes.isNotBlank()) {
                    Text(
                        text = "Release 说明 (${rel.tagName})：\n${rel.releaseNotes}",
                        color = HerdrColors.Muted,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(HerdrColors.Raised)
                .border(1.dp, HerdrColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .clickable { onNavigateBack() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "← 完成并返回上一级界面",
                color = HerdrColors.Foreground,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.5.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(HerdrColors.Surface)
            .border(1.dp, HerdrColors.Border, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = HerdrColors.Foreground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = HerdrColors.Muted,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (expanded) "收起 ▴" else "展开 ▾",
                color = HerdrColors.Accent,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun PresetTargetCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) HerdrColors.AccentSubtle else HerdrColors.Chrome)
            .border(
                width = 1.dp,
                color = if (selected) HerdrColors.Accent else HerdrColors.Border,
                shape = RoundedCornerShape(9.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(5.5.dp)
                    .clip(CircleShape)
                    .background(if (selected) HerdrColors.Accent else HerdrColors.Dim)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = title,
                color = if (selected) HerdrColors.Foreground else HerdrColors.Muted,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 12.sp,
                softWrap = false,
                maxLines = 1
            )
        }
        Text(
            text = subtitle,
            color = if (selected) HerdrColors.Accent else HerdrColors.Dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            softWrap = false,
            maxLines = 1
        )
    }
}

@Composable
private fun TacticalInputField(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 5,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = if (focused) HerdrColors.Accent else HerdrColors.Muted,
                fontSize = 11.sp,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(HerdrColors.Chrome)
                .border(
                    width = 1.dp,
                    color = if (focused) HerdrColors.Accent else HerdrColors.InputBorder,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 11.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = HerdrColors.Dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = TextStyle(
                    color = HerdrColors.Foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                ),
                cursorBrush = SolidColor(HerdrColors.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
            )
        }
    }
}

@Composable
private fun TacticalActionPill(
    text: String,
    accent: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) HerdrColors.AccentSubtle else HerdrColors.Raised)
            .border(
                width = 1.dp,
                color = if (accent) HerdrColors.Accent else HerdrColors.Border,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (accent) HerdrColors.Accent else HerdrColors.Foreground,
            fontSize = 11.sp,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
            softWrap = false,
            maxLines = 1
        )
    }
}

@Composable
private fun DiagnosticsPipelineCard(
    connectionState: ConnectionState,
    onTrustFingerprint: (String) -> Unit
) {
    val stages: List<DiagnosticStageStatus> = when (connectionState) {
        is ConnectionState.Connecting -> connectionState.stages
        is ConnectionState.Connected -> connectionState.stages
        is ConnectionState.Failed -> connectionState.stages
        ConnectionState.Disconnected -> ConnectionStage.entries.map { DiagnosticStageStatus(it) }
    }

    val isActiveOrFailed = connectionState is ConnectionState.Connecting || connectionState is ConnectionState.Failed
    var manuallyExpanded by remember { mutableStateOf(false) }
    val showStages = isActiveOrFailed || manuallyExpanded

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(HerdrColors.Surface)
            .border(
                width = 1.dp,
                color = if (connectionState is ConnectionState.Failed) HerdrColors.Blocked else HerdrColors.Border,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { manuallyExpanded = !manuallyExpanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "四步连接诊断流水线",
                    color = HerdrColors.Foreground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                    softWrap = false,
                    maxLines = 1
                )
                Text(
                    text = when (connectionState) {
                        is ConnectionState.Connected -> "✓ 四步握手已全部通过 · Core ${connectionState.hello.version}"
                        is ConnectionState.Connecting -> "正在执行第 ${connectionState.activeStage.stepNumber} 步：${connectionState.activeStage.title}"
                        is ConnectionState.Failed -> "! 阻塞于第 ${connectionState.failedStage.stepNumber} 步：${connectionState.failedStage.title}"
                        ConnectionState.Disconnected -> "待命中 · 点击可展开查看四步握手检查项"
                    },
                    color = when (connectionState) {
                        is ConnectionState.Connected -> HerdrColors.Done
                        is ConnectionState.Connecting -> HerdrColors.Working
                        is ConnectionState.Failed -> HerdrColors.Blocked
                        ConnectionState.Disconnected -> HerdrColors.Muted
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (showStages) "收起 ▴" else "详情 ▾",
                color = HerdrColors.Accent,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                maxLines = 1
            )
        }

        AnimatedVisibility(visible = showStages) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                stages.forEach { item ->
                    val (badgeBg, badgeFg, symbol) = when (item.state) {
                        StageCheckState.PASSED -> Triple(HerdrColors.DoneSubtle, HerdrColors.Done, "✓")
                        StageCheckState.IN_PROGRESS -> Triple(HerdrColors.WorkingSubtle, HerdrColors.Working, "⋯")
                        StageCheckState.FAILED -> Triple(HerdrColors.BlockedSubtle, HerdrColors.Blocked, "!")
                        StageCheckState.PENDING -> Triple(HerdrColors.Raised, HerdrColors.Dim, "${item.stage.stepNumber}")
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(HerdrColors.Chrome)
                            .border(
                                width = 1.dp,
                                color = if (item.state == StageCheckState.FAILED) HerdrColors.Blocked else HerdrColors.Border,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = symbol,
                                color = badgeFg,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "${item.stage.stepNumber}. ${item.stage.title}",
                                color = HerdrColors.Foreground,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.5.sp,
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.detail ?: item.stage.description,
                                color = if (item.state == StageCheckState.FAILED) HerdrColors.Blocked else HerdrColors.Muted,
                                fontSize = 11.sp,
                                lineHeight = 15.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (!item.hint.isNullOrBlank()) {
                                Text(
                                    text = "排查建议：${item.hint}",
                                    color = HerdrColors.Working,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        if (connectionState is ConnectionState.Failed) {
            val detectedSha256 = remember(connectionState.message, connectionState.actionableHint) {
                val combined = "${connectionState.message} ${connectionState.actionableHint}"
                Regex("SHA256:[A-Za-z0-9+/=_-]+").find(combined)?.value
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(HerdrColors.BlockedSubtle)
                    .border(1.dp, HerdrColors.Blocked, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "阻塞阶段：${connectionState.failedStage.title} (${connectionState.errorKind})",
                    color = HerdrColors.Blocked,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp
                )
                Text(
                    text = connectionState.message,
                    color = HerdrColors.Foreground,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "建议动作：${connectionState.actionableHint}",
                    color = HerdrColors.Working,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
                if (detectedSha256 != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(HerdrColors.Accent)
                            .clickable { onTrustFingerprint(detectedSha256) }
                            .padding(vertical = 9.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "信任并填入服务端指纹重连",
                            color = Color(0xFF04131D),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentsTopologyPane(
    nodes: List<AgentTreeNode>,
    currentFilter: AgentStateWord?,
    onSelectFilter: (AgentStateWord?) -> Unit,
    onRefresh: () -> Unit,
    onObserve: (TerminalSummary) -> Unit,
    onTakeover: (TerminalSummary) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Filter strip + Refresh button (All fit on one screen width!)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filters: List<Pair<AgentStateWord?, String>> = listOf(
                    null to "全部 ${nodes.size}",
                    AgentStateWord.BLOCKED to "待处理",
                    AgentStateWord.WORKING to "运行中",
                    AgentStateWord.DONE to "已完成",
                    AgentStateWord.IDLE to "空闲"
                )
                filters.forEach { (word, label) ->
                    val active = currentFilter == word
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (active) HerdrColors.AccentSubtle else HerdrColors.Surface)
                            .border(
                                1.dp,
                                if (active) HerdrColors.Accent else HerdrColors.Border,
                                RoundedCornerShape(999.dp)
                            )
                            .clickable { onSelectFilter(word) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (active) HerdrColors.Foreground else HerdrColors.Muted,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }

            TacticalActionPill(
                text = "↻ 刷新",
                accent = false,
                onClick = onRefresh
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(nodes, key = { it.summary.address }) { node ->
                AgentTreeNodeCard(
                    node = node,
                    onObserve = { onObserve(node.summary) },
                    onTakeover = { onTakeover(node.summary) }
                )
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AgentTreeNodeCard(
    node: AgentTreeNode,
    onObserve: () -> Unit,
    onTakeover: () -> Unit
) {
    val item = node.summary
    val isChild = node.depth > 0
    val statusColor = when (item.agentStatus) {
        AgentStateWord.WORKING -> HerdrColors.Working
        AgentStateWord.BLOCKED, AgentStateWord.ERROR -> HerdrColors.Blocked
        AgentStateWord.DONE -> HerdrColors.Done
        AgentStateWord.IDLE, AgentStateWord.NONE -> HerdrColors.Idle
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (node.depth * 14).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isChild) {
            Text(
                text = "└─",
                color = HerdrColors.Accent.copy(alpha = 0.75f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.padding(end = 6.dp)
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isChild) HerdrColors.Chrome else HerdrColors.Surface)
                .border(
                    width = 1.dp,
                    color = if (item.agentStatus == AgentStateWord.BLOCKED) {
                        HerdrColors.Blocked
                    } else {
                        HerdrColors.Border
                    },
                    shape = RoundedCornerShape(10.dp)
                )
                .clickable { onObserve() }
        ) {
            // Left vertical status indicator rail
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .fillMaxHeight()
                    .background(statusColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Row 1: Full-width Header (Address + Name on Left, App + Status Pills on Right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.address,
                            color = HerdrColors.Accent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                        if (!item.name.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = item.name.orEmpty(),
                                color = HerdrColors.Foreground,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.5.sp,
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = (item.agentApp ?: "shell").uppercase(),
                            color = HerdrColors.Muted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(HerdrColors.Raised)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Text(
                            text = item.agentStatus.wireName.uppercase(),
                            color = statusColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(statusColor.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Row 2: Workspace Title & Agent Latest Summary (Full Width!)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.workspaceSubtitle,
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!item.agentMessage.isNullOrBlank()) {
                        Text(
                            text = item.agentMessage.orEmpty(),
                            color = HerdrColors.Muted,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Row 3: Footer (Dimensions & Turn on Left + Concise Buttons on Right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${item.cols}×${item.rows} · Turn ${item.turn}",
                        color = HerdrColors.Dim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TacticalActionPill(
                            text = "只读观看",
                            accent = false,
                            onClick = onObserve
                        )
                        TacticalActionPill(
                            text = "接管终端",
                            accent = true,
                            onClick = onTakeover
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTerminalAttachPane(
    session: TerminalSession?,
    summary: TerminalSummary?,
    appSettings: HerdrAppSettings,
    savedDesktopDims: Map<String, Pair<Int, Int>>,
    mobilePtyTargetCols: Int,
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleMode: () -> Unit,
    onTakeoverPtyWidth: (Int) -> Unit,
    onReleasePtyWidth: () -> Unit,
    onSendLine: (String, Boolean) -> Unit,
    onSendSpecialKey: (String) -> Unit,
    onInterrupt: () -> Unit,
    onPickTerminal: () -> Unit
) {
    if (session == null || summary == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "尚未挂载终端流 (terminal.attach)",
                    color = HerdrColors.Foreground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    softWrap = false,
                    maxLines = 1
                )
                Text(
                    text = "请先从「Agents 拓扑」选择一个终端进入只读观看或接管模式。",
                    color = HerdrColors.Muted,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(HerdrColors.Raised)
                            .border(1.dp, HerdrColors.Border, RoundedCornerShape(8.dp))
                            .clickable { onNavigateBack() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "← 返回上一级",
                            color = HerdrColors.Foreground,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(HerdrColors.Accent)
                            .clickable { onPickTerminal() }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "前往选择终端 →",
                            color = Color(0xFF04131D),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        return
    }

    val mode by session.mode.collectAsState()
    val rawScreen by session.screenSnapshot.collectAsState()
    val dims by session.dimensions.collectAsState()
    var inputBuffer by remember { mutableStateOf("") }
    var fontZoomDeltaSp by remember(session.address) { mutableFloatStateOf(0f) }
    var softWrapLines by remember(session.address) { mutableStateOf(false) }
    var pendingFocusAfterUnlock by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    // Auto-hiding top control drawer for ultra-clean terminal immersion
    var topBarExpanded by remember(session.address) { mutableStateOf(true) }
    var topBarInteractionTick by remember(session.address) { mutableIntStateOf(0) }
    var showSpecialKeyStrip by remember(session.address, mode, appSettings.autoHideObserveKeyStrip) {
        mutableStateOf(mode == AttachMode.TAKEOVER || !appSettings.autoHideObserveKeyStrip)
    }

    LaunchedEffect(topBarExpanded, topBarInteractionTick, appSettings.autoHideTerminalTopBar) {
        if (topBarExpanded && appSettings.autoHideTerminalTopBar) {
            delay(2600)
            topBarExpanded = false
        }
    }

    val curCols = dims.first
    val curRows = dims.second
    val isMobilePtyLocked = curCols in 1..88
    val desktopOrigDims = savedDesktopDims[summary.address]

    LaunchedEffect(mode, pendingFocusAfterUnlock) {
        if (mode == AttachMode.TAKEOVER && pendingFocusAfterUnlock) {
            pendingFocusAfterUnlock = false
            runCatching { inputFocusRequester.requestFocus() }
        }
    }

    val styledScreen = remember(rawScreen) {
        AnsiTerminalParser.parseToAnnotatedString(rawScreen, mobileReflow = false)
    }

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var followBottom by remember(session.address) { mutableStateOf(true) }
    var isProgrammaticScroll by remember(session.address) { mutableStateOf(false) }
    var lastProgrammaticTarget by remember(session.address) { mutableIntStateOf(0) }

    LaunchedEffect(vScroll) {
        snapshotFlow { Triple(vScroll.value, vScroll.maxValue, vScroll.isScrollInProgress) }
            .collect { (value, maxValue, inProgress) ->
                if (maxValue <= 0 || isProgrammaticScroll) return@collect
                val distFromBottom = (maxValue - value).coerceAtLeast(0)
                if (inProgress) {
                    followBottom = distFromBottom <= 16
                    if (followBottom) {
                        lastProgrammaticTarget = value
                    }
                } else if (followBottom && value < lastProgrammaticTarget - 16 && distFromBottom > 16) {
                    followBottom = false
                }
            }
    }

    LaunchedEffect(styledScreen.text, vScroll.maxValue) {
        val currentMax = vScroll.maxValue
        if (currentMax <= 0) return@LaunchedEffect
        if (followBottom && !vScroll.isScrollInProgress) {
            isProgrammaticScroll = true
            try {
                vScroll.scrollTo(currentMax)
                lastProgrammaticTarget = vScroll.value
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    val isScrolledUp = !followBottom && vScroll.maxValue > 24 && (vScroll.maxValue - vScroll.value) > 24

    Column(modifier = Modifier.fillMaxSize()) {
        // Slim Always-Visible Top Micro-Header + Auto-Hiding Full Control Drawer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isMobilePtyLocked || mode == AttachMode.TAKEOVER) HerdrColors.WorkingSubtle else HerdrColors.Chrome
                )
                .border(
                    width = 0.5.dp,
                    color = if (isMobilePtyLocked) HerdrColors.Accent else if (mode == AttachMode.TAKEOVER) HerdrColors.Working else HerdrColors.Border
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Row 1: Ultra-Clean Navigation + Terminal Badge + PTY Action + Drawer Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(HerdrColors.Raised)
                        .border(0.8.dp, HerdrColors.Border, RoundedCornerShape(6.dp))
                        .clickable { onNavigateBack() }
                        .padding(horizontal = 8.dp, vertical = 3.5.dp)
                ) {
                    Text(
                        text = "← 返回",
                        color = HerdrColors.Accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            topBarExpanded = !topBarExpanded
                            topBarInteractionTick++
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = summary.address,
                        color = HerdrColors.Accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = summary.name ?: summary.workspaceSubtitle,
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp,
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isMobilePtyLocked) HerdrColors.AccentSubtle else HerdrColors.Raised)
                            .border(
                                width = 0.5.dp,
                                color = if (isMobilePtyLocked) HerdrColors.Accent else HerdrColors.Border,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "${curCols}×${curRows}",
                            color = if (isMobilePtyLocked) HerdrColors.Accent else HerdrColors.Muted,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isMobilePtyLocked) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 9.8.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isMobilePtyLocked) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(HerdrColors.Accent)
                                .clickable {
                                    fontZoomDeltaSp = 0f
                                    topBarInteractionTick++
                                    coroutineScope.launch { hScroll.scrollTo(0) }
                                    onTakeoverPtyWidth(mobilePtyTargetCols)
                                }
                                .padding(horizontal = 8.dp, vertical = 3.5.dp)
                        ) {
                            Text(
                                text = "PTY ${mobilePtyTargetCols}列",
                                color = Color(0xFF04131D),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    } else {
                        val origColsLabel = desktopOrigDims?.first ?: appSettings.defaultDesktopFallbackCols
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(HerdrColors.Working)
                                .clickable {
                                    fontZoomDeltaSp = 0f
                                    topBarInteractionTick++
                                    onReleasePtyWidth()
                                }
                                .padding(horizontal = 8.dp, vertical = 3.5.dp)
                        ) {
                            Text(
                                text = "释放(${origColsLabel}列)",
                                color = Color(0xFF04131D),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(HerdrColors.Raised)
                            .border(0.8.dp, HerdrColors.Border, RoundedCornerShape(6.dp))
                            .clickable {
                                topBarExpanded = !topBarExpanded
                                topBarInteractionTick++
                            }
                            .padding(horizontal = 7.dp, vertical = 3.5.dp)
                    ) {
                        Text(
                            text = if (topBarExpanded) "收起 ▴" else "顶栏 ▾",
                            color = HerdrColors.Foreground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }

            // Collapsible Row 2: PTY Width Presets (56/68/80), Font Zoom (A-/A+), SoftWrap & Settings Link
            AnimatedVisibility(visible = topBarExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(56, 68, 80).forEach { presetCols ->
                            val isSelected = curCols == presetCols
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (isSelected) HerdrColors.AccentSubtle else HerdrColors.Raised)
                                    .border(
                                        width = 0.8.dp,
                                        color = if (isSelected) HerdrColors.Accent else HerdrColors.Border,
                                        shape = RoundedCornerShape(5.dp)
                                    )
                                    .clickable {
                                        fontZoomDeltaSp = 0f
                                        topBarInteractionTick++
                                        coroutineScope.launch { hScroll.scrollTo(0) }
                                        onTakeoverPtyWidth(presetCols)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${presetCols}列",
                                    color = if (isSelected) HerdrColors.Accent else HerdrColors.Muted,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 9.8.sp,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(HerdrColors.Raised)
                                .border(0.8.dp, HerdrColors.Border, RoundedCornerShape(5.dp))
                                .clickable {
                                    topBarInteractionTick++
                                    fontZoomDeltaSp = (fontZoomDeltaSp - 0.75f).coerceAtLeast(-3.5f)
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "A-",
                                color = HerdrColors.Foreground,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.8.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(HerdrColors.Raised)
                                .border(0.8.dp, HerdrColors.Border, RoundedCornerShape(5.dp))
                                .clickable {
                                    topBarInteractionTick++
                                    fontZoomDeltaSp = (fontZoomDeltaSp + 0.75f).coerceAtMost(5.0f)
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "A+",
                                color = HerdrColors.Foreground,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.8.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (softWrapLines) HerdrColors.AccentSubtle else HerdrColors.Raised)
                                .border(
                                    width = 0.8.dp,
                                    color = if (softWrapLines) HerdrColors.Accent else HerdrColors.Border,
                                    shape = RoundedCornerShape(5.dp)
                                )
                                .clickable {
                                    topBarInteractionTick++
                                    softWrapLines = !softWrapLines
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (softWrapLines) "折行" else "1:1等宽",
                                color = if (softWrapLines) HerdrColors.Accent else HerdrColors.Muted,
                                fontSize = 9.5.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (mode == AttachMode.TAKEOVER) HerdrColors.DoneSubtle else HerdrColors.Raised)
                                .border(
                                    width = 0.8.dp,
                                    color = if (mode == AttachMode.TAKEOVER) HerdrColors.Done else HerdrColors.Border,
                                    shape = RoundedCornerShape(5.dp)
                                )
                                .clickable {
                                    topBarInteractionTick++
                                    onToggleMode()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (mode == AttachMode.TAKEOVER) "可输入" else "只读锁",
                                color = if (mode == AttachMode.TAKEOVER) HerdrColors.Done else HerdrColors.Muted,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(HerdrColors.Raised)
                                .border(0.8.dp, HerdrColors.Border, RoundedCornerShape(5.dp))
                                .clickable { onOpenSettings() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "⚙",
                                color = HerdrColors.Muted,
                                fontSize = 9.8.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Native 1:1 PTY Canvas with automatic column-width font fitting
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 4.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, HerdrColors.Border, RoundedCornerShape(10.dp)),
                color = HerdrColors.Canvas,
                shape = RoundedCornerShape(10.dp)
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val innerWidthDp = (maxWidth - 16.dp).value.coerceAtLeast(240f)
                    val baseFontSizeSp = if (curCols in 1..92) {
                        (innerWidthDp / (curCols * 0.675f)).coerceIn(7.2f, 15.0f)
                    } else {
                        10.5f
                    }
                    val effectiveFontSizeSp = (baseFontSizeSp + fontZoomDeltaSp).coerceIn(6.8f, 18.0f)

                    val scrollModifier = if (softWrapLines) {
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(vScroll)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(vScroll)
                            .horizontalScroll(hScroll, enabled = hScroll.maxValue > 4)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    }
                    Box(modifier = scrollModifier) {
                        SelectionContainer {
                            Text(
                                text = styledScreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = effectiveFontSizeSp.sp,
                                lineHeight = (effectiveFontSizeSp * 1.32f).sp,
                                softWrap = softWrapLines
                            )
                        }
                    }
                }
            }

            if (isScrolledUp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(HerdrColors.Raised.copy(alpha = 0.95f))
                        .border(1.dp, HerdrColors.Accent, RoundedCornerShape(999.dp))
                        .clickable {
                            followBottom = true
                            coroutineScope.launch {
                                isProgrammaticScroll = true
                                try {
                                    vScroll.animateScrollTo(vScroll.maxValue)
                                    lastProgrammaticTarget = vScroll.value
                                } finally {
                                    isProgrammaticScroll = false
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "↓ 回到底部",
                        color = HerdrColors.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        softWrap = false,
                        maxLines = 1
                    )
                }
            }
        }

        // Clean Layered Bottom Dock (Collapsible Special Keys + Mode-Aware Input Bar)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HerdrColors.Chrome)
                .border(0.5.dp, HerdrColors.Border)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AnimatedVisibility(visible = showSpecialKeyStrip) {
                val specialKeys = listOf(
                    "esc" to "ESC",
                    "tab" to "TAB",
                    "up" to "↑",
                    "down" to "↓",
                    "ctrl+o" to "CTRL+O",
                    "ctrl+t" to "CTRL+T",
                    "ctrl+c" to "CTRL+C",
                    "enter" to "ENTER"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    specialKeys.forEach { (wireKey, label) ->
                        val isInterruptKey = wireKey == "ctrl+c"
                        val isExpandKey = wireKey == "ctrl+o" || wireKey == "ctrl+t"
                        val keyWeight = when {
                            label.length == 1 -> 0.72f
                            label.length >= 6 -> 1.28f
                            label.length == 5 -> 1.18f
                            else -> 0.95f
                        }
                        Box(
                            modifier = Modifier
                                .weight(keyWeight)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        isInterruptKey -> HerdrColors.BlockedSubtle
                                        isExpandKey -> HerdrColors.AccentSubtle
                                        else -> HerdrColors.Raised
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = when {
                                        isInterruptKey -> HerdrColors.Blocked.copy(alpha = 0.65f)
                                        isExpandKey -> HerdrColors.Accent.copy(alpha = 0.55f)
                                        else -> HerdrColors.Border
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    if (isInterruptKey) {
                                        onInterrupt()
                                    } else {
                                        onSendSpecialKey(wireKey)
                                    }
                                }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = when {
                                    isInterruptKey -> HerdrColors.Blocked
                                    isExpandKey -> HerdrColors.Accent
                                    mode == AttachMode.TAKEOVER -> HerdrColors.Foreground
                                    else -> HerdrColors.Muted
                                },
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 10.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            if (mode == AttachMode.OBSERVE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(HerdrColors.Surface)
                            .border(1.dp, HerdrColors.Border, RoundedCornerShape(8.dp))
                            .clickable {
                                pendingFocusAfterUnlock = true
                                onToggleMode()
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "只读监工中 · 点击解锁输入",
                            color = HerdrColors.Muted,
                            fontSize = 11.5.sp,
                            softWrap = false,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "接管输入 →",
                            color = HerdrColors.Accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (showSpecialKeyStrip) HerdrColors.AccentSubtle else HerdrColors.Raised)
                            .border(
                                width = 1.dp,
                                color = if (showSpecialKeyStrip) HerdrColors.Accent else HerdrColors.Border,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { showSpecialKeyStrip = !showSpecialKeyStrip }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (showSpecialKeyStrip) "按键 ▾" else "按键 ▴",
                            color = if (showSpecialKeyStrip) HerdrColors.Accent else HerdrColors.Foreground,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            } else {
                var inputFocused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(HerdrColors.Surface)
                            .border(
                                width = 1.dp,
                                color = if (inputFocused) HerdrColors.Accent else HerdrColors.InputBorder,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "❯",
                            color = HerdrColors.Accent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (inputBuffer.isEmpty()) {
                                Text(
                                    text = "输入命令或中文发送到 ${session.address}...",
                                    color = HerdrColors.Dim,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    softWrap = false,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = inputBuffer,
                                onValueChange = { inputBuffer = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = HerdrColors.Foreground,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                ),
                                cursorBrush = SolidColor(HerdrColors.Accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (inputBuffer.isNotBlank()) {
                                            onSendLine(inputBuffer, false)
                                            inputBuffer = ""
                                        }
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(inputFocusRequester)
                                    .onFocusChanged { inputFocused = it.isFocused }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (showSpecialKeyStrip) HerdrColors.AccentSubtle else HerdrColors.Raised)
                            .border(
                                width = 1.dp,
                                color = if (showSpecialKeyStrip) HerdrColors.Accent else HerdrColors.Border,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { showSpecialKeyStrip = !showSpecialKeyStrip }
                            .padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (showSpecialKeyStrip) "键 ▾" else "键 ▴",
                            color = if (showSpecialKeyStrip) HerdrColors.Accent else HerdrColors.Foreground,
                            fontSize = 11.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }

                    val canSend = inputBuffer.isNotBlank()
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (canSend) HerdrColors.Accent else HerdrColors.Raised)
                            .border(
                                width = 1.dp,
                                color = if (canSend) HerdrColors.Accent else HerdrColors.Border,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = canSend) {
                                onSendLine(inputBuffer, false)
                                inputBuffer = ""
                            }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "发送",
                            color = if (canSend) Color(0xFF04131D) else HerdrColors.Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
