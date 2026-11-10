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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import kotlin.math.roundToInt
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
import dev.herdr.android.ui.theme.HerdrThemeSlot
import dev.herdr.android.ui.theme.HerdrThemeTriad
import dev.herdr.android.ui.theme.basePalette
import dev.herdr.android.ui.theme.colorToHsv
import dev.herdr.android.ui.theme.formatColorHex
import dev.herdr.android.ui.theme.hsvToColor
import dev.herdr.android.ui.theme.palette
import dev.herdr.android.ui.theme.parseHexColorOrNull
import dev.herdr.android.update.AppUpdateManager
import dev.herdr.android.update.AppUpdateState
import dev.herdr.android.update.UpdateStatus
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import dev.herdr.android.ui.components.HerdrButton
import dev.herdr.android.ui.components.HerdrButtonKind
import dev.herdr.android.ui.components.HerdrIconButton
import dev.herdr.android.ui.components.HerdrShapes
import dev.herdr.android.ui.components.HerdrToggle
import dev.herdr.android.ui.components.OneLine
import dev.herdr.android.ui.components.StatusDot
import dev.herdr.android.ui.fleet.FleetInboxPane
import dev.herdr.android.ui.theme.HerdrType
import dev.herdr.android.ui.workbench.AgentWorkbenchPane
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
    val terminalPreviews by viewModel.terminalPreviews.collectAsState()
    val requestInputFocusSignal by viewModel.requestInputFocusSignal.collectAsState()
    val context = LocalContext.current

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
            delay((2200L + (toastBanner?.length ?: 0) * 45L).coerceAtMost(6000L))
            viewModel.clearBanner()
        }
    }

    val effectiveHostLabel = (connectionState as? ConnectionState.Connected)?.profile?.host ?: profile.host
    val connectWithPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && appSettings.keepAliveInBackground) {
            runCatching {
                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        viewModel.connectNow()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HerdrColors.Canvas)
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val showUpdate = selectedTab != WorkspaceTab.TERMINAL && (
                appUpdateState.status == UpdateStatus.AVAILABLE ||
                    appUpdateState.status == UpdateStatus.DOWNLOADING ||
                    appUpdateState.status == UpdateStatus.READY_TO_INSTALL
                )
            AnimatedVisibility(visible = showUpdate) {
                val rel = appUpdateState.latestRelease
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                        .clip(HerdrShapes.Control)
                        .background(HerdrColors.Surface)
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(HerdrColors.Done, 7.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    OneLine(
                        text = when (appUpdateState.status) {
                            UpdateStatus.DOWNLOADING -> "正在下载 ${rel?.tagName.orEmpty()} · ${appUpdateState.downloadProgressPercent}%"
                            UpdateStatus.READY_TO_INSTALL -> "${rel?.tagName.orEmpty()} 已下载，可安装"
                            else -> "新版本 ${rel?.tagName.orEmpty()} 可用"
                        },
                        style = HerdrType.Label,
                        color = HerdrColors.Foreground,
                        modifier = Modifier.weight(1f)
                    )
                    if (appUpdateState.status != UpdateStatus.DOWNLOADING) {
                        HerdrButton(
                            text = if (appUpdateState.status == UpdateStatus.READY_TO_INSTALL) "安装" else "更新",
                            onClick = { viewModel.downloadAndInstallUpdate(context) },
                            kind = HerdrButtonKind.Tonal,
                            compact = true
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    WorkspaceTab.AGENTS -> FleetInboxPane(
                        nodes = agentNodes,
                        allTerminals = allTerminals,
                        totalLiveCount = totalLiveCount,
                        connectionState = connectionState,
                        hostLabel = effectiveHostLabel,
                        activeTerminalAddress = activeSession?.address,
                        currentFilter = statusFilter,
                        acknowledgedDoneTurns = acknowledgedDoneTurns,
                        dispatchedReceipts = dispatchedReceipts,
                        terminalPreviews = terminalPreviews,
                        onSelectFilter = viewModel::setStatusFilter,
                        onRefresh = viewModel::refreshTerminals,
                        onOpenSettings = { viewModel.selectTab(WorkspaceTab.SETTINGS) },
                        onOpenConnection = { viewModel.selectTab(WorkspaceTab.CONNECTION) },
                        onCardClick = { viewModel.attachTerminal(it, AttachMode.TAKEOVER) },
                        onWriteNextInstruction = viewModel::openTerminalAndFocusInput,
                        onQuickDispatch = viewModel::quickDispatchToTerminal,
                        onAcknowledgeDone = viewModel::markTerminalDoneAcknowledged,
                        onConnectNow = connectWithPermission,
                        onEnterDemoMode = {
                            viewModel.applyPreset(true)
                            viewModel.connectNow()
                        }
                    )

                    WorkspaceTab.TERMINAL -> AgentWorkbenchPane(
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
                        onSwitchTerminal = { target -> viewModel.attachTerminal(target, AttachMode.TAKEOVER) },
                        onUpdateDraft = { text ->
                            activeSession?.address?.let { addr -> viewModel.updateDraftForTerminal(addr, text) }
                        },
                        onTakeoverPtyWidth = viewModel::takeoverPtyWidth,
                        onReleasePtyWidth = viewModel::releasePtyWidth,
                        onSendLine = viewModel::sendTerminalLine,
                        onSendSpecialKey = viewModel::sendSpecialKey,
                        onInterrupt = viewModel::interruptActiveTerminal,
                        onPickTerminal = { viewModel.selectTab(WorkspaceTab.AGENTS) }
                    )

                    WorkspaceTab.CONNECTION -> Column(modifier = Modifier.fillMaxSize()) {
                        SubPageTopBar(title = "连接", onBack = { viewModel.selectTab(WorkspaceTab.AGENTS) })
                        ConnectionAndDiagnosticsPane(
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
                            onConnect = connectWithPermission,
                            onRefreshTerminals = viewModel::refreshTerminals,
                            onDisconnect = viewModel::disconnectNow,
                            onJumpToAgents = { viewModel.selectTab(WorkspaceTab.AGENTS) }
                        )
                    }

                    WorkspaceTab.SETTINGS -> Column(modifier = Modifier.fillMaxSize()) {
                        SubPageTopBar(title = "设置", onBack = { viewModel.navigateBack() })
                        DefaultSettingsPane(
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

        // Snackbar-style toast: floats above content, never pushes layout.
        AnimatedVisibility(
            visible = toastBanner != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = if (selectedTab == WorkspaceTab.TERMINAL) 132.dp else 24.dp
                )
        ) {
            val msg = toastBanner.orEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HerdrShapes.Control)
                    .background(HerdrColors.Foreground)
                    .clickable { viewModel.clearBanner() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = msg,
                    style = HerdrType.Label,
                    color = HerdrColors.Canvas,
                    fontWeight = FontWeight.Normal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SubPageTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HerdrIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            onClick = onBack,
            tint = HerdrColors.Foreground
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(title, style = HerdrType.Title.copy(fontSize = 20.sp), color = HerdrColors.Foreground)
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
                .then(if (HerdrColors.IsLight) Modifier.border(1.dp, HerdrColors.Border, RoundedCornerShape(16.dp)) else Modifier)
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
                        text = "工作站",
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "选择网络，建立 SSH 会话",
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
                                text = "回到牧群 · $agentCount",
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
                        text = if (appSettings.keepAliveInBackground) "关闭" else "开启",
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
    currentTriad: HerdrThemeTriad,
    onSelectPreset: (HerdrThemePreset) -> Unit,
    onUpdateTriad: (HerdrThemeTriad) -> Unit
) {
    val activePalette = currentPreset.palette(currentTriad)
    var expandedSlot by remember { mutableStateOf<HerdrThemeSlot?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HerdrColors.Surface)
            .then(if (HerdrColors.IsLight) Modifier.border(1.dp, HerdrColors.Border, RoundedCornerShape(16.dp)) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "界面主题与三原色",
                    color = HerdrColors.Foreground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp
                )
                Text(
                    text = "3 原色衍生整套主题 · 点击下方任意色点展开调色盘",
                    color = HerdrColors.Muted,
                    fontSize = 11.sp
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (currentTriad.hasCustomOverrides) {
                    TacticalActionPill(
                        text = "重置主题",
                        accent = false,
                        onClick = { onUpdateTriad(HerdrThemeTriad()) }
                    )
                } else {
                    Text(
                        text = "${currentPreset.title} · ${currentPreset.subtitle}",
                        color = HerdrColors.Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 1. Preset Theme Cards (each shows its own authentic 3-color triad)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            HerdrThemePreset.entries.forEach { preset ->
                val selected = preset == currentPreset
                val pal = if (selected) activePalette else preset.basePalette()
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
                        .clickable {
                            onSelectPreset(preset)
                        }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(pal.canvas)
                                    .border(0.6.dp, pal.border, CircleShape)
                                    .then(
                                        if (selected) {
                                            Modifier.clickable {
                                                expandedSlot = if (expandedSlot == HerdrThemeSlot.CANVAS) null else HerdrThemeSlot.CANVAS
                                            }
                                        } else Modifier
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(pal.accent)
                                    .border(0.6.dp, pal.border, CircleShape)
                                    .then(
                                        if (selected) {
                                            Modifier.clickable {
                                                expandedSlot = if (expandedSlot == HerdrThemeSlot.ACCENT) null else HerdrThemeSlot.ACCENT
                                            }
                                        } else Modifier
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(pal.done)
                                    .border(0.6.dp, pal.border, CircleShape)
                                    .then(
                                        if (selected) {
                                            Modifier.clickable {
                                                expandedSlot = if (expandedSlot == HerdrThemeSlot.SECONDARY) null else HerdrThemeSlot.SECONDARY
                                            }
                                        } else Modifier
                                    )
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
                            text = if (selected && currentTriad.hasCustomOverrides) "已自定义三原色" else preset.subtitle,
                            color = if (selected && currentTriad.hasCustomOverrides) pal.accent else pal.muted,
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 2. Three Core Color Slots of Current Theme (click any slot to expand color picker)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "当前主题三原色（点击任意槽位展开调色盘）",
                    color = HerdrColors.Muted,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
                if (expandedSlot != null) {
                    Text(
                        text = "点击已选槽位收起",
                        color = HerdrColors.Dim,
                        fontSize = 10.5.sp
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HerdrThemeSlot.entries.forEach { slot ->
                    val slotColor = activePalette.colorForSlot(slot)
                    val isSlotExpanded = expandedSlot == slot
                    val isSlotCustom = parseHexColorOrNull(currentTriad.hexForSlot(slot)) != null
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (isSlotExpanded) HerdrColors.AccentSubtle else HerdrColors.Raised)
                            .border(
                                width = if (isSlotExpanded) 1.4.dp else 0.7.dp,
                                color = if (isSlotExpanded) HerdrColors.Accent else HerdrColors.Border,
                                shape = RoundedCornerShape(11.dp)
                            )
                            .clickable {
                                expandedSlot = if (isSlotExpanded) null else slot
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(slotColor)
                                        .border(0.8.dp, HerdrColors.InputBorder, CircleShape)
                                )
                                Text(
                                    text = slot.title,
                                    color = HerdrColors.Foreground,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatColorHex(slotColor),
                                color = if (isSlotCustom) HerdrColors.Accent else HerdrColors.Muted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                fontWeight = if (isSlotCustom) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = if (isSlotCustom) "自定" else "默认",
                                color = if (isSlotCustom) HerdrColors.Accent else HerdrColors.Dim,
                                fontSize = 9.5.sp
                            )
                        }
                    }
                }
            }
        }

        // 3. Expandable Slot Color Picker (Curated Swatches + HSV Sliders + HEX Input)
        AnimatedVisibility(visible = expandedSlot != null) {
            val slot = expandedSlot ?: HerdrThemeSlot.ACCENT
            val currentSlotColor = activePalette.colorForSlot(slot)
            val defaultSlotColor = currentPreset.basePalette().colorForSlot(slot)
            val isSlotCustomized = parseHexColorOrNull(currentTriad.hexForSlot(slot)) != null

            val initialHsv = remember(slot, currentPreset) {
                colorToHsv(currentSlotColor)
            }
            var hue by remember(slot, currentPreset) { mutableFloatStateOf(initialHsv[0]) }
            var saturation by remember(slot, currentPreset) { mutableFloatStateOf(initialHsv[1]) }
            var brightness by remember(slot, currentPreset) { mutableFloatStateOf(initialHsv[2]) }
            var hexDraft by remember(slot, currentPreset, currentTriad.hexForSlot(slot)) {
                mutableStateOf(formatColorHex(currentSlotColor))
            }

            fun applySlotColor(color: Color, updateHsv: Boolean = true) {
                val hex = formatColorHex(color)
                hexDraft = hex
                if (updateHsv) {
                    val hsv = colorToHsv(color)
                    if (hsv[1] > 0.001f) {
                        hue = hsv[0]
                    }
                    saturation = hsv[1]
                    brightness = hsv[2]
                }
                onUpdateTriad(currentTriad.withSlotHex(slot, hex))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HerdrColors.Canvas.copy(alpha = if (HerdrColors.IsLight) 0.7f else 0.55f))
                    .border(0.8.dp, HerdrColors.Border, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                // Picker Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(currentSlotColor)
                                .border(1.dp, HerdrColors.InputBorder, CircleShape)
                        )
                        Column {
                            Text(
                                text = "槽位 ${slot.index} · ${slot.title}",
                                color = HerdrColors.Foreground,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp
                            )
                            Text(
                                text = slot.roleHint,
                                color = HerdrColors.Muted,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isSlotCustomized) {
                            TacticalActionPill(
                                text = "恢复默认",
                                accent = false,
                                onClick = {
                                    val hsv = colorToHsv(defaultSlotColor)
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    brightness = hsv[2]
                                    hexDraft = formatColorHex(defaultSlotColor)
                                    onUpdateTriad(currentTriad.withSlotHex(slot, ""))
                                }
                            )
                        }
                        TacticalActionPill(
                            text = "收起",
                            accent = false,
                            onClick = { expandedSlot = null }
                        )
                    }
                }

                // Curated Swatches for this Slot
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = "精选推荐色格",
                        color = HerdrColors.Muted,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        slot.recommendedSwatches.forEach { (swatchHex, swatchLabel) ->
                            val swatchColor = parseHexColorOrNull(swatchHex) ?: return@forEach
                            val isSelectedSwatch = formatColorHex(currentSlotColor).equals(swatchHex, ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelectedSwatch) HerdrColors.AccentSubtle else HerdrColors.Surface)
                                    .border(
                                        width = if (isSelectedSwatch) 1.2.dp else 0.6.dp,
                                        color = if (isSelectedSwatch) HerdrColors.Accent else HerdrColors.Border,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { applySlotColor(swatchColor, updateHsv = true) }
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .border(0.5.dp, HerdrColors.InputBorder, CircleShape)
                                )
                                Text(
                                    text = swatchLabel,
                                    color = if (isSelectedSwatch) HerdrColors.Foreground else HerdrColors.Muted,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelectedSwatch) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                // HSV Sliders (Hue / Saturation / Brightness)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 1. Hue Slider (0..360)
                    HsvGradientSliderRow(
                        label = "色相 H",
                        valueText = "${hue.roundToInt()}°",
                        value = hue,
                        valueRange = 0f..360f,
                        gradientColors = listOf(
                            Color(0xFFFF0000),
                            Color(0xFFFFFF00),
                            Color(0xFF00FF00),
                            Color(0xFF00FFFF),
                            Color(0xFF0000FF),
                            Color(0xFFFF00FF),
                            Color(0xFFFF0000)
                        ),
                        thumbColor = hsvToColor(hue, 1f, 1f),
                        onValueChange = { newHue ->
                            hue = newHue
                            // If saturation was 0 (pure gray/white/black), nudge saturation slightly when dragging hue
                            val effectiveSat = if (saturation < 0.04f) 0.65f else saturation
                            val effectiveVal = if (brightness < 0.08f) 0.50f else brightness
                            saturation = effectiveSat
                            brightness = effectiveVal
                            applySlotColor(hsvToColor(newHue, effectiveSat, effectiveVal), updateHsv = false)
                        }
                    )

                    // 2. Saturation Slider (0..1)
                    HsvGradientSliderRow(
                        label = "饱和度 S",
                        valueText = "${(saturation * 100).roundToInt()}%",
                        value = saturation,
                        valueRange = 0f..1f,
                        gradientColors = listOf(
                            hsvToColor(hue, 0f, brightness.coerceAtLeast(0.2f)),
                            hsvToColor(hue, 1f, brightness.coerceAtLeast(0.2f))
                        ),
                        thumbColor = currentSlotColor,
                        onValueChange = { newSat ->
                            saturation = newSat
                            applySlotColor(hsvToColor(hue, newSat, brightness), updateHsv = false)
                        }
                    )

                    // 3. Brightness / Value Slider (0..1)
                    HsvGradientSliderRow(
                        label = "明度 V",
                        valueText = "${(brightness * 100).roundToInt()}%",
                        value = brightness,
                        valueRange = 0f..1f,
                        gradientColors = listOf(
                            Color(0xFF050507),
                            hsvToColor(hue, saturation, 1f)
                        ),
                        thumbColor = currentSlotColor,
                        onValueChange = { newVal ->
                            brightness = newVal
                            applySlotColor(hsvToColor(hue, saturation, newVal), updateHsv = false)
                        }
                    )
                }

                // HEX Input Row
                val draftParsed = parseHexColorOrNull(hexDraft)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HEX 色值",
                        color = HerdrColors.Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(HerdrShapes.Control)
                            .background(HerdrColors.Surface)
                            .border(0.8.dp, HerdrColors.InputBorder, HerdrShapes.Control)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = hexDraft,
                            onValueChange = { raw ->
                                val filtered = raw.uppercase().filterIndexed { idx, c ->
                                    (idx == 0 && c == '#') || c in '0'..'9' || c in 'A'..'F'
                                }.take(7)
                                hexDraft = filtered
                                val norm = filtered.removePrefix("#")
                                if (norm.length == 6) {
                                    parseHexColorOrNull(norm)?.let { parsed ->
                                        applySlotColor(parsed, updateHsv = true)
                                    }
                                }
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = HerdrColors.Foreground,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            cursorBrush = SolidColor(HerdrColors.Accent),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (hexDraft.isEmpty()) {
                                    Text(
                                        text = "#RRGGBB",
                                        color = HerdrColors.Dim,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp
                                    )
                                }
                                inner()
                            }
                        )
                    }
                    if (draftParsed != null) {
                        TacticalActionPill(
                            text = "应用",
                            accent = true,
                            onClick = { applySlotColor(draftParsed, updateHsv = true) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HsvGradientSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    gradientColors: List<Color>,
    thumbColor: Color,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            color = HerdrColors.Muted,
            fontSize = 10.5.sp,
            modifier = Modifier.width(48.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(26.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.horizontalGradient(gradientColors))
                    .border(0.6.dp, HerdrColors.Border, RoundedCornerShape(5.dp))
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = thumbColor,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                )
            )
        }
        Text(
            text = valueText,
            color = HerdrColors.Foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            modifier = Modifier.width(36.dp)
        )
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
            currentTriad = appSettings.themeTriad,
            onSelectPreset = { preset ->
                onUpdateAppSettings { it.copy(themePreset = preset, themeTriad = HerdrThemeTriad()) }
            },
            onUpdateTriad = { triad ->
                onUpdateAppSettings { it.copy(themeTriad = triad) }
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
                HerdrToggle(
                    checked = appSettings.autoHideTerminalTopBar,
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
                HerdrToggle(
                    checked = appSettings.autoHideObserveKeyStrip,
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
                    HerdrToggle(
                        checked = appSettings.keepAliveInBackground,
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
            .clip(HerdrShapes.Card)
            .background(HerdrColors.Surface)
            .then(if (HerdrColors.IsLight) Modifier.border(1.dp, HerdrColors.Border, HerdrShapes.Card) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                OneLine(title, HerdrType.Heading, HerdrColors.Foreground)
                Spacer(modifier = Modifier.height(2.dp))
                OneLine(subtitle, HerdrType.Caption, HerdrColors.Muted)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = HerdrColors.Dim,
                modifier = Modifier.size(20.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
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
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) HerdrColors.Surface else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            style = HerdrType.Label,
            color = if (selected) HerdrColors.Foreground else HerdrColors.Muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            softWrap = false,
            maxLines = 1
        )
        Text(
            text = subtitle,
            style = HerdrType.Caption,
            color = if (selected) HerdrColors.Muted else HerdrColors.Dim,
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
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = HerdrType.Caption,
                color = if (focused) HerdrColors.Accent else HerdrColors.Muted,
                fontWeight = FontWeight.Medium,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(HerdrShapes.Control)
                .background(HerdrColors.Raised)
                .then(
                    if (focused) Modifier.border(1.dp, HerdrColors.Accent, HerdrShapes.Control) else Modifier
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = HerdrType.Label,
                    color = HerdrColors.Dim,
                    fontWeight = FontWeight.Normal,
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
                textStyle = HerdrType.Mono.copy(color = HerdrColors.Foreground, fontSize = 13.5.sp, lineHeight = 19.sp),
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
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (accent) HerdrColors.AccentSubtle else HerdrColors.Raised)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = HerdrType.Label,
            color = if (accent) HerdrColors.Accent else HerdrColors.Foreground,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
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
                text = if (showStages) "收起" else "展开",
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
