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
import dev.herdr.android.service.HerdrKeepAliveCoordinator
import dev.herdr.android.ui.terminal.AnsiTerminalParser
import dev.herdr.android.ui.theme.HerdrColors
import dev.herdr.android.ui.theme.HerdrThemePreset
import dev.herdr.android.ui.theme.palette
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

    var showQuickThemeDrawer by remember { mutableStateOf(false) }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    BackHandler(enabled = true) {
        if (!viewModel.navigateBack()) {
            (context as? android.app.Activity)?.moveTaskToBack(true)
        }
    }

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
        // Minimal top header (hidden when inside full-screen Terminal view)
        AnimatedVisibility(visible = selectedTab != WorkspaceTab.TERMINAL) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopTelemetryBar(
                    connectionState = connectionState,
                    terminalCount = agentNodes.size,
                    hostLabel = profile.host,
                    currentTheme = appSettings.themePreset,
                    themeDrawerExpanded = showQuickThemeDrawer,
                    onToggleThemeDrawer = { showQuickThemeDrawer = !showQuickThemeDrawer }
                )

                AnimatedVisibility(visible = showQuickThemeDrawer) {
                    QuickThemeStrip(
                        currentPreset = appSettings.themePreset,
                        onSelectPreset = { preset ->
                            viewModel.updateAppSettings { it.copy(themePreset = preset) }
                        }
                    )
                }

                if (appUpdateState.status == UpdateStatus.AVAILABLE ||
                    appUpdateState.status == UpdateStatus.DOWNLOADING ||
                    appUpdateState.status == UpdateStatus.READY_TO_INSTALL
                ) {
                    val rel = appUpdateState.latestRelease
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(HerdrColors.DoneSubtle)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(HerdrColors.Done)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (appUpdateState.status) {
                                    UpdateStatus.DOWNLOADING -> "正在下载更新 ${rel?.tagName.orEmpty()} (${appUpdateState.downloadProgressPercent}%)..."
                                    UpdateStatus.READY_TO_INSTALL -> "新版本 ${rel?.tagName.orEmpty()} 已就绪 · 保留全部配置"
                                    else -> "发现新版本 ${rel?.tagName.orEmpty()} · ${rel?.releaseTitle.orEmpty()}"
                                },
                                color = HerdrColors.Foreground,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        if (appUpdateState.status != UpdateStatus.DOWNLOADING) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(HerdrColors.Done)
                                    .clickable { viewModel.downloadAndInstallUpdate(context) }
                                    .padding(horizontal = 12.dp, vertical = 4.5.dp)
                            ) {
                                Text(
                                    text = if (appUpdateState.status == UpdateStatus.READY_TO_INSTALL) "安装更新" else "立即更新",
                                    color = Color.White,
                                    fontSize = 11.sp,
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
                        .clickable { viewModel.clearBanner() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "×",
                        color = HerdrColors.Accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Main Workspace Content
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                WorkspaceTab.CONNECTION -> ConnectionAndDiagnosticsPane(
                    profile = profile,
                    appSettings = appSettings,
                    connectionState = connectionState,
                    zeroTierState = zeroTierState,
                    agentCount = agentNodes.size,
                    onUpdateProfile = viewModel::updateProfile,
                    onUpdateAppSettings = viewModel::updateAppSettings,
                    onSelectTheme = { preset ->
                        viewModel.updateAppSettings { it.copy(themePreset = preset) }
                    },
                    onApplyLanPreset = viewModel::applyLanPreset,
                    onApplyPreset = viewModel::applyPreset,
                    onOpenSettings = { viewModel.selectTab(WorkspaceTab.SETTINGS) },
                    onShowBanner = viewModel::showBanner,
                    onConnect = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 && appSettings.keepAliveInBackground) {
                            runCatching {
                                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        viewModel.connectNow()
                    },
                    onDisconnect = viewModel::disconnectNow,
                    onJumpToAgents = { viewModel.selectTab(WorkspaceTab.AGENTS) }
                )

                WorkspaceTab.AGENTS -> AgentsTopologyPane(
                    nodes = agentNodes,
                    currentFilter = statusFilter,
                    onSelectFilter = viewModel::setStatusFilter,
                    onRefresh = viewModel::refreshTerminals,
                    onObserve = { viewModel.attachTerminal(it, appSettings.defaultAttachMode) },
                    onTakeover = { viewModel.attachTerminal(it, AttachMode.TAKEOVER) },
                    onGoToConnect = { viewModel.selectTab(WorkspaceTab.CONNECTION) }
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
                    onShowBanner = viewModel::showBanner
                )
            }
        }

        // Thumb-friendly Bottom Navigation Bar (hidden in full-screen Terminal view)
        AnimatedVisibility(visible = selectedTab != WorkspaceTab.TERMINAL) {
            BottomWorkspaceDock(
                selectedTab = selectedTab,
                agentCount = agentNodes.size,
                activeTerminalAddress = activeSession?.address,
                onSelectTab = viewModel::selectTab
            )
        }
    }
}

