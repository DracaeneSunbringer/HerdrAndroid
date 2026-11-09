package dev.herdr.android.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import dev.herdr.android.ui.terminal.QuickReplyAction
import dev.herdr.android.ui.terminal.StructuredTerminalReader
import dev.herdr.android.ui.terminal.TerminalSemanticBlock
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
    val appSettings by viewModel.appSettings.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val allTerminals by viewModel.terminals.collectAsState()
    val agentNodes by viewModel.agentTreeNodes.collectAsState()
    val totalLiveCount by viewModel.totalLiveCount.collectAsState()
    val statusCounts by viewModel.statusCounts.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val activeSummary by viewModel.activeTerminalSummary.collectAsState()
    val savedDesktopDims by viewModel.savedDesktopDims.collectAsState()
    val mobilePtyTargetCols by viewModel.mobilePtyTargetCols.collectAsState()
    val zeroTierState by viewModel.zeroTierOverlayState.collectAsState()
    val appUpdateState by viewModel.appUpdateState.collectAsState()
    val toastBanner by viewModel.toastBanner.collectAsState()
    val acknowledgedDoneTurns by viewModel.acknowledgedDoneTurns.collectAsState()
    val draftsByAddress by viewModel.draftsByAddress.collectAsState()
    val dispatchedReceipts by viewModel.dispatchedReceipts.collectAsState()
    val requestInputFocusSignal by viewModel.requestInputFocusSignal.collectAsState()
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

    val effectiveHostLabel = (connectionState as? ConnectionState.Connected)?.profile?.host ?: profile.host

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HerdrColors.Canvas)
            .imePadding()
    ) {
        // Minimal top telemetry & status pill bar (hidden inside full-screen Agent Workbench)
        AnimatedVisibility(visible = selectedTab != WorkspaceTab.TERMINAL) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopTelemetryBar(
                    selectedTab = selectedTab,
                    connectionState = connectionState,
                    terminalCount = totalLiveCount,
                    hostLabel = effectiveHostLabel,
                    currentTheme = appSettings.themePreset,
                    themeDrawerExpanded = showQuickThemeDrawer,
                    onNavigateBackToFleet = { viewModel.selectTab(WorkspaceTab.AGENTS) },
                    onOpenConnectionSheet = {
                        if (selectedTab == WorkspaceTab.CONNECTION) {
                            viewModel.selectTab(WorkspaceTab.AGENTS)
                        } else {
                            viewModel.selectTab(WorkspaceTab.CONNECTION)
                        }
                    },
                    onOpenSettings = {
                        if (selectedTab == WorkspaceTab.SETTINGS) {
                            viewModel.selectTab(WorkspaceTab.AGENTS)
                        } else {
                            viewModel.selectTab(WorkspaceTab.SETTINGS)
                        }
                    },
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

        // Main Two-Layer Workspace Content (Fleet Triage Inbox <-> Agent Dispatch Workbench, with Overlay Connection/Settings)
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                WorkspaceTab.CONNECTION -> ConnectionAndDiagnosticsPane(
                    profile = profile,
                    appSettings = appSettings,
                    connectionState = connectionState,
                    zeroTierState = zeroTierState,
                    agentCount = totalLiveCount,
                    onUpdateProfile = viewModel::updateProfile,
                    onUpdateAppSettings = viewModel::updateAppSettings,
                    onApplyLanPreset = viewModel::applyLanPreset,
                    onApplyPreset = viewModel::applyPreset,
                    onOpenSettings = { viewModel.selectTab(WorkspaceTab.SETTINGS) },
                    onShowBanner = viewModel::showBanner,
                    onConnect = {
                        if (Build.VERSION.SDK_INT >= 33 && appSettings.keepAliveInBackground) {
                            runCatching {
                                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        viewModel.connectNow()
                    },
                    onRefreshTerminals = viewModel::refreshTerminals,
                    onDisconnect = viewModel::disconnectNow,
                    onJumpToAgents = { viewModel.selectTab(WorkspaceTab.AGENTS) }
                )

                WorkspaceTab.AGENTS -> AgentsTopologyPane(
                    nodes = agentNodes,
                    allTerminals = allTerminals,
                    totalLiveCount = totalLiveCount,
                    statusCounts = statusCounts,
                    connectionState = connectionState,
                    activeTerminalAddress = activeSession?.address,
                    currentFilter = statusFilter,
                    acknowledgedDoneTurns = acknowledgedDoneTurns,
                    dispatchedReceipts = dispatchedReceipts,
                    onSelectFilter = viewModel::setStatusFilter,
                    onRefresh = viewModel::refreshTerminals,
                    onCardClick = { viewModel.attachTerminal(it, AttachMode.TAKEOVER) },
                    onWriteNextInstruction = viewModel::openTerminalAndFocusInput,
                    onQuickDispatch = viewModel::quickDispatchToTerminal,
                    onAcknowledgeDone = viewModel::markTerminalDoneAcknowledged,
                    onConnectNow = {
                        if (Build.VERSION.SDK_INT >= 33 && appSettings.keepAliveInBackground) {
                            runCatching {
                                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        viewModel.connectNow()
                    },
                    onEnterDemoMode = {
                        viewModel.applyPreset(true)
                        viewModel.connectNow()
                    },
                    onGoToConnect = { viewModel.selectTab(WorkspaceTab.CONNECTION) }
                )

                WorkspaceTab.TERMINAL -> LiveTerminalAttachPane(
                    session = activeSession,
                    summary = activeSummary,
                    allLiveTerminals = allTerminals.filter { it.state == "live" },
                    acknowledgedDoneTurns = acknowledgedDoneTurns,
                    draftText = activeSession?.address?.let { draftsByAddress[it] }.orEmpty(),
                    dispatchedReceipt = activeSession?.address?.let { dispatchedReceipts[it] },
                    requestInputFocusSignal = requestInputFocusSignal,
                    appSettings = appSettings,
                    savedDesktopDims = savedDesktopDims,
                    mobilePtyTargetCols = mobilePtyTargetCols,
                    onNavigateBack = { viewModel.navigateBack() },
                    onOpenSettings = { viewModel.selectTab(WorkspaceTab.SETTINGS) },
                    onSwitchTerminal = { target ->
                        viewModel.attachTerminal(target, AttachMode.TAKEOVER)
                    },
                    onUpdateDraft = { text ->
                        activeSession?.address?.let { addr ->
                            viewModel.updateDraftForTerminal(addr, text)
                        }
                    },
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
    }
}

@Composable
private fun TopTelemetryBar(
    selectedTab: WorkspaceTab,
    connectionState: ConnectionState,
    terminalCount: Int,
    hostLabel: String,
    currentTheme: HerdrThemePreset,
    themeDrawerExpanded: Boolean,
    onNavigateBackToFleet: () -> Unit,
    onOpenConnectionSheet: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleThemeDrawer: () -> Unit
) {
    val isSecondarySheet = selectedTab == WorkspaceTab.CONNECTION || selectedTab == WorkspaceTab.SETTINGS

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HerdrColors.Chrome)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSecondarySheet) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(HerdrColors.AccentSubtle)
                        .clickable { onNavigateBackToFleet() }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "← 牧群",
                        color = HerdrColors.Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(HerdrColors.AccentSubtle)
                        .clickable { onOpenConnectionSheet() },
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
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenConnectionSheet() }
                    .padding(vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (selectedTab) {
                            WorkspaceTab.CONNECTION -> "链路与网络诊断"
                            WorkspaceTab.SETTINGS -> "全局偏好设置"
                            else -> "Herdr 牧群台"
                        },
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp,
                        letterSpacing = (-0.3).sp,
                        softWrap = false,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    val (dotColor, pillText) = when (connectionState) {
                        is ConnectionState.Connected -> HerdrColors.Done to "已连接"
                        is ConnectionState.Connecting -> HerdrColors.Working to "重连中"
                        is ConnectionState.Failed -> HerdrColors.Blocked to "链路受阻"
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
                        Spacer(modifier = Modifier.width(4.5.dp))
                        Text(
                            text = "$pillText ▾",
                            color = dotColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
                Spacer(modifier = Modifier.height(1.5.dp))
                Text(
                    text = when (connectionState) {
                        is ConnectionState.Connected ->
                            "$hostLabel · $terminalCount 个在管终端 · 点击切换网络"
                        is ConnectionState.Connecting ->
                            "正在执行：${connectionState.activeStage.title} ($hostLabel)"
                        is ConnectionState.Failed ->
                            "受阻于：${connectionState.failedStage.title} · 点击诊断"
                        ConnectionState.Disconnected ->
                            "$hostLabel · 点击连接或开启离线演示"
                    },
                    color = HerdrColors.Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 1-Tap Quick Theme Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (themeDrawerExpanded) HerdrColors.AccentSubtle else HerdrColors.Raised)
                    .clickable { onToggleThemeDrawer() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.5.dp)
                        .clip(CircleShape)
                        .background(HerdrColors.Accent)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = currentTheme.title,
                    color = if (themeDrawerExpanded) HerdrColors.Accent else HerdrColors.Foreground,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    softWrap = false,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 1-Tap Settings Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selectedTab == WorkspaceTab.SETTINGS) HerdrColors.AccentSubtle else HerdrColors.Raised)
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚙ 设置",
                    color = if (selectedTab == WorkspaceTab.SETTINGS) HerdrColors.Accent else HerdrColors.Foreground,
                    fontSize = 11.sp,
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
private fun ConnectionAndDiagnosticsPane(
    profile: ConnectionProfile,
    appSettings: HerdrAppSettings,
    connectionState: ConnectionState,
    zeroTierState: ZeroTierOverlayState,
    agentCount: Int,
    onUpdateProfile: ((ConnectionProfile) -> ConnectionProfile) -> Unit,
    onUpdateAppSettings: ((HerdrAppSettings) -> HerdrAppSettings) -> Unit,
    onApplyLanPreset: () -> Unit,
    onApplyPreset: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onShowBanner: (String) -> Unit,
    onConnect: () -> Unit,
    onRefreshTerminals: () -> Unit,
    onDisconnect: () -> Unit,
    onJumpToAgents: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showQuickTargetOverride by remember { mutableStateOf(false) }
    var batteryWhitelisted by remember {
        mutableStateOf(HerdrKeepAliveCoordinator.isIgnoringBatteryOptimizations(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryWhitelisted = HerdrKeepAliveCoordinator.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val lanDefaultHost = appSettings.defaultLanHost.ifBlank { "192.168.1.100" }
    val ztDefaultHost = appSettings.defaultZeroTierHost.ifBlank { "10.147.17.100" }
    val isPreview = profile.host.startsWith("demo://")
    val isZeroTier = (!isPreview && (profile.useBuiltInZeroTier || profile.host == ztDefaultHost))
    val isLan = (!isPreview && !isZeroTier)
    val isConnectedOrConnecting = connectionState is ConnectionState.Connected || connectionState is ConnectionState.Connecting

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
                    onClick = {
                        val changed = !isLan
                        onApplyLanPreset()
                        if (changed && isConnectedOrConnecting) {
                            onConnect()
                        }
                    }
                )
                PresetTargetCard(
                    title = "ZeroTier",
                    subtitle = ztDefaultHost,
                    selected = isZeroTier,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val changed = !isZeroTier
                        onApplyPreset(false)
                        if (changed && isConnectedOrConnecting) {
                            onConnect()
                        }
                    }
                )
                PresetTargetCard(
                    title = "离线演示",
                    subtitle = "本地拓扑预览",
                    selected = isPreview,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val changed = !isPreview
                        onApplyPreset(true)
                        if (changed && isConnectedOrConnecting) {
                            onConnect()
                        }
                    }
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

            // Primary Action Buttons Row (4-State Machine: Disconnected / Connecting / Failed / Connected)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (connectionState) {
                    is ConnectionState.Connected -> {
                        Box(
                            modifier = Modifier
                                .weight(1.35f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(HerdrColors.Accent)
                                .clickable { onJumpToAgents() }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "进入终端拓扑 ($agentCount) →",
                                color = HerdrColors.OnAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }

                        Box(
                            modifier = Modifier
                                .height(46.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(HerdrColors.Raised)
                                .clickable { onRefreshTerminals() }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "↻ 同步",
                                color = HerdrColors.Foreground,
                                fontWeight = FontWeight.SemiBold,
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

                    is ConnectionState.Connecting -> {
                        Box(
                            modifier = Modifier
                                .weight(1.35f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(HerdrColors.WorkingSubtle)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "正在握手 (${connectionState.activeStage.stepNumber}/4) · ${connectionState.activeStage.title}",
                                color = HerdrColors.Working,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Box(
                            modifier = Modifier
                                .height(46.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(HerdrColors.BlockedSubtle)
                                .clickable { onDisconnect() }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "取消",
                                color = HerdrColors.Blocked,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    }

                    is ConnectionState.Failed -> {
                        Box(
                            modifier = Modifier
                                .weight(1.35f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(HerdrColors.Accent)
                                .clickable { onConnect() }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "重试加密连接",
                                color = HerdrColors.OnAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }

                        Box(
                            modifier = Modifier
                                .height(46.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(HerdrColors.Raised)
                                .clickable { onDisconnect() }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "重置",
                                color = HerdrColors.Muted,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    }

                    ConnectionState.Disconnected -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(HerdrColors.Accent)
                                .clickable { onConnect() }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "建立加密连接",
                                color = HerdrColors.OnAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
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

        // Progressive-Disclosure Handshake Diagnostics Card (visible directly on first screen)
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
    var showZtIdentityPlaintext by remember { mutableStateOf(false) }
    var showSshKeyPlaintext by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryWhitelisted by remember {
        mutableStateOf(HerdrKeepAliveCoordinator.isIgnoringBatteryOptimizations(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryWhitelisted = HerdrKeepAliveCoordinator.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    if (!batteryWhitelisted) {
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
                    if (identityDraft.isNotBlank()) {
                        TacticalActionPill(
                            text = if (showZtIdentityPlaintext) "隐藏明文" else "显示明文",
                            accent = showZtIdentityPlaintext,
                            onClick = { showZtIdentityPlaintext = !showZtIdentityPlaintext }
                        )
                    }
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
                                copySensitiveTextToClipboard(context, "ZeroTier Identity Secret", exported)
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

            if (showZtIdentityPlaintext || identityDraft.isBlank()) {
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
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HerdrColors.Canvas.copy(alpha = 0.65f))
                        .border(0.7.dp, HerdrColors.InputBorder, RoundedCornerShape(10.dp))
                        .clickable { showZtIdentityPlaintext = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "✓ 已配置持久化节点身份密钥 (${identityDraft.length} 字符 · 已隐藏明文)",
                        color = HerdrColors.Done,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "查看/编辑",
                        color = HerdrColors.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
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
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (profile.privateKeyPem.isNotBlank()) {
                        TacticalActionPill(
                            text = if (showSshKeyPlaintext) "隐藏明文" else "显示明文",
                            accent = showSshKeyPlaintext,
                            onClick = { showSshKeyPlaintext = !showSshKeyPlaintext }
                        )
                    }
                    TacticalActionPill(
                        text = "从文件导入",
                        accent = true,
                        onClick = { keyPickerLauncher.launch(arrayOf("*/*")) }
                    )
                }
            }

            if (showSshKeyPlaintext || profile.privateKeyPem.isBlank()) {
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
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HerdrColors.Canvas.copy(alpha = 0.65f))
                        .border(0.7.dp, HerdrColors.InputBorder, RoundedCornerShape(10.dp))
                        .clickable { showSshKeyPlaintext = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "✓ 已配置 Ed25519 私钥 (${profile.privateKeyPem.lines().size} 行 / ${profile.privateKeyPem.length} 字符 · 已隐藏明文)",
                        color = HerdrColors.Done,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "查看/编辑",
                        color = HerdrColors.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
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
    var showStages by remember(connectionState::class) { mutableStateOf(isActiveOrFailed) }
    var pendingTrustFingerprint by remember { mutableStateOf<String?>(null) }
    val isHostKeyChanged = (connectionState as? ConnectionState.Failed)?.errorKind == "host_key_changed"

    pendingTrustFingerprint?.let { fp ->
        val formattedFp = remember(fp) {
            fp.removePrefix("SHA256:").chunked(8).joinToString(" ")
        }
        AlertDialog(
            onDismissRequest = { pendingTrustFingerprint = null },
            containerColor = HerdrColors.Surface,
            titleContentColor = HerdrColors.Foreground,
            textContentColor = HerdrColors.Foreground,
            title = {
                Text(
                    text = if (isHostKeyChanged) {
                        "⚠️ 警告：远端主机指纹发生变更"
                    } else {
                        "确认信任 SSH 主机指纹"
                    },
                    color = if (isHostKeyChanged) HerdrColors.Blocked else HerdrColors.Foreground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (isHostKeyChanged) {
                            "远端主机返回的公钥指纹与您之前固定信任的指纹不一致！这可能是由于远端重装了系统/OpenSSH，或者当前网络存在中间人攻击 (MITM)。请务必在远端机器核对以下 SHA256 指纹："
                        } else {
                            "首次建立固定指纹信任前，请核对远端 OpenSSH 服务端的公钥 SHA256 指纹："
                        },
                        color = if (isHostKeyChanged) HerdrColors.Blocked else HerdrColors.Muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(HerdrColors.Canvas)
                            .border(0.8.dp, HerdrColors.Border, RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "SHA256:",
                                color = HerdrColors.Accent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = formattedFp,
                                color = HerdrColors.Foreground,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isHostKeyChanged) HerdrColors.Blocked else HerdrColors.Accent)
                        .clickable {
                            val targetFp = fp
                            pendingTrustFingerprint = null
                            onTrustFingerprint(targetFp)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isHostKeyChanged) "确认覆盖并重连" else "确认信任并重连",
                        color = if (isHostKeyChanged) Color.White else HerdrColors.OnAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp
                    )
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(HerdrColors.Raised)
                        .clickable { pendingTrustFingerprint = null }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "取消",
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.5.sp
                    )
                }
            }
        )
    }

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
                .clickable { showStages = !showStages },
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
                            .background(if (isHostKeyChanged) HerdrColors.Blocked else HerdrColors.Accent)
                            .clickable { pendingTrustFingerprint = detectedSha256 }
                            .padding(vertical = 10.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isHostKeyChanged) {
                                "⚠️ 核对并覆盖服务端指纹..."
                            } else {
                                "核对并信任服务端指纹..."
                            },
                            color = if (isHostKeyChanged) Color.White else HerdrColors.OnAccent,
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
    allTerminals: List<TerminalSummary>,
    totalLiveCount: Int,
    statusCounts: Map<AgentStateWord, Int>,
    connectionState: ConnectionState,
    activeTerminalAddress: String?,
    currentFilter: AgentStateWord?,
    acknowledgedDoneTurns: Map<String, Int>,
    dispatchedReceipts: Map<String, DispatchedReceipt>,
    onSelectFilter: (AgentStateWord?) -> Unit,
    onRefresh: () -> Unit,
    onCardClick: (TerminalSummary) -> Unit,
    onWriteNextInstruction: (TerminalSummary) -> Unit,
    onQuickDispatch: (TerminalSummary, String, Boolean) -> Unit,
    onAcknowledgeDone: (TerminalSummary) -> Unit,
    onConnectNow: () -> Unit,
    onEnterDemoMode: () -> Unit,
    onGoToConnect: () -> Unit
) {
    val liveTerminals = remember(allTerminals) { allTerminals.filter { it.state == "live" } }
    val childrenByParent = remember(liveTerminals) {
        val byAddress = liveTerminals.associateBy { it.address }
        val byShortId = liveTerminals.associateBy { it.address.substringAfterLast('/') }
        val result = mutableMapOf<String, MutableList<TerminalSummary>>()
        for (term in liveTerminals) {
            val rawParent = term.parent?.trim().orEmpty()
            if (rawParent.isEmpty()) continue
            val resolvedParent = byAddress[rawParent] ?: byShortId[rawParent.substringAfterLast('/')]
            if (resolvedParent != null && resolvedParent.address != term.address) {
                result.getOrPut(resolvedParent.address) { mutableListOf() }.add(term)
            }
        }
        result
    }

    val allUrgentCount = remember(liveTerminals) {
        liveTerminals.count { it.agentStatus == AgentStateWord.BLOCKED || it.agentStatus == AgentStateWord.ERROR }
    }
    val allReadyDoneCount = remember(liveTerminals, acknowledgedDoneTurns) {
        liveTerminals.count {
            it.agentStatus == AgentStateWord.DONE && acknowledgedDoneTurns[it.address] != it.turn
        }
    }
    val allWorkingCount = remember(liveTerminals) {
        liveTerminals.count { it.agentStatus == AgentStateWord.WORKING }
    }
    val allQuietCount = remember(liveTerminals, acknowledgedDoneTurns) {
        liveTerminals.count {
            it.agentStatus == AgentStateWord.IDLE ||
                it.agentStatus == AgentStateWord.NONE ||
                (it.agentStatus == AgentStateWord.DONE && acknowledgedDoneTurns[it.address] == it.turn)
        }
    }

    val urgentNodes = remember(nodes) {
        nodes.filter {
            it.summary.agentStatus == AgentStateWord.BLOCKED || it.summary.agentStatus == AgentStateWord.ERROR
        }
    }
    val readyDoneNodes = remember(nodes, acknowledgedDoneTurns) {
        nodes.filter {
            it.summary.agentStatus == AgentStateWord.DONE &&
                acknowledgedDoneTurns[it.summary.address] != it.summary.turn
        }
    }
    val workingNodes = remember(nodes) {
        nodes.filter { it.summary.agentStatus == AgentStateWord.WORKING }
    }
    val quietArchiveNodes = remember(nodes, acknowledgedDoneTurns) {
        nodes.filter {
            it.summary.agentStatus == AgentStateWord.IDLE ||
                it.summary.agentStatus == AgentStateWord.NONE ||
                (it.summary.agentStatus == AgentStateWord.DONE &&
                    acknowledgedDoneTurns[it.summary.address] == it.summary.turn)
        }
    }

    var expandQuietArchive by remember(currentFilter) {
        mutableStateOf(currentFilter == AgentStateWord.IDLE || currentFilter == AgentStateWord.DONE)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Inline Connection Status & Recovery Banner when not yet connected
        if (connectionState !is ConnectionState.Connected) {
            val isConnecting = connectionState is ConnectionState.Connecting
            val isFailed = connectionState is ConnectionState.Failed
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        when {
                            isConnecting -> HerdrColors.WorkingSubtle
                            isFailed -> HerdrColors.BlockedSubtle
                            else -> HerdrColors.Surface
                        }
                    )
                    .border(
                        width = 0.8.dp,
                        color = when {
                            isConnecting -> HerdrColors.Working
                            isFailed -> HerdrColors.Blocked
                            else -> HerdrColors.Border
                        },
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (connectionState) {
                                is ConnectionState.Connecting ->
                                    "正在建立加密连接 · 第 ${connectionState.activeStage.stepNumber} 步 ${connectionState.activeStage.title}"
                                is ConnectionState.Failed ->
                                    "链路受阻 · ${connectionState.failedStage.title}"
                                else ->
                                    "等待连接远端牧群主机"
                            },
                            color = when {
                                isConnecting -> HerdrColors.Working
                                isFailed -> HerdrColors.Blocked
                                else -> HerdrColors.Foreground
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when (connectionState) {
                                is ConnectionState.Connecting ->
                                    "若局域网不可达且已配置 ZeroTier，将自动回退虚拟局域网..."
                                is ConnectionState.Failed ->
                                    connectionState.message
                                else ->
                                    "一键连接远端 SSH，或直接体验内置离线多 Agent 演示"
                            },
                            color = HerdrColors.Muted,
                            fontSize = 11.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(HerdrColors.Accent)
                            .clickable { onConnectNow() }
                            .padding(horizontal = 13.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = if (isConnecting) "重新握手" else "立即连接",
                            color = HerdrColors.OnAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(HerdrColors.Raised)
                            .clickable { onEnterDemoMode() }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "离线演示",
                            color = HerdrColors.Foreground,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(HerdrColors.Raised)
                            .clickable { onGoToConnect() }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "链路诊断与切换 →",
                            color = HerdrColors.Accent,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Triage Filter Strip + Refresh
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
                val filters: List<Triple<AgentStateWord?, String, Color>> = listOf(
                    Triple(null, "分诊收件箱 $totalLiveCount", HerdrColors.Accent),
                    Triple(
                        AgentStateWord.BLOCKED,
                        if (allUrgentCount > 0) "🔴 等你拍板 $allUrgentCount" else "等你拍板",
                        HerdrColors.Blocked
                    ),
                    Triple(
                        AgentStateWord.DONE,
                        if (allReadyDoneCount > 0) "🟢 待派活 $allReadyDoneCount" else "已完成 ${statusCounts[AgentStateWord.DONE] ?: 0}",
                        HerdrColors.Done
                    ),
                    Triple(
                        AgentStateWord.WORKING,
                        if (allWorkingCount > 0) "⚡ 运行中 $allWorkingCount" else "运行中",
                        HerdrColors.Working
                    ),
                    Triple(
                        AgentStateWord.IDLE,
                        if (allQuietCount > 0) "🌙 归档 $allQuietCount" else "归档",
                        HerdrColors.Muted
                    )
                )
                filters.forEach { (word, label, highlightColor) ->
                    val active = currentFilter == word
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (active) highlightColor.copy(alpha = 0.16f) else HerdrColors.Surface
                            )
                            .border(
                                width = if (active) 1.dp else 0.6.dp,
                                color = if (active) highlightColor else HerdrColors.Border,
                                shape = RoundedCornerShape(999.dp)
                            )
                            .clickable { onSelectFilter(word) }
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (active) highlightColor else HerdrColors.Muted,
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
            val isConnected = connectionState is ConnectionState.Connected
            val isFilteredEmpty = isConnected && totalLiveCount > 0 && currentFilter != null
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
                        text = when {
                            !isConnected -> "牧群收件箱待命中"
                            isFilteredEmpty -> "该分诊分组下暂无终端"
                            else -> "远端暂无活跃 Agent 终端"
                        },
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp
                    )
                    Text(
                        text = when {
                            !isConnected -> "点击上方「立即连接」建立远端会话，或点「离线演示」体验分诊与批示流"
                            isFilteredEmpty -> "共有 $totalLiveCount 个在管终端，当前筛选条件下无匹配项"
                            else -> "远端 Herdr Core 运行正常，当前尚未启动托管终端"
                        },
                        color = HerdrColors.Muted,
                        fontSize = 12.sp
                    )
                    if (isFilteredEmpty) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(HerdrColors.Accent)
                                .clickable { onSelectFilter(null) }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "返回全部牧群 ($totalLiveCount)",
                                color = HerdrColors.OnAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Tier 1: Urgent Blocked / Error (Needs immediate user decision)
                if (urgentNodes.isNotEmpty()) {
                    item(key = "header_urgent") {
                        TriageSectionHeader(
                            badgeText = "🔴 等你拍板 · 阻塞待批示",
                            count = urgentNodes.size,
                            accentColor = HerdrColors.Blocked,
                            subtitle = "支持在卡片内一键批准/确认，或进入批示台查看上下文"
                        )
                    }
                    items(urgentNodes, key = { "urgent_${it.summary.address}" }) { node ->
                        UrgentTriageCard(
                            node = node,
                            childHelpers = childrenByParent[node.summary.address].orEmpty(),
                            isCurrentlyAttached = node.summary.address == activeTerminalAddress,
                            recentReceipt = dispatchedReceipts[node.summary.address],
                            onOpenWorkbench = { onCardClick(node.summary) },
                            onQuickDispatch = { payload, isSpecialKey ->
                                onQuickDispatch(node.summary, payload, isSpecialKey)
                            },
                            onSelectChild = onCardClick
                        )
                    }
                }

                // Tier 2: Unacknowledged DONE (Just finished a turn, waiting for next instruction)
                if (readyDoneNodes.isNotEmpty()) {
                    item(key = "header_ready_done") {
                        TriageSectionHeader(
                            badgeText = "🟢 刚完成 · 待验收派活",
                            count = readyDoneNodes.size,
                            accentColor = HerdrColors.Done,
                            subtitle = "本轮任务已收工，可立即派发下一轮指令或标记已阅归档"
                        )
                    }
                    items(readyDoneNodes, key = { "done_${it.summary.address}" }) { node ->
                        ReadyDoneTriageCard(
                            node = node,
                            childHelpers = childrenByParent[node.summary.address].orEmpty(),
                            isCurrentlyAttached = node.summary.address == activeTerminalAddress,
                            recentReceipt = dispatchedReceipts[node.summary.address],
                            onOpenWorkbench = { onCardClick(node.summary) },
                            onWriteNextInstruction = { onWriteNextInstruction(node.summary) },
                            onQuickDispatch = { payload, isSpecialKey ->
                                onQuickDispatch(node.summary, payload, isSpecialKey)
                            },
                            onAcknowledgeDone = { onAcknowledgeDone(node.summary) },
                            onSelectChild = onCardClick
                        )
                    }
                }

                // Tier 3: Autonomous WORKING (Compact telemetry rows)
                if (workingNodes.isNotEmpty()) {
                    item(key = "header_working") {
                        TriageSectionHeader(
                            badgeText = "⚡ 正在自主推进",
                            count = workingNodes.size,
                            accentColor = HerdrColors.Working,
                            subtitle = "无需人工干预，点击任意节点可随时巡检或插话"
                        )
                    }
                    items(workingNodes, key = { "working_${it.summary.address}" }) { node ->
                        CompactWorkingTriageRow(
                            node = node,
                            childHelpers = childrenByParent[node.summary.address].orEmpty(),
                            isCurrentlyAttached = node.summary.address == activeTerminalAddress,
                            recentReceipt = dispatchedReceipts[node.summary.address],
                            onOpenWorkbench = { onCardClick(node.summary) },
                            onSelectChild = onCardClick
                        )
                    }
                }

                // Tier 4: Quiet Archive (IDLE shells + Acknowledged DONE, collapsed by default)
                if (quietArchiveNodes.isNotEmpty()) {
                    item(key = "header_quiet_archive") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(HerdrColors.Surface)
                                .border(0.6.dp, HerdrColors.Border, RoundedCornerShape(12.dp))
                                .clickable { expandQuietArchive = !expandQuietArchive }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(HerdrColors.Idle)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "已归档与空闲终端 (${quietArchiveNodes.size})",
                                    color = HerdrColors.Muted,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "含已阅 DONE 与空闲 Shell",
                                    color = HerdrColors.Dim,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = if (expandQuietArchive) "收起 ▴" else "展开 ▾",
                                color = HerdrColors.Accent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (expandQuietArchive) {
                        items(quietArchiveNodes, key = { "quiet_${it.summary.address}" }) { node ->
                            CompactQuietTriageRow(
                                node = node,
                                isCurrentlyAttached = node.summary.address == activeTerminalAddress,
                                onOpenWorkbench = { onCardClick(node.summary) },
                                onWriteNextInstruction = { onWriteNextInstruction(node.summary) }
                            )
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(18.dp))
                }
            }
        }
    }
}

@Composable
private fun TriageSectionHeader(
    badgeText: String,
    count: Int,
    accentColor: Color,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = badgeText,
                color = accentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.5.sp,
                softWrap = false,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(accentColor.copy(alpha = 0.16f))
                    .padding(horizontal = 7.dp, vertical = 1.5.dp)
            ) {
                Text(
                    text = "$count",
                    color = accentColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = subtitle,
                color = HerdrColors.Dim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UrgentTriageCard(
    node: AgentTreeNode,
    childHelpers: List<TerminalSummary>,
    isCurrentlyAttached: Boolean,
    recentReceipt: DispatchedReceipt?,
    onOpenWorkbench: () -> Unit,
    onQuickDispatch: (String, Boolean) -> Unit,
    onSelectChild: (TerminalSummary) -> Unit
) {
    val item = node.summary
    val headline = remember(item) {
        StructuredTerminalReader.extractLatestHeadline(null, item)
    }
    val quickActions = remember(item) {
        StructuredTerminalReader.extractQuickActions(null, item).take(3)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(15.dp))
            .background(HerdrColors.Surface)
            .border(
                width = 1.3.dp,
                color = HerdrColors.Blocked,
                shape = RoundedCornerShape(15.dp)
            )
            .clickable { onOpenWorkbench() }
    ) {
        Box(
            modifier = Modifier
                .width(4.5.dp)
                .fillMaxHeight()
                .background(HerdrColors.Blocked)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                            .background(HerdrColors.BlockedSubtle)
                            .padding(horizontal = 7.dp, vertical = 2.5.dp)
                    ) {
                        Text(
                            text = item.address,
                            color = HerdrColors.Blocked,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.name?.takeIf { it.isNotBlank() } ?: item.workspaceSubtitle,
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = (item.agentApp ?: "agent").uppercase(),
                        color = HerdrColors.Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(HerdrColors.Raised)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        text = if (item.agentStatus == AgentStateWord.ERROR) "ERROR" else "待拍板",
                        color = HerdrColors.Blocked,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(HerdrColors.BlockedSubtle)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(HerdrColors.BlockedSubtle.copy(alpha = 0.65f))
                    .border(0.7.dp, HerdrColors.Blocked.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = headline,
                    color = HerdrColors.Foreground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.5.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.workspaceSubtitle} · Turn ${item.turn}",
                    color = HerdrColors.Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (childHelpers.isNotEmpty()) {
                SwarmChildHelperStrip(
                    childHelpers = childHelpers,
                    onSelectChild = onSelectChild
                )
            }

            if (recentReceipt != null) {
                DispatchedReceiptInlineBanner(receipt = recentReceipt)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                quickActions.forEach { action ->
                    val bg = when {
                        action.isPrimary -> HerdrColors.Done
                        action.isDanger -> HerdrColors.BlockedSubtle
                        else -> HerdrColors.Raised
                    }
                    val fg = when {
                        action.isPrimary -> Color.White
                        action.isDanger -> HerdrColors.Blocked
                        else -> HerdrColors.Foreground
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(bg)
                            .clickable { onQuickDispatch(action.payload, action.isSpecialKey) }
                            .padding(horizontal = 11.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = action.label,
                            color = fg,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(HerdrColors.AccentSubtle)
                        .clickable { onOpenWorkbench() }
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = if (isCurrentlyAttached) "批示台 (当前) →" else "进入批示台 →",
                        color = HerdrColors.Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyDoneTriageCard(
    node: AgentTreeNode,
    childHelpers: List<TerminalSummary>,
    isCurrentlyAttached: Boolean,
    recentReceipt: DispatchedReceipt?,
    onOpenWorkbench: () -> Unit,
    onWriteNextInstruction: () -> Unit,
    onQuickDispatch: (String, Boolean) -> Unit,
    onAcknowledgeDone: () -> Unit,
    onSelectChild: (TerminalSummary) -> Unit
) {
    val item = node.summary
    val headline = remember(item) {
        StructuredTerminalReader.extractLatestHeadline(null, item)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(HerdrColors.Surface)
            .border(
                width = if (isCurrentlyAttached) 1.2.dp else 0.8.dp,
                color = if (isCurrentlyAttached) HerdrColors.Accent else HerdrColors.Done.copy(alpha = 0.65f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onOpenWorkbench() }
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(HerdrColors.Done)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
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
                            .background(HerdrColors.DoneSubtle)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.address,
                            color = HerdrColors.Done,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.name?.takeIf { it.isNotBlank() } ?: item.workspaceSubtitle,
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!item.parent.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "↳ ${item.parent}",
                            color = HerdrColors.Muted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
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
                    Text(
                        text = "${(item.agentApp ?: "agent").uppercase()} · Turn ${item.turn}",
                        color = HerdrColors.Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "✓ 已完成",
                        color = HerdrColors.Done,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(HerdrColors.DoneSubtle)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = headline,
                color = HerdrColors.Foreground,
                fontSize = 12.5.sp,
                lineHeight = 17.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (childHelpers.isNotEmpty()) {
                SwarmChildHelperStrip(
                    childHelpers = childHelpers,
                    onSelectChild = onSelectChild
                )
            }

            if (recentReceipt != null) {
                DispatchedReceiptInlineBanner(receipt = recentReceipt)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(HerdrColors.Accent)
                        .clickable { onWriteNextInstruction() }
                        .padding(horizontal = 11.dp, vertical = 6.5.dp)
                ) {
                    Text(
                        text = "✎ 派下一轮指令",
                        color = HerdrColors.OnAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(HerdrColors.Raised)
                        .clickable { onQuickDispatch("继续", false) }
                        .padding(horizontal = 10.dp, vertical = 6.5.dp)
                ) {
                    Text(
                        text = "继续推进",
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.5.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(HerdrColors.Raised)
                        .clickable { onAcknowledgeDone() }
                        .padding(horizontal = 10.dp, vertical = 6.5.dp)
                ) {
                    Text(
                        text = "✓ 已阅归档",
                        color = HerdrColors.Muted,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactWorkingTriageRow(
    node: AgentTreeNode,
    childHelpers: List<TerminalSummary>,
    isCurrentlyAttached: Boolean,
    recentReceipt: DispatchedReceipt?,
    onOpenWorkbench: () -> Unit,
    onSelectChild: (TerminalSummary) -> Unit
) {
    val item = node.summary
    val headline = remember(item) {
        StructuredTerminalReader.extractLatestHeadline(null, item)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(HerdrColors.Surface)
            .border(
                width = if (isCurrentlyAttached) 1.1.dp else 0.6.dp,
                color = if (isCurrentlyAttached) HerdrColors.Accent else HerdrColors.Border,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onOpenWorkbench() }
    ) {
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .fillMaxHeight()
                .background(HerdrColors.Working)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(HerdrColors.WorkingSubtle)
                        .padding(horizontal = 6.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        text = item.address,
                        color = HerdrColors.Working,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = item.name?.takeIf { it.isNotBlank() } ?: item.workspaceSubtitle,
                    color = HerdrColors.Foreground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${(item.agentApp ?: "shell").uppercase()} · Turn ${item.turn}",
                    color = HerdrColors.Dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    softWrap = false,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "⚡ 运行中",
                    color = HerdrColors.Working,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    softWrap = false,
                    maxLines = 1
                )
            }

            Text(
                text = headline,
                color = HerdrColors.Muted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (childHelpers.isNotEmpty()) {
                SwarmChildHelperStrip(
                    childHelpers = childHelpers,
                    onSelectChild = onSelectChild
                )
            }

            if (recentReceipt != null) {
                DispatchedReceiptInlineBanner(receipt = recentReceipt)
            }
        }
    }
}

@Composable
private fun CompactQuietTriageRow(
    node: AgentTreeNode,
    isCurrentlyAttached: Boolean,
    onOpenWorkbench: () -> Unit,
    onWriteNextInstruction: () -> Unit
) {
    val item = node.summary
    val isDone = item.agentStatus == AgentStateWord.DONE

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(HerdrColors.Surface.copy(alpha = 0.78f))
            .border(
                width = if (isCurrentlyAttached) 1.dp else 0.5.dp,
                color = if (isCurrentlyAttached) HerdrColors.Accent else HerdrColors.Border,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onOpenWorkbench() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.address,
            color = if (isDone) HerdrColors.Done else HerdrColors.Muted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            softWrap = false,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = item.name?.takeIf { it.isNotBlank() } ?: item.workspaceSubtitle,
            color = HerdrColors.Foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (isDone) "已阅 DONE" else "IDLE",
            color = HerdrColors.Dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(HerdrColors.Raised)
                .clickable { onWriteNextInstruction() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "派活 →",
                color = HerdrColors.Accent,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SwarmChildHelperStrip(
    childHelpers: List<TerminalSummary>,
    onSelectChild: (TerminalSummary) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "↳ 协作子助手:",
            color = HerdrColors.Dim,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            softWrap = false,
            maxLines = 1
        )
        childHelpers.forEach { child ->
            val dotColor = when (child.agentStatus) {
                AgentStateWord.BLOCKED, AgentStateWord.ERROR -> HerdrColors.Blocked
                AgentStateWord.DONE -> HerdrColors.Done
                AgentStateWord.WORKING -> HerdrColors.Working
                else -> HerdrColors.Idle
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(HerdrColors.Raised)
                    .border(0.5.dp, dotColor.copy(alpha = 0.5f), RoundedCornerShape(7.dp))
                    .clickable { onSelectChild(child) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
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
                    text = "${child.address.substringAfterLast('/')} ${child.name.orEmpty()}".trim(),
                    color = HerdrColors.Foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    softWrap = false,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DispatchedReceiptInlineBanner(receipt: DispatchedReceipt) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(HerdrColors.AccentSubtle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "✓ 已发送批示「${receipt.text}」· 正在同步远端状态...",
            color = HerdrColors.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LiveTerminalAttachPane(
    session: TerminalSession?,
    summary: TerminalSummary?,
    allLiveTerminals: List<TerminalSummary>,
    acknowledgedDoneTurns: Map<String, Int>,
    draftText: String,
    dispatchedReceipt: DispatchedReceipt?,
    requestInputFocusSignal: Long,
    appSettings: HerdrAppSettings,
    savedDesktopDims: Map<String, Pair<Int, Int>>,
    mobilePtyTargetCols: Int,
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchTerminal: (TerminalSummary) -> Unit,
    onUpdateDraft: (String) -> Unit,
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
                    text = "尚未选择 Agent 终端",
                    color = HerdrColors.Foreground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp
                )
                Text(
                    text = "请返回牧群分诊收件箱选择需要批示或巡检的 Agent。",
                    color = HerdrColors.Muted,
                    fontSize = 12.5.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(HerdrColors.Accent)
                        .clickable { onPickTerminal() }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "← 返回牧群收件箱",
                        color = HerdrColors.OnAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
        return
    }

    val clipboardManager = LocalClipboardManager.current
    val rawScreen by session.screenSnapshot.collectAsState()
    val dims by session.dimensions.collectAsState()

    // Default to Structured Reflow Reader Mode (`阅读批示`); user can toggle to 1:1 Raw PTY (`>_ 原始`) anytime
    var structuredReaderMode by remember { mutableStateOf(true) }
    var fontZoomDeltaSp by remember(session.address) { mutableFloatStateOf(0f) }
    var softWrapLines by remember(session.address) { mutableStateOf(false) }
    var showSpecialKeyStrip by remember(session.address) { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(requestInputFocusSignal) {
        if (requestInputFocusSignal > 0L) {
            delay(120)
            runCatching { inputFocusRequester.requestFocus() }
        }
    }

    val curCols = dims.first
    val curRows = dims.second
    val isMobilePtyLocked = curCols in 1..88
    val desktopOrigDims = savedDesktopDims[summary.address]

    val semanticBlocks = remember(rawScreen) {
        StructuredTerminalReader.parseBlocks(rawScreen)
    }
    val quickActions = remember(rawScreen, summary) {
        StructuredTerminalReader.extractQuickActions(rawScreen, summary)
    }

    val fastSwitchList = remember(allLiveTerminals, acknowledgedDoneTurns, summary.address) {
        allLiveTerminals.sortedWith(
            compareBy<TerminalSummary> { term ->
                when {
                    term.agentStatus == AgentStateWord.BLOCKED || term.agentStatus == AgentStateWord.ERROR -> 0
                    term.address == summary.address -> 1
                    term.agentStatus == AgentStateWord.DONE && acknowledgedDoneTurns[term.address] != term.turn -> 2
                    term.agentStatus == AgentStateWord.WORKING -> 3
                    else -> 4
                }
            }.thenBy { it.address }
        )
    }

    val otherBlockedCount = remember(allLiveTerminals, summary.address) {
        allLiveTerminals.count {
            it.address != summary.address &&
                (it.agentStatus == AgentStateWord.BLOCKED || it.agentStatus == AgentStateWord.ERROR)
        }
    }

    val activeThemePreset = HerdrColors.currentPalette.preset
    val styledScreen = remember(rawScreen, activeThemePreset, structuredReaderMode) {
        if (structuredReaderMode) {
            AnnotatedString("")
        } else {
            AnsiTerminalParser.parseToAnnotatedString(rawScreen, mobileReflow = false)
        }
    }

    val readerScrollState = rememberScrollState()
    val rawVScroll = rememberScrollState()
    val rawHScroll = rememberScrollState()
    var followBottom by remember(session.address) { mutableStateOf(true) }
    var isProgrammaticScroll by remember(session.address) { mutableStateOf(false) }
    var lastProgrammaticTarget by remember(session.address) { mutableIntStateOf(0) }

    val activeVScroll = if (structuredReaderMode) readerScrollState else rawVScroll

    LaunchedEffect(activeVScroll) {
        snapshotFlow { Triple(activeVScroll.value, activeVScroll.maxValue, activeVScroll.isScrollInProgress) }
            .collect { (value, maxValue, inProgress) ->
                if (maxValue <= 0 || isProgrammaticScroll) return@collect
                val distFromBottom = (maxValue - value).coerceAtLeast(0)
                if (inProgress) {
                    followBottom = distFromBottom <= 24
                    if (followBottom) {
                        lastProgrammaticTarget = value
                    }
                } else if (followBottom && value < lastProgrammaticTarget - 24 && distFromBottom > 24) {
                    followBottom = false
                }
            }
    }

    LaunchedEffect(rawScreen, structuredReaderMode, activeVScroll.maxValue, dispatchedReceipt?.timestampMs) {
        val currentMax = activeVScroll.maxValue
        if (currentMax <= 0) return@LaunchedEffect
        if (followBottom && !activeVScroll.isScrollInProgress) {
            isProgrammaticScroll = true
            try {
                activeVScroll.scrollTo(currentMax)
                lastProgrammaticTarget = activeVScroll.value
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    val isScrolledUp = !followBottom && activeVScroll.maxValue > 32 && (activeVScroll.maxValue - activeVScroll.value) > 32

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Workbench Header + Fast-Switch Agent Strip + View Mode Toggle
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HerdrColors.Chrome)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // Row 1: Back to Fleet + Current Agent Title + View Mode Switcher ([阅读批示 | >_ 原始])
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (otherBlockedCount > 0) HerdrColors.BlockedSubtle else HerdrColors.Raised)
                        .clickable { onNavigateBack() }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (otherBlockedCount > 0) "← 牧群 ($otherBlockedCount 待拍板)" else "← 牧群",
                        color = if (otherBlockedCount > 0) HerdrColors.Blocked else HerdrColors.Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        softWrap = false,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            text = summary.name?.takeIf { it.isNotBlank() } ?: summary.workspaceSubtitle,
                            color = HerdrColors.Foreground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Segmented View Mode Toggle: [ 阅读批示 | >_ 原始 ]
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(HerdrColors.Raised)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (structuredReaderMode) HerdrColors.Accent else Color.Transparent)
                            .clickable {
                                structuredReaderMode = true
                                followBottom = true
                            }
                            .padding(horizontal = 9.dp, vertical = 4.5.dp)
                    ) {
                        Text(
                            text = "阅读批示",
                            color = if (structuredReaderMode) HerdrColors.OnAccent else HerdrColors.Muted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (!structuredReaderMode) HerdrColors.Accent else Color.Transparent)
                            .clickable {
                                structuredReaderMode = false
                                followBottom = true
                            }
                            .padding(horizontal = 9.dp, vertical = 4.5.dp)
                    ) {
                        Text(
                            text = ">_ 原始",
                            color = if (!structuredReaderMode) HerdrColors.OnAccent else HerdrColors.Muted,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }

            // Row 2: Horizontal Fast-Switch Agent Strip (1-tap jump between blocked/done/working terminals)
            if (fastSwitchList.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    fastSwitchList.forEach { term ->
                        val isCurrent = term.address == summary.address
                        val isUnackedDone = term.agentStatus == AgentStateWord.DONE &&
                            acknowledgedDoneTurns[term.address] != term.turn
                        val dotColor = when {
                            term.agentStatus == AgentStateWord.BLOCKED || term.agentStatus == AgentStateWord.ERROR -> HerdrColors.Blocked
                            isUnackedDone -> HerdrColors.Done
                            term.agentStatus == AgentStateWord.WORKING -> HerdrColors.Working
                            else -> HerdrColors.Idle
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (isCurrent) HerdrColors.AccentSubtle else HerdrColors.Surface)
                                .border(
                                    width = if (isCurrent) 1.1.dp else 0.6.dp,
                                    color = if (isCurrent) HerdrColors.Accent else dotColor.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(999.dp)
                                )
                                .clickable {
                                    if (!isCurrent) {
                                        onSwitchTerminal(term)
                                    }
                                }
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = buildString {
                                    append(term.address.substringAfterLast('/'))
                                    val label = term.name?.takeIf { it.isNotBlank() }
                                    if (label != null) {
                                        append(" ")
                                        append(label)
                                    }
                                },
                                color = if (isCurrent) HerdrColors.Accent else HerdrColors.Foreground,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 10.5.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Row 3: Low-level PTY / Font / Wrap Controls (ONLY shown when user switches to `>_ 原始` raw PTY mode)
            AnimatedVisibility(visible = !structuredReaderMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "${curCols}×${curRows}",
                        color = HerdrColors.Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                    listOf(56, 68, 80).forEach { presetCols ->
                        val isSelected = curCols == presetCols
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) HerdrColors.AccentSubtle else HerdrColors.Raised)
                                .clickable {
                                    fontZoomDeltaSp = 0f
                                    coroutineScope.launch { rawHScroll.scrollTo(0) }
                                    onTakeoverPtyWidth(presetCols)
                                }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "PTY ${presetCols}列",
                                color = if (isSelected) HerdrColors.Accent else HerdrColors.Muted,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 10.sp
                            )
                        }
                    }
                    if (isMobilePtyLocked) {
                        val origColsLabel = desktopOrigDims?.first ?: appSettings.defaultDesktopFallbackCols
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(HerdrColors.WorkingSubtle)
                                .clickable {
                                    fontZoomDeltaSp = 0f
                                    onReleasePtyWidth()
                                }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "释放(${origColsLabel}列)",
                                color = HerdrColors.Working,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(HerdrColors.Raised)
                            .clickable { fontZoomDeltaSp = (fontZoomDeltaSp - 0.75f).coerceAtLeast(-3.5f) }
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
                            .clickable { fontZoomDeltaSp = (fontZoomDeltaSp + 0.75f).coerceAtMost(5.0f) }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "A+",
                            color = HerdrColors.Foreground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (softWrapLines) HerdrColors.AccentSubtle else HerdrColors.Raised)
                            .clickable { softWrapLines = !softWrapLines }
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(HerdrColors.Border)
        )

        // Main Reading / Inspection Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(HerdrColors.Canvas)
        ) {
            if (structuredReaderMode) {
                // Mode A: Structured Mobile-Native Reflow Reader (`结构化重排阅读器`)
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(readerScrollState)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Agent Context Capsule at top of stream
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(HerdrColors.Surface)
                                .border(0.6.dp, HerdrColors.Border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${(summary.agentApp ?: "shell").uppercase()} · Turn ${summary.turn} · ${summary.workspaceSubtitle}",
                                    color = HerdrColors.Foreground,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!summary.cwd.isNullOrBlank()) {
                                    Text(
                                        text = summary.cwd.orEmpty(),
                                        color = HerdrColors.Dim,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            val statusColor = when (summary.agentStatus) {
                                AgentStateWord.BLOCKED, AgentStateWord.ERROR -> HerdrColors.Blocked
                                AgentStateWord.DONE -> HerdrColors.Done
                                AgentStateWord.WORKING -> HerdrColors.Working
                                else -> HerdrColors.Idle
                            }
                            Text(
                                text = summary.agentStatus.wireName.uppercase(),
                                color = statusColor,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(statusColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
                            )
                        }

                        if (semanticBlocks.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(HerdrColors.Surface)
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = summary.agentMessage?.takeIf { it.isNotBlank() }
                                        ?: "正在同步终端画面，或切换到「>_ 原始」查看原始终端画布...",
                                    color = HerdrColors.Muted,
                                    fontSize = 13.5.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        } else {
                            semanticBlocks.forEachIndexed { idx, block ->
                                when (block) {
                                    is TerminalSemanticBlock.UserPromptBlock -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                                                    .background(HerdrColors.AccentSubtle)
                                                    .border(
                                                        0.8.dp,
                                                        HerdrColors.Accent.copy(alpha = 0.45f),
                                                        RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)
                                                    )
                                                    .padding(horizontal = 13.dp, vertical = 9.dp),
                                                verticalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Text(
                                                    text = "❯ 指令输入",
                                                    color = HerdrColors.Accent,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.5.sp
                                                )
                                                Text(
                                                    text = block.text,
                                                    color = HerdrColors.Foreground,
                                                    fontSize = 14.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    lineHeight = 20.5.sp
                                                )
                                            }
                                        }
                                    }

                                    is TerminalSemanticBlock.ToolGroupBlock -> {
                                        var expanded by remember(session.address, idx) { mutableStateOf(false) }
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(HerdrColors.Surface)
                                                .border(0.6.dp, HerdrColors.Border, RoundedCornerShape(10.dp))
                                                .clickable { expanded = !expanded }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "⚙ ${block.summaryLabel}",
                                                    color = if (block.hasError) HerdrColors.Blocked else HerdrColors.Muted,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = if (expanded) "收起 ▴" else "展开 ▾",
                                                    color = HerdrColors.Accent,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            AnimatedVisibility(visible = expanded) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(HerdrColors.Canvas)
                                                        .padding(10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    block.steps.forEach { line ->
                                                        Text(
                                                            text = line,
                                                            color = HerdrColors.Muted,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 11.5.sp,
                                                            lineHeight = 16.sp
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    is TerminalSemanticBlock.AgentProseBlock -> {
                                        Text(
                                            text = if (block.isBullet) "• ${block.text}" else block.text,
                                            color = HerdrColors.Foreground,
                                            fontFamily = FontFamily.SansSerif,
                                            fontSize = if (block.isHeading) 15.5.sp else 14.8.sp,
                                            fontWeight = if (block.isHeading) FontWeight.Bold else FontWeight.Normal,
                                            lineHeight = 22.sp,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    start = if (block.isBullet) 8.dp else 2.dp,
                                                    end = 2.dp
                                                )
                                        )
                                    }

                                    is TerminalSemanticBlock.CodeOrTableBlock -> {
                                        val codeText = block.content
                                        val lineCount = remember(codeText) { codeText.lines().size }
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(11.dp))
                                                .background(HerdrColors.Surface)
                                                .border(0.7.dp, HerdrColors.Border, RoundedCornerShape(11.dp))
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(HerdrColors.Raised)
                                                    .padding(horizontal = 11.dp, vertical = 5.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "CODE / OUTPUT ($lineCount 行)",
                                                    color = HerdrColors.Muted,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "复制",
                                                    color = HerdrColors.Accent,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.clickable {
                                                        clipboardManager.setText(AnnotatedString(codeText))
                                                    }
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState())
                                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                                            ) {
                                                Text(
                                                    text = codeText,
                                                    color = HerdrColors.Foreground,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 12.sp,
                                                    lineHeight = 17.sp,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }

                                    is TerminalSemanticBlock.UrgentAlertBlock -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(HerdrColors.BlockedSubtle)
                                                .border(1.2.dp, HerdrColors.Blocked, RoundedCornerShape(12.dp))
                                                .padding(horizontal = 13.dp, vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "⚠️ ${block.title}",
                                                color = HerdrColors.Blocked,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            if (block.detail.isNotBlank() && block.detail != block.title) {
                                                Text(
                                                    text = block.detail,
                                                    color = HerdrColors.Foreground,
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    lineHeight = 19.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Deterministic Dispatched Receipt Bubble at bottom of conversation stream
                        if (dispatchedReceipt != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                                        .background(HerdrColors.DoneSubtle)
                                        .border(
                                            0.9.dp,
                                            HerdrColors.Done,
                                            RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)
                                        )
                                        .padding(horizontal = 13.dp, vertical = 9.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "✓ 已下达指令 · 正在同步远端画面",
                                        color = HerdrColors.Done,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = dispatchedReceipt.text,
                                        color = HerdrColors.Foreground,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            } else {
                // Mode B: Native 1:1 ANSI PTY Canvas (`>_ 原始终端`)
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
                                .verticalScroll(rawVScroll)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        } else {
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rawVScroll)
                                .horizontalScroll(rawHScroll, enabled = rawHScroll.maxValue > 4)
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

                if (!softWrapLines && rawHScroll.maxValue > 28 && (rawHScroll.maxValue - rawHScroll.value) > 20) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(HerdrColors.Surface.copy(alpha = 0.88f))
                            .border(0.6.dp, HerdrColors.Border, RoundedCornerShape(999.dp))
                            .clickable {
                                coroutineScope.launch {
                                    rawHScroll.animateScrollTo((rawHScroll.value + 320).coerceAtMost(rawHScroll.maxValue))
                                }
                            }
                            .padding(horizontal = 9.dp, vertical = 3.5.dp)
                    ) {
                        Text(
                            text = "横滑或切回「阅读批示」→",
                            color = HerdrColors.Muted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            softWrap = false,
                            maxLines = 1
                        )
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
                                    activeVScroll.animateScrollTo(activeVScroll.maxValue)
                                    lastProgrammaticTarget = activeVScroll.value
                                } finally {
                                    isProgrammaticScroll = false
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "↓ 最新输出",
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

        // Bottom Always-Ready Dispatch Bar (Contextual Quick Actions + Special Keys + Always-Active Input)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HerdrColors.Chrome)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: Contextual Quick-Reply Pills + Toggle Raw Key Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    quickActions.forEach { action ->
                        val bg = when {
                            action.isPrimary -> HerdrColors.DoneSubtle
                            action.isDanger -> HerdrColors.BlockedSubtle
                            else -> HerdrColors.Raised
                        }
                        val fg = when {
                            action.isPrimary -> HerdrColors.Done
                            action.isDanger -> HerdrColors.Blocked
                            else -> HerdrColors.Foreground
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(bg)
                                .border(
                                    width = 0.6.dp,
                                    color = if (action.isPrimary || action.isDanger) fg.copy(alpha = 0.5f) else HerdrColors.Border,
                                    shape = RoundedCornerShape(999.dp)
                                )
                                .clickable {
                                    followBottom = true
                                    if (action.isSpecialKey) {
                                        if (action.payload == "ctrl+c") {
                                            onInterrupt()
                                        } else {
                                            onSendSpecialKey(action.payload)
                                        }
                                    } else {
                                        onSendLine(action.payload, false)
                                    }
                                }
                                .padding(horizontal = 11.dp, vertical = 5.5.dp)
                        ) {
                            Text(
                                text = action.label,
                                color = fg,
                                fontWeight = if (action.isPrimary || action.isDanger) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 11.5.sp,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (showSpecialKeyStrip) HerdrColors.AccentSubtle else HerdrColors.Raised)
                        .clickable { showSpecialKeyStrip = !showSpecialKeyStrip }
                        .padding(horizontal = 9.dp, vertical = 5.5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (showSpecialKeyStrip) "按键 ▾" else "按键 ▴",
                        color = if (showSpecialKeyStrip) HerdrColors.Accent else HerdrColors.Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        softWrap = false,
                        maxLines = 1
                    )
                }
            }

            // Row 2: Optional Raw Special Key Strip (ESC / TAB / Arrows / CTRL+O / CTRL+C)
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
                                    followBottom = true
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
                                    else -> HerdrColors.Foreground
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

            // Row 3: Always-Ready Input Field (Preserves draft per terminal & auto-promotes to TAKEOVER on send)
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
                            width = if (inputFocused) 1.2.dp else 0.7.dp,
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
                        if (draftText.isEmpty()) {
                            Text(
                                text = "直接向 ${summary.address.substringAfterLast('/')} (${summary.name ?: summary.workspaceSubtitle}) 下达指令...",
                                color = HerdrColors.Dim,
                                fontSize = 12.5.sp,
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = draftText,
                            onValueChange = onUpdateDraft,
                            singleLine = true,
                            textStyle = TextStyle(
                                color = HerdrColors.Foreground,
                                fontSize = 13.5.sp
                            ),
                            cursorBrush = SolidColor(HerdrColors.Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    followBottom = true
                                    if (draftText.isNotBlank()) {
                                        val toSend = draftText
                                        onUpdateDraft("")
                                        onSendLine(toSend, false)
                                    } else {
                                        onSendSpecialKey("enter")
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

                val hasInputText = draftText.isNotBlank()
                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (hasInputText) HerdrColors.Accent else HerdrColors.AccentSubtle)
                        .clickable {
                            followBottom = true
                            if (hasInputText) {
                                val toSend = draftText
                                onUpdateDraft("")
                                onSendLine(toSend, false)
                            } else {
                                onSendSpecialKey("enter")
                            }
                        }
                        .padding(horizontal = 15.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (hasInputText) "发送" else "⏎ 回车",
                        color = if (hasInputText) HerdrColors.OnAccent else HerdrColors.Accent,
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

private fun copySensitiveTextToClipboard(context: Context, label: String, secret: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, secret)
    if (Build.VERSION.SDK_INT >= 24) {
        val extras = PersistableBundle().apply {
            val extraKey = if (Build.VERSION.SDK_INT >= 33) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                "android.content.extra.IS_SENSITIVE"
            }
            putBoolean(extraKey, true)
        }
        clip.description.extras = extras
    }
    cm.setPrimaryClip(clip)
}