@Composable
private fun TopTelemetryBar(
    connectionState: ConnectionState,
    terminalCount: Int,
    hostLabel: String,
    currentTheme: HerdrThemePreset,
    themeDrawerExpanded: Boolean,
    onToggleThemeDrawer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HerdrColors.Chrome)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(HerdrColors.AccentSubtle),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "H",
                    color = HerdrColors.Accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Herdr",
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.5.sp,
                        letterSpacing = (-0.3).sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val (dotColor, pillText) = when (connectionState) {
                        is ConnectionState.Connected -> HerdrColors.Done to "已连接"
                        is ConnectionState.Connecting -> HerdrColors.Working to "握手中"
                        is ConnectionState.Failed -> HerdrColors.Blocked to "连接异常"
                        ConnectionState.Disconnected -> HerdrColors.Idle to "待连接"
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(dotColor.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 2.5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.5.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = pillText,
                            color = dotColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when (connectionState) {
                        is ConnectionState.Connected ->
                            "${connectionState.hello.coreName} · $terminalCount 个活跃终端 · $hostLabel"
                        is ConnectionState.Connecting ->
                            "正在执行：${connectionState.activeStage.title}"
                        is ConnectionState.Failed ->
                            "受阻于：${connectionState.failedStage.title}"
                        ConnectionState.Disconnected ->
                            "加密终端工作台 · $hostLabel"
                    },
                    color = HerdrColors.Muted,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 1-Tap Quick Theme Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (themeDrawerExpanded) HerdrColors.AccentSubtle else HerdrColors.Raised)
                    .clickable { onToggleThemeDrawer() }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(HerdrColors.Accent)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = currentTheme.title,
                    color = if (themeDrawerExpanded) HerdrColors.Accent else HerdrColors.Foreground,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    softWrap = false,
                    maxLines = 1
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(HerdrColors.Border)
        )
    }
}

@Composable
private fun QuickThemeStrip(
    currentPreset: HerdrThemePreset,
    onSelectPreset: (HerdrThemePreset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HerdrColors.Chrome)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HerdrThemePreset.entries.forEach { preset ->
                val pal = preset.palette()
                val selected = preset == currentPreset
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) HerdrColors.AccentSubtle else HerdrColors.Raised)
                        .border(
                            width = if (selected) 1.2.dp else 0.5.dp,
                            color = if (selected) HerdrColors.Accent else HerdrColors.Border,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectPreset(preset) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(pal.canvas)
                            .border(1.dp, pal.border, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(pal.accent)
                        )
                    }
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = preset.title,
                        color = if (selected) HerdrColors.Accent else HerdrColors.Foreground,
                        fontSize = 11.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        softWrap = false,
                        maxLines = 1
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(HerdrColors.Border)
        )
    }
}

@Composable
private fun BottomWorkspaceDock(
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HerdrColors.Chrome)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(HerdrColors.Border)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            visibleTabs.forEach { tab ->
                val selected = tab == selectedTab
                val shortTermId = activeTerminalAddress?.substringAfterLast('/')
                val (iconGlyph, label, badge) = when (tab) {
                    WorkspaceTab.CONNECTION -> Triple("◈", "连接", null)
                    WorkspaceTab.AGENTS -> Triple("❖", "拓扑", if (agentCount > 0) "$agentCount" else null)
                    WorkspaceTab.TERMINAL -> Triple("❯_", "终端", shortTermId)
                    WorkspaceTab.SETTINGS -> Triple("⚙", "设置", null)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) HerdrColors.AccentSubtle else Color.Transparent)
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 9.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = iconGlyph,
                        color = if (selected) HerdrColors.Accent else HerdrColors.Muted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = label,
                        color = if (selected) HerdrColors.Accent else HerdrColors.Muted,
                        fontSize = 12.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        softWrap = false,
                        maxLines = 1
                    )
                    if (!badge.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) HerdrColors.Accent else HerdrColors.Raised)
                                .padding(horizontal = 6.dp, vertical = 1.5.dp)
                        ) {
                            Text(
                                text = badge,
                                color = if (selected) HerdrColors.OnAccent else HerdrColors.Muted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
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
}

@Composable
private fun ConnectionAndDiagnosticsPane(
    profile: ConnectionProfile,
    appSettings: HerdrAppSettings,
    connectionState: ConnectionState,
    zeroTierState: ZeroTierOverlayState,
    agentCount: Int,
    onUpdateProfile: ((ConnectionProfile) -> ConnectionProfile) -> Unit,
    onUpdateAppSettings: ((HerdrAppSettings) -> HerdrAppSettings) -> Unit,
    onSelectTheme: (HerdrThemePreset) -> Unit,
    onApplyLanPreset: () -> Unit,
    onApplyPreset: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onShowBanner: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onJumpToAgents: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showQuickTargetOverride by remember { mutableStateOf(false) }
    var batteryWhitelisted by remember {
        mutableStateOf(HerdrKeepAliveCoordinator.isIgnoringBatteryOptimizations(context))
    }

    val lanDefaultHost = appSettings.defaultLanHost.ifBlank { "192.168.1.100" }
    val ztDefaultHost = appSettings.defaultZeroTierHost.ifBlank { "10.147.17.100" }
    val isPreview = profile.host.startsWith("demo://")
    val isZeroTier = (!isPreview && (profile.useBuiltInZeroTier || profile.host == ztDefaultHost))
    val isLan = (!isPreview && !isZeroTier)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Connection Card (Linear/Raycast style: soft surface, minimal border, generous spacing)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(HerdrColors.Surface)
                .border(0.6.dp, HerdrColors.Border, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "远端工作台连接",
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "切换连接模式并建立 SSH 加密会话",
                        color = HerdrColors.Muted,
                        fontSize = 11.5.sp
                    )
                }
                TacticalActionPill(
                    text = "参数配置",
                    accent = false,
                    onClick = onOpenSettings
                )
            }

            // Clean Segmented Mode Container (No triple-nested harsh borders)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HerdrColors.Raised)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
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

            // Target Endpoint Card
            val hasKey = profile.privateKeyPem.contains("PRIVATE KEY")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HerdrColors.Canvas.copy(alpha = 0.65f))
                    .padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isPreview) {
                                "demo://preview"
                            } else {
                                "${profile.username}@${profile.host}:${profile.port}"
                            },
                            color = HerdrColors.Foreground,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        val keyStatusLabel = if (isPreview) {
                            "内置多 Agent 模拟环境"
                        } else if (hasKey) {
                            "Ed25519 密钥已就绪"
                        } else {
                            "尚未配置私钥 · 请前往设置导入"
                        }
                        val platLabel = when (profile.platform) {
                            HostPlatform.WINDOWS -> "Windows OpenSSH"
                            HostPlatform.AUTO -> "Auto"
                            HostPlatform.UNIX -> "Unix"
                        }
                        Text(
                            text = if (isPreview) keyStatusLabel else "$platLabel · $keyStatusLabel",
                            color = if (hasKey || isPreview) HerdrColors.Muted else HerdrColors.Working,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!isPreview) {
                        TacticalActionPill(
                            text = if (showQuickTargetOverride) "收起" else "改地址",
                            accent = showQuickTargetOverride,
                            onClick = { showQuickTargetOverride = !showQuickTargetOverride }
                        )
                    }
                }

                AnimatedVisibility(visible = showQuickTargetOverride && !isPreview) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TacticalInputField(
                            label = "主机地址",
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

            // Built-in ZeroTier Userspace Card (when ZeroTier selected)
            if (isZeroTier) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(HerdrColors.Canvas.copy(alpha = 0.65f))
                        .padding(13.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "内置 ZeroTier 节点 (libzt)",
                                    color = HerdrColors.Foreground,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (profile.useBuiltInZeroTier) {
                                        "用户态直连 · 不占用系统 VPN 槽位"
                                    } else {
                                        "已关闭内置节点（需外部运行 ZerotierFix）"
                                    },
                                    color = HerdrColors.Muted,
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        TacticalActionPill(
                            text = if (profile.useBuiltInZeroTier) "已启用" else "启用",
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
                                .clip(RoundedCornerShape(9.dp))
                                .background(HerdrColors.Surface)
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val nodeText = zeroTierState.nodeIdHex.ifBlank { "连接时生成" }
                                val ipText = zeroTierState.assignedIpv4.ifBlank { "待分配" }
                                val pathBadge = zeroTierState.peerPathMode.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                                Text(
                                    text = "Node $nodeText · IP $ipText$pathBadge",
                                    color = HerdrColors.Foreground,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Net ${profile.zeroTierNetworkId.ifBlank { "未设置" }} · ${zeroTierState.phase.label}",
                                    color = if (zeroTierState.phase == ZeroTierNodePhase.WAITING_AUTH) {
                                        HerdrColors.Blocked
                                    } else {
                                        HerdrColors.Muted
                                    },
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (zeroTierState.nodeIdHex.isNotBlank()) {
                                    TacticalActionPill(
                                        text = "复制 ID",
                                        accent = false,
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(zeroTierState.nodeIdHex))
                                            onShowBanner("已复制 ZeroTier Node ID: ${zeroTierState.nodeIdHex}")
                                        }
                                    )
                                }
                                TacticalActionPill(
                                    text = "节点配置",
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
                        .height(46.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(HerdrColors.Accent)
                        .clickable { onConnect() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isConnected) "重新同步连接" else "建立加密连接",
                        color = HerdrColors.OnAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                }

                if (isConnected) {
                    Box(
                        modifier = Modifier
                            .weight(1.15f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(HerdrColors.AccentSubtle)
                            .clickable { onJumpToAgents() }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "终端拓扑 ($agentCount) →",
                            color = HerdrColors.Accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .height(46.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(HerdrColors.BlockedSubtle)
                            .clickable { onDisconnect() }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "断开",
                            color = HerdrColors.Blocked,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }

            // Background Keep-Alive & Auto-Reconnect Status Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(HerdrColors.Canvas.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.5.dp)
                            .clip(CircleShape)
                            .background(
                                if (appSettings.keepAliveInBackground) HerdrColors.Done else HerdrColors.Muted
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (appSettings.keepAliveInBackground) {
                                "后台常驻保活已开启"
                            } else {
                                "后台常驻保活已关闭"
                            },
                            color = HerdrColors.Foreground,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (appSettings.keepAliveInBackground) {
                                "前台服务 + 唤醒锁 + 断线自动恢复终端流"
                            } else {
                                "切后台时可能被系统冻结断流"
                            },
                            color = HerdrColors.Muted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (appSettings.keepAliveInBackground && !batteryWhitelisted) {
                        TacticalActionPill(
                            text = "省电白名单",
                            accent = false,
                            onClick = {
                                HerdrKeepAliveCoordinator.requestIgnoreBatteryOptimizations(context)
                                batteryWhitelisted = HerdrKeepAliveCoordinator.isIgnoringBatteryOptimizations(context)
                            }
                        )
                    }
                    TacticalActionPill(
                        text = if (appSettings.keepAliveInBackground) "保活中" else "开启",
                        accent = appSettings.keepAliveInBackground,
                        onClick = {
                            onUpdateAppSettings { it.copy(keepAliveInBackground = !it.keepAliveInBackground) }
                        }
                    )
                }
            }
        }

        // Theme Showcase Card (Dark & Light Presets)
        ThemeSelectorCard(
            currentPreset = appSettings.themePreset,
            onSelectPreset = onSelectTheme
        )

        // Progressive-Disclosure Handshake Diagnostics Card
        DiagnosticsPipelineCard(
            connectionState = connectionState,
            onTrustFingerprint = { fp ->
                onUpdateProfile { it.copy(expectedHostKeyFingerprint = fp) }
                onConnect()
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ThemeSelectorCard(
    currentPreset: HerdrThemePreset,
    onSelectPreset: (HerdrThemePreset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HerdrColors.Surface)
            .border(0.6.dp, HerdrColors.Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "主题与视觉风格",
                    color = HerdrColors.Foreground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp
                )
                Text(
                    text = "4 款深色 + 2 款浅色护眼主题 · 实时同步终端配色",
                    color = HerdrColors.Muted,
                    fontSize = 11.sp
                )
            }
            Text(
                text = if (currentPreset.isLight) "☼ 浅色" else "◐ 深色",
                color = HerdrColors.Accent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            HerdrThemePreset.entries.forEach { preset ->
                val pal = preset.palette()
                val selected = preset == currentPreset
                Column(
                    modifier = Modifier
                        .width(108.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(pal.surface)
                        .border(
                            width = if (selected) 1.6.dp else 0.8.dp,
                            color = if (selected) pal.accent else pal.border,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectPreset(preset) }
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(11.dp)
                                    .clip(CircleShape)
                                    .background(pal.canvas)
                                    .border(0.5.dp, pal.border, CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .size(11.dp)
                                    .clip(CircleShape)
                                    .background(pal.accent)
                            )
                            Box(
                                modifier = Modifier
                                    .size(11.dp)
                                    .clip(CircleShape)
                                    .background(pal.done)
                            )
                        }
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(pal.accent)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = preset.title,
                            color = pal.foreground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                        Text(
                            text = preset.subtitle,
                            color = pal.muted,
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
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
    onShowBanner: (String) -> Unit
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Appearance & Theme Card at the top of Settings
        ThemeSelectorCard(
            currentPreset = appSettings.themePreset,
            onSelectPreset = { preset ->
                onUpdateAppSettings { it.copy(themePreset = preset) }
            }
        )

        // Section: Terminal Display & Interaction
        SettingsSectionCard(
            title = "终端显示与交互",
            subtitle = "PTY 默认列宽 · 顶栏自动隐藏 · 默认进入模式",
            expanded = expandPtySection,
            onToggleExpand = { expandPtySection = !expandPtySection }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "手机 PTY 接管列宽",
                        color = HerdrColors.Foreground,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "移动端重排终端时的默认列数",
                        color = HerdrColors.Muted,
                        fontSize = 11.sp
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "释放 PTY 还原桌面列宽",
                        color = HerdrColors.Foreground,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "点「释放」时恢复的桌面端列数",
                        color = HerdrColors.Muted,
                        fontSize = 11.sp
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "终端顶栏自动收起",
                        color = HerdrColors.Foreground,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "进入终端 2.6 秒后自动折叠扩展工具栏",
                        color = HerdrColors.Muted,
                        fontSize = 11.sp
                    )
                }
                TacticalActionPill(
                    text = if (appSettings.autoHideTerminalTopBar) "已开启" else "常驻展开",
                    accent = appSettings.autoHideTerminalTopBar,
                    onClick = {
                        onUpdateAppSettings { it.copy(autoHideTerminalTopBar = !it.autoHideTerminalTopBar) }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "只读模式默认收起按键栏",
                        color = HerdrColors.Foreground,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "只读观看时隐藏 ESC/TAB 快捷按键排",
                        color = HerdrColors.Muted,
                        fontSize = 11.sp
                    )
                }
                TacticalActionPill(
                    text = if (appSettings.autoHideObserveKeyStrip) "已开启" else "始终显示",
                    accent = appSettings.autoHideObserveKeyStrip,
                    onClick = {
                        onUpdateAppSettings { it.copy(autoHideObserveKeyStrip = !it.autoHideObserveKeyStrip) }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "点击终端卡片默认模式",
                        color = HerdrColors.Foreground,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "推荐默认「只读观看」防止误触",
                        color = HerdrColors.Muted,
                        fontSize = 11.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TacticalActionPill(
                        text = "只读观看",
                        accent = appSettings.defaultAttachMode == AttachMode.OBSERVE,
                        onClick = { onUpdateAppSettings { it.copy(defaultAttachMode = AttachMode.OBSERVE) } }
                    )
                    TacticalActionPill(
                        text = "接管输入",
                        accent = appSettings.defaultAttachMode == AttachMode.TAKEOVER,
                        onClick = { onUpdateAppSettings { it.copy(defaultAttachMode = AttachMode.TAKEOVER) } }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "后台连接常驻保活",
                        color = HerdrColors.Foreground,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "切后台与锁屏时保持 SSH / ZeroTier 不断流并自动重连",
                        color = HerdrColors.Muted,
                        fontSize = 11.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!HerdrKeepAliveCoordinator.isIgnoringBatteryOptimizations(context)) {
                        TacticalActionPill(
                            text = "省电白名单",
                            accent = false,
                            onClick = {
                                HerdrKeepAliveCoordinator.requestIgnoreBatteryOptimizations(context)
                            }
                        )
                    }
                    TacticalActionPill(
                        text = if (appSettings.keepAliveInBackground) "已开启" else "已关闭",
                        accent = appSettings.keepAliveInBackground,
                        onClick = {
                            onUpdateAppSettings { it.copy(keepAliveInBackground = !it.keepAliveInBackground) }
                        }
                    )
                }
            }
        }

        // Section: Built-in ZeroTier Userspace & Identity Settings
        SettingsSectionCard(
            title = "ZeroTier 虚拟局域网",
            subtitle = "Network ID · 自定义 Planet · 节点身份导入导出",
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
                        text = "节点 ID: ${zeroTierState.nodeIdHex.ifBlank { "首次连接自动生成" }}",
                        color = HerdrColors.Foreground,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${zeroTierState.phase.label} · 虚拟 IP: ${zeroTierState.assignedIpv4.ifBlank { "待分配" }}",
                        color = HerdrColors.Muted,
                        fontSize = 11.sp
                    )
                }
                if (zeroTierState.nodeIdHex.isNotBlank()) {
                    TacticalActionPill(
                        text = "复制 ID",
                        accent = false,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(zeroTierState.nodeIdHex))
                            onShowBanner("已复制 ZeroTier Node ID: ${zeroTierState.nodeIdHex}")
                        }
                    )
                }
            }

            TacticalInputField(
                label = "ZeroTier Network ID (16 位十六进制)",
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
                        text = "Planet 根节点",
                        color = HerdrColors.Foreground,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = zeroTierState.planetSummary,
                        color = HerdrColors.Muted,
                        fontSize = 11.sp,
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
                        text = "重置",
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
                    text = "节点身份 (identity.secret)",
                    color = HerdrColors.Muted,
                    fontSize = 11.5.sp,
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
                        text = "导出",
                        accent = false,
                        onClick = {
                            onExportZtIdentity { exported ->
                                identityDraft = exported
                                clipboardManager.setText(AnnotatedString(exported))
                            }
                        }
                    )
                    TacticalActionPill(
                        text = "保存",
                        accent = true,
                        onClick = { onImportZtIdentity(identityDraft) }
                    )
                }
            }

            TacticalInputField(
                label = null,
                value = identityDraft,
                onValueChange = { identityDraft = it },
                placeholder = "留空自动生成持久化身份；或粘贴已有 identity.secret",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Section: SSH Credentials & Default Host Addresses
        SettingsSectionCard(
            title = "SSH 主机与密钥凭据",
            subtitle = "默认 IP · 用户名与端口 · Ed25519 私钥与指纹",
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
                    label = "SSH 用户名",
                    value = profile.username,
                    onValueChange = { u -> onUpdateProfile { it.copy(username = u.trim()) } },
                    placeholder = "herdr",
                    modifier = Modifier.weight(1.1f)
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
                    modifier = Modifier.weight(0.7f)
                )
                Column(
                    modifier = Modifier.weight(1.4f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "目标系统",
                        color = HerdrColors.Muted,
                        fontSize = 11.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(HerdrColors.Raised)
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
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (active) HerdrColors.Surface else Color.Transparent)
                                    .clickable { onUpdateProfile { it.copy(platform = plat) } },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = shortLabel,
                                    color = if (active) HerdrColors.Accent else HerdrColors.Muted,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 11.sp,
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
                label = "远端 hc 可执行文件路径",
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
                    fontSize = 11.5.sp,
                    softWrap = false,
                    maxLines = 1
                )
                TacticalActionPill(
                    text = "从文件导入",
                    accent = true,
                    onClick = { keyPickerLauncher.launch(arrayOf("*/*")) }
                )
            }

            TacticalInputField(
                label = null,
                value = profile.privateKeyPem,
                onValueChange = { pem -> onUpdateProfile { it.copy(privateKeyPem = pem) } },
                placeholder = "粘贴 -----BEGIN OPENSSH PRIVATE KEY----- 或从文件导入",
                singleLine = false,
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Section: App Version & GitHub Release Online Update
        SettingsSectionCard(
            title = "版本与在线更新",
            subtitle = "v${appUpdateState.currentVersion} · GitHub Release 在线升级 · 保留全部配置",
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = appUpdateState.statusMessage,
                        color = when (appUpdateState.status) {
                            UpdateStatus.AVAILABLE, UpdateStatus.READY_TO_INSTALL -> HerdrColors.Done
                            UpdateStatus.ERROR -> HerdrColors.Blocked
                            else -> HerdrColors.Muted
                        },
                        fontSize = 11.sp
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
                                "下载 ${appUpdateState.latestRelease?.tagName.orEmpty()}"
                            },
                            accent = true,
                            onClick = onDownloadAndInstallUpdate
                        )
                    }
                }
            }

            appUpdateState.latestRelease?.let { rel ->
                if (rel.releaseNotes.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(HerdrColors.Canvas.copy(alpha = 0.65f))
                            .padding(11.dp)
                    ) {
                        Text(
                            text = rel.releaseNotes,
                            color = HerdrColors.Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
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
            .clip(RoundedCornerShape(16.dp))
            .background(HerdrColors.Surface)
            .border(0.6.dp, HerdrColors.Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = HerdrColors.Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (expanded) "收起 ▴" else "展开 ▾",
                color = HerdrColors.Accent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
            .background(if (selected) HerdrColors.Surface else Color.Transparent)
            .then(
                if (selected) {
                    Modifier.border(0.8.dp, HerdrColors.Accent.copy(alpha = 0.65f), RoundedCornerShape(9.dp))
                } else {
                    Modifier
                }
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (selected) HerdrColors.Accent else HerdrColors.Dim)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = if (selected) HerdrColors.Foreground else HerdrColors.Muted,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 12.5.sp,
                softWrap = false,
                maxLines = 1
            )
        }
        Text(
            text = subtitle,
            color = if (selected) HerdrColors.Accent else HerdrColors.Dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(HerdrColors.Canvas.copy(alpha = 0.65f))
                .border(
                    width = if (focused) 1.2.dp else 0.7.dp,
                    color = if (focused) HerdrColors.Accent else HerdrColors.InputBorder,
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
            .clip(RoundedCornerShape(8.dp))
            .background(if (accent) HerdrColors.AccentSubtle else HerdrColors.Raised)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (accent) HerdrColors.Accent else HerdrColors.Foreground,
            fontSize = 11.5.sp,
            fontWeight = if (accent) FontWeight.Bold else FontWeight.Medium,
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
            .clip(RoundedCornerShape(16.dp))
            .background(HerdrColors.Surface)
            .border(
                width = 0.6.dp,
                color = if (connectionState is ConnectionState.Failed) HerdrColors.Blocked else HerdrColors.Border,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    text = "连接握手诊断",
                    color = HerdrColors.Foreground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp,
                    softWrap = false,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when (connectionState) {
                        is ConnectionState.Connected -> "✓ 四步握手已通过 · Core ${connectionState.hello.version}"
                        is ConnectionState.Connecting -> "正在执行第 ${connectionState.activeStage.stepNumber} 步：${connectionState.activeStage.title}"
                        is ConnectionState.Failed -> "! 阻塞于第 ${connectionState.failedStage.stepNumber} 步：${connectionState.failedStage.title}"
                        ConnectionState.Disconnected -> "待命中 · 点击查看四步握手状态"
                    },
                    color = when (connectionState) {
                        is ConnectionState.Connected -> HerdrColors.Done
                        is ConnectionState.Connecting -> HerdrColors.Working
                        is ConnectionState.Failed -> HerdrColors.Blocked
                        ConnectionState.Disconnected -> HerdrColors.Muted
                    },
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (showStages) "收起 ▴" else "展开 ▾",
                color = HerdrColors.Accent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                softWrap = false,
                maxLines = 1
            )
        }

        AnimatedVisibility(visible = showStages) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
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
                            .clip(RoundedCornerShape(11.dp))
                            .background(HerdrColors.Canvas.copy(alpha = 0.6f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(badgeBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = symbol,
                                color = badgeFg,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
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
                                text = item.stage.title,
                                color = HerdrColors.Foreground,
                                fontWeight = FontWeight.SemiBold,
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
                                    text = "建议：${item.hint}",
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(HerdrColors.BlockedSubtle)
                    .padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "受阻阶段：${connectionState.failedStage.title} (${connectionState.errorKind})",
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
                    text = "建议：${connectionState.actionableHint}",
                    color = HerdrColors.Working,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
                if (detectedSha256 != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(HerdrColors.Accent)
                            .clickable { onTrustFingerprint(detectedSha256) }
                            .padding(vertical = 10.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "信任并填入服务端指纹重连",
                            color = HerdrColors.OnAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
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
    onTakeover: (TerminalSummary) -> Unit,
    onGoToConnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Filter strip + Refresh button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                                width = 0.6.dp,
                                color = if (active) HerdrColors.Accent else HerdrColors.Border,
                                shape = RoundedCornerShape(999.dp)
                            )
                            .clickable { onSelectFilter(word) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (active) HerdrColors.Accent else HerdrColors.Muted,
                            fontSize = 11.5.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
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

        if (nodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "暂无活跃终端节点",
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp
                    )
                    Text(
                        text = "请先在「连接」页建立远端会话，或切换筛选条件",
                        color = HerdrColors.Muted,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(HerdrColors.Accent)
                            .clickable { onGoToConnect() }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "前往连接工作台 →",
                            color = HerdrColors.OnAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
            .padding(start = (node.depth * 16).dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isChild) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp)
                    .background(HerdrColors.Accent.copy(alpha = 0.45f))
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(14.dp))
                .background(HerdrColors.Surface)
                .border(
                    width = if (item.agentStatus == AgentStateWord.BLOCKED) 1.2.dp else 0.6.dp,
                    color = if (item.agentStatus == AgentStateWord.BLOCKED) {
                        HerdrColors.Blocked
                    } else {
                        HerdrColors.Border
                    },
                    shape = RoundedCornerShape(14.dp)
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
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Row 1: Header (Address Badge + Name on Left, App + Status Pills on Right)
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
                                .clip(RoundedCornerShape(6.dp))
                                .background(HerdrColors.AccentSubtle)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = item.address,
                                color = HerdrColors.Accent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                        if (!item.name.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.name.orEmpty(),
                                color = HerdrColors.Foreground,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
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
                                .clip(RoundedCornerShape(6.dp))
                                .background(HerdrColors.Raised)
                                .padding(horizontal = 6.dp, vertical = 2.5.dp)
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
                                .clip(RoundedCornerShape(6.dp))
                                .background(statusColor.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 2.5.dp)
                        )
                    }
                }

                // Row 2: Workspace Title & Agent Latest Summary
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = item.workspaceSubtitle,
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!item.agentMessage.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(HerdrColors.Canvas.copy(alpha = 0.6f))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
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
                            text = "观看",
                            accent = false,
                            onClick = onObserve
                        )
                        TacticalActionPill(
                            text = "接管",
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
                    text = "尚未挂载终端会话",
                    color = HerdrColors.Foreground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp
                )
                Text(
                    text = "请先从「拓扑」选择一个终端进入观看或接管模式。",
                    color = HerdrColors.Muted,
                    fontSize = 12.5.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(HerdrColors.Raised)
                            .clickable { onNavigateBack() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "← 返回",
                            color = HerdrColors.Foreground,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(HerdrColors.Accent)
                            .clickable { onPickTerminal() }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "选择终端 →",
                            color = HerdrColors.OnAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
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

    val activeThemePreset = HerdrColors.currentPalette.preset
    val styledScreen = remember(rawScreen, activeThemePreset) {
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
        // Slim Top Micro-Header + Auto-Hiding Control Drawer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HerdrColors.Chrome)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(HerdrColors.Raised)
                        .clickable { onNavigateBack() }
                        .padding(horizontal = 9.dp, vertical = 4.5.dp)
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
                        fontSize = 12.5.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = summary.name ?: summary.workspaceSubtitle,
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (isMobilePtyLocked) HerdrColors.AccentSubtle else HerdrColors.Raised)
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = "${curCols}×${curRows}",
                            color = if (isMobilePtyLocked) HerdrColors.Accent else HerdrColors.Muted,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isMobilePtyLocked) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 10.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isMobilePtyLocked) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(HerdrColors.Accent)
                                .clickable {
                                    fontZoomDeltaSp = 0f
                                    topBarInteractionTick++
                                    coroutineScope.launch { hScroll.scrollTo(0) }
                                    onTakeoverPtyWidth(mobilePtyTargetCols)
                                }
                                .padding(horizontal = 9.dp, vertical = 4.5.dp)
                        ) {
                            Text(
                                text = "PTY ${mobilePtyTargetCols}列",
                                color = HerdrColors.OnAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    } else {
                        val origColsLabel = desktopOrigDims?.first ?: appSettings.defaultDesktopFallbackCols
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(HerdrColors.WorkingSubtle)
                                .clickable {
                                    fontZoomDeltaSp = 0f
                                    topBarInteractionTick++
                                    onReleasePtyWidth()
                                }
                                .padding(horizontal = 9.dp, vertical = 4.5.dp)
                        ) {
                            Text(
                                text = "释放(${origColsLabel}列)",
                                color = HerdrColors.Working,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(HerdrColors.Raised)
                            .clickable {
                                topBarExpanded = !topBarExpanded
                                topBarInteractionTick++
                            }
                            .padding(horizontal = 8.dp, vertical = 4.5.dp)
                    ) {
                        Text(
                            text = if (topBarExpanded) "▴" else "▾",
                            color = HerdrColors.Foreground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }

            AnimatedVisibility(visible = topBarExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        listOf(56, 68, 80).forEach { presetCols ->
                            val isSelected = curCols == presetCols
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) HerdrColors.AccentSubtle else HerdrColors.Raised)
                                    .clickable {
                                        fontZoomDeltaSp = 0f
                                        topBarInteractionTick++
                                        coroutineScope.launch { hScroll.scrollTo(0) }
                                        onTakeoverPtyWidth(presetCols)
                                    }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "${presetCols}列",
                                    color = if (isSelected) HerdrColors.Accent else HerdrColors.Muted,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
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
                                .clickable {
                                    topBarInteractionTick++
                                    fontZoomDeltaSp = (fontZoomDeltaSp - 0.75f).coerceAtLeast(-3.5f)
                                }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "A-",
                                color = HerdrColors.Foreground,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(HerdrColors.Raised)
                                .clickable {
                                    topBarInteractionTick++
                                    fontZoomDeltaSp = (fontZoomDeltaSp + 0.75f).coerceAtMost(5.0f)
                                }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "A+",
                                color = HerdrColors.Foreground,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (softWrapLines) HerdrColors.AccentSubtle else HerdrColors.Raised)
                                .clickable {
                                    topBarInteractionTick++
                                    softWrapLines = !softWrapLines
                                }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (softWrapLines) "折行" else "1:1等宽",
                                color = if (softWrapLines) HerdrColors.Accent else HerdrColors.Muted,
                                fontSize = 10.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (mode == AttachMode.TAKEOVER) HerdrColors.DoneSubtle else HerdrColors.Raised)
                                .clickable {
                                    topBarInteractionTick++
                                    onToggleMode()
                                }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (mode == AttachMode.TAKEOVER) "可输入" else "只读",
                                color = if (mode == AttachMode.TAKEOVER) HerdrColors.Done else HerdrColors.Muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(HerdrColors.Raised)
                                .clickable { onOpenSettings() }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "⚙",
                                color = HerdrColors.Muted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(HerdrColors.Border)
        )

        // Native 1:1 PTY Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(HerdrColors.Canvas)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = HerdrColors.Canvas
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val innerWidthDp = (maxWidth - 20.dp).value.coerceAtLeast(240f)
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
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(vScroll)
                            .horizontalScroll(hScroll, enabled = hScroll.maxValue > 4)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
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
                        .padding(14.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(HerdrColors.Accent)
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
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "↓ 回到底部",
                        color = HerdrColors.OnAccent,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        softWrap = false,
                        maxLines = 1
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(HerdrColors.Border)
        )

        // Clean Bottom Dock (Special Keys + Input Bar)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HerdrColors.Chrome)
                .padding(horizontal = 10.dp, vertical = 7.dp),
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
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    when {
                                        isInterruptKey -> HerdrColors.BlockedSubtle
                                        isExpandKey -> HerdrColors.AccentSubtle
                                        else -> HerdrColors.Raised
                                    }
                                )
                                .clickable {
                                    if (isInterruptKey) {
                                        onInterrupt()
                                    } else {
                                        onSendSpecialKey(wireKey)
                                    }
                                }
                                .padding(vertical = 6.dp),
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
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(HerdrColors.Surface)
                            .clickable {
                                pendingFocusAfterUnlock = true
                                onToggleMode()
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "只读观看模式 · 点击切换为输入接管",
                            color = HerdrColors.Muted,
                            fontSize = 12.sp,
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
                            fontSize = 11.5.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (showSpecialKeyStrip) HerdrColors.AccentSubtle else HerdrColors.Raised)
                            .clickable { showSpecialKeyStrip = !showSpecialKeyStrip }
                            .padding(horizontal = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (showSpecialKeyStrip) "按键 ▾" else "按键 ▴",
                            color = if (showSpecialKeyStrip) HerdrColors.Accent else HerdrColors.Foreground,
                            fontSize = 11.5.sp,
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
                            .height(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(HerdrColors.Surface)
                            .border(
                                width = if (inputFocused) 1.2.dp else 0.6.dp,
                                color = if (inputFocused) HerdrColors.Accent else HerdrColors.InputBorder,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "❯",
                            color = HerdrColors.Accent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (inputBuffer.isEmpty()) {
                                Text(
                                    text = "输入指令发送至 ${session.address}...",
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
                            .height(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (showSpecialKeyStrip) HerdrColors.AccentSubtle else HerdrColors.Raised)
                            .clickable { showSpecialKeyStrip = !showSpecialKeyStrip }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (showSpecialKeyStrip) "键 ▾" else "键 ▴",
                            color = if (showSpecialKeyStrip) HerdrColors.Accent else HerdrColors.Foreground,
                            fontSize = 11.5.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }

                    val canSend = inputBuffer.isNotBlank()
                    Box(
                        modifier = Modifier
                            .height(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (canSend) HerdrColors.Accent else HerdrColors.Raised)
                            .clickable(enabled = canSend) {
                                onSendLine(inputBuffer, false)
                                inputBuffer = ""
                            }
                            .padding(horizontal = 15.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "发送",
                            color = if (canSend) HerdrColors.OnAccent else HerdrColors.Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
