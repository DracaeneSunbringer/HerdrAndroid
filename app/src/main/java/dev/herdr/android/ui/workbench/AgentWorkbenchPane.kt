package dev.herdr.android.ui.workbench

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.TerminalSession
import dev.herdr.android.protocol.TerminalSummary
import dev.herdr.android.ui.DispatchedReceipt
import dev.herdr.android.ui.HerdrAppSettings
import dev.herdr.android.ui.QueuedDispatchMode
import dev.herdr.android.ui.QueuedPromptItem
import dev.herdr.android.ui.StagedImageAttachment
import dev.herdr.android.ui.components.HerdrButton
import dev.herdr.android.ui.components.HerdrButtonKind
import dev.herdr.android.ui.components.HerdrDivider
import dev.herdr.android.ui.components.HerdrIconButton
import dev.herdr.android.ui.components.HerdrSegmented
import dev.herdr.android.ui.components.HerdrShapes
import dev.herdr.android.ui.components.OneLine
import dev.herdr.android.ui.components.StatusDot
import dev.herdr.android.ui.components.agentAppLabel
import dev.herdr.android.ui.components.statusToneOf
import dev.herdr.android.ui.terminal.AnsiTerminalParser
import dev.herdr.android.ui.terminal.BtwRichContentParser
import dev.herdr.android.ui.terminal.ConversationTurn
import dev.herdr.android.ui.terminal.LiveWorkingActivity
import dev.herdr.android.ui.terminal.QuickReplyAction
import dev.herdr.android.ui.terminal.StructuredTerminalReader
import dev.herdr.android.ui.terminal.AgyAgentMode
import dev.herdr.android.ui.terminal.AgyBtwCardState
import dev.herdr.android.ui.terminal.AgyBtwTurn
import dev.herdr.android.ui.terminal.AgySlashCatalog
import dev.herdr.android.ui.terminal.AgySlashModalKind
import dev.herdr.android.ui.terminal.AskNavDirection
import dev.herdr.android.ui.terminal.TerminalDialect
import dev.herdr.android.ui.terminal.TerminalSemanticBlock
import dev.herdr.android.ui.terminal.ToolActionCategory
import dev.herdr.android.ui.terminal.WorkbenchSnapshot
import dev.herdr.android.ui.theme.HerdrColors
import dev.herdr.android.ui.theme.HerdrType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val hcWrapperRegex = Regex("^<hc\\b[^>]*?(?:from=\"([^\"]*)\")?[^>]*>([\\s\\S]*?)(?:</hc>)?\\s*$")
private val whitespaceRegex = Regex("\\s+")
private val orderedBulletPrefixRegex = Regex("^(\\d{1,2}[.)、]|[（(]\\d{1,2}[)）])\\s*")
private val attachmentTagRegex = Regex("\\[📎[^\\]]*]")

private val PhotoAttachIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PhotoAttach",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(5f, 3f)
            horizontalLineTo(19f)
            curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
            verticalLineTo(19f)
            curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
            horizontalLineTo(5f)
            curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
            verticalLineTo(5f)
            curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
            close()
            moveTo(5f, 5f)
            verticalLineTo(19f)
            horizontalLineTo(19f)
            verticalLineTo(5f)
            horizontalLineTo(5f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(6.5f, 17.5f)
            lineTo(10f, 13f)
            lineTo(12.5f, 16f)
            lineTo(15f, 12.5f)
            lineTo(17.5f, 17.5f)
            close()
            moveTo(9f, 8f)
            arcTo(1.5f, 1.5f, 0f, false, true, 9f, 11f)
            arcTo(1.5f, 1.5f, 0f, false, true, 9f, 8f)
            close()
        }
    }.build()
}

private fun LazyListState.distanceFromBottomPx(): Int {
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return 0
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return 0
    if (lastVisible.index < total - 1) return 1_000
    val viewportEnd = info.viewportEndOffset - info.afterContentPadding
    return (lastVisible.offset + lastVisible.size - viewportEnd).coerceAtLeast(0)
}

/** Splits `<hc from="t1q">body</hc>` into ("t1q", "body"); plain prompts return (null, text). */
private fun splitHcPrompt(text: String): Pair<String?, String> {
    val m = hcWrapperRegex.find(text.trim()) ?: return null to text
    val from = m.groupValues[1].takeIf { it.isNotBlank() }
    return from to m.groupValues[2].trim()
}

private fun workbenchTitleOf(item: TerminalSummary): String {
    val ws = item.workspaceSubtitle.takeIf { it.isNotBlank() && it != "~" }
    return ws ?: item.name?.takeIf { it.isNotBlank() } ?: item.address
}

@Composable
fun AgentWorkbenchPane(
    session: TerminalSession?,
    summary: TerminalSummary?,
    allLiveTerminals: List<TerminalSummary>,
    acknowledgedDoneTurns: Map<String, Int>,
    draftText: String,
    dispatchedReceipt: DispatchedReceipt?,
    stagedAttachments: List<StagedImageAttachment>,
    queuedPrompts: List<QueuedPromptItem> = emptyList(),
    requestInputFocusSignal: Long,
    sequenceStatus: SequenceExecutionStatus?,
    appSettings: HerdrAppSettings,
    savedDesktopDims: Map<String, Pair<Int, Int>>,
    mobilePtyTargetCols: Int,
    onNavigateBack: () -> Unit,
    onSwitchTerminal: (TerminalSummary) -> Unit,
    onUpdateDraft: (String) -> Unit,
    onUploadImages: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSendSubmission: (String, Boolean, Boolean) -> Unit,
    onEnqueuePrompt: (String, Boolean, QueuedDispatchMode) -> Unit = { _, _, _ -> },
    onSendQueuedPromptNow: (String) -> Unit = {},
    onEditQueuedPrompt: (String) -> Unit = {},
    onRemoveQueuedPrompt: (String) -> Unit = {},
    onMoveQueuedPromptToTop: (String) -> Unit = {},
    onSyncLiveAgentStatus: (AgentStateWord, Int) -> Unit = { _, _ -> },
    onTakeoverPtyWidth: (Int) -> Unit,
    onReleasePtyWidth: () -> Unit,
    onSendLine: (String, Boolean) -> Unit,
    onSendSpecialKey: (String) -> Unit,
    onInterrupt: () -> Unit,
    onCancelSequence: () -> Unit,
    onSubmitAgyAskAnswer: (questionIndex: Int, selectedNumbers: Set<Int>, writeInText: String?) -> Unit,
    onNavigateAgyAsk: (AskNavDirection, Int) -> Unit,
    onCycleAgyModeTo: (AgyAgentMode) -> Unit,
    onRunAgySlashCommand: (commandText: String, expectModal: AgySlashModalKind?) -> Unit,
    onSelectAgyModalItem: (AgySlashModalKind, Int, Boolean, String?) -> Unit,
    onStepAgyModalValue: (AgySlashModalKind, Int, Boolean, String?) -> Unit,
    onSwitchAgyModalTab: (AgySlashModalKind, Boolean) -> Unit,
    onFilterAgyModal: (AgySlashModalKind, String) -> Unit,
    onSendAgyModalKey: (AgySlashModalKind, String, Boolean) -> Unit,
    onCloseAgyModal: () -> Unit,
    onSendAgyBtwQuestion: (String) -> Unit,
    onDismissAgyBtw: () -> Unit,
    onToggleAgyBtwExpand: () -> Unit,
    btwHistory: List<AgyBtwTurn> = emptyList(),
    isViewingBtwHistory: Boolean = false,
    onSyncLiveBtwCard: (AgyBtwCardState?) -> Unit = {},
    onOpenBtwHistory: () -> Unit = {},
    cachedReaderBlocks: List<TerminalSemanticBlock> = emptyList(),
    onUpdateCachedReaderBlocks: (List<TerminalSemanticBlock>) -> Unit = {},
    onPickTerminal: () -> Unit
) {
    if (session == null || summary == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("还没有选择 Agent", style = HerdrType.Heading, color = HerdrColors.Foreground)
            Spacer(Modifier.height(6.dp))
            Text("回到牧群，选一个需要处理的 Agent。", style = HerdrType.Label, color = HerdrColors.Muted, fontWeight = FontWeight.Normal)
            Spacer(Modifier.height(18.dp))
            HerdrButton("返回牧群", onPickTerminal, kind = HerdrButtonKind.Tonal)
        }
        return
    }

    val clipboard = LocalClipboardManager.current
    val rawScreen by session.screenSnapshot.collectAsState()
    val dims by session.dimensions.collectAsState()

    var readerMode by remember { mutableStateOf(true) }
    var fontZoomDeltaSp by remember(session.address) { mutableFloatStateOf(0f) }
    var softWrapLines by remember(session.address) { mutableStateOf(false) }
    var showKeys by remember(session.address) { mutableStateOf(false) }
    var showSlashCenter by remember(session.address) { mutableStateOf(false) }
    var btwFollowUpFocused by remember(session.address) { mutableStateOf(false) }
    val isSequenceBusy = sequenceStatus != null && sequenceStatus.address == session.address
    val inputFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

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

    var localHistoryBlocks by remember(session.address) {
        mutableStateOf(cachedReaderBlocks)
    }
    var snapshot by remember(session.address) {
        mutableStateOf(
            WorkbenchSnapshot(
                blocks = emptyList(),
                quickActions = emptyList(),
                liveStatus = summary.agentStatus,
                isExpandedMode = false
            )
        )
    }
    var hasInitialSnapshot by remember(session.address) { mutableStateOf(false) }
    LaunchedEffect(session.address, rawScreen, summary) {
        val computed = withContext(Dispatchers.Default) {
            StructuredTerminalReader.analyzeWorkbench(rawScreen, summary, localHistoryBlocks)
        }
        val nextCache = if (
            computed.blocks.any { it is TerminalSemanticBlock.SessionHeaderBlock } &&
            computed.blocks.none { it is TerminalSemanticBlock.UserPromptBlock }
        ) {
            emptyList()
        } else {
            computed.blocks.filterNot { it is TerminalSemanticBlock.SessionHeaderBlock }
        }
        localHistoryBlocks = nextCache
        onUpdateCachedReaderBlocks(computed.blocks)
        snapshot = computed
        hasInitialSnapshot = true
    }

    val isAgyStreamingCard = snapshot.agyState?.activeBtwCard?.isRunning == true ||
        snapshot.agyState?.activeSlashModal?.isLoading == true
    LaunchedEffect(session.address, isAgyStreamingCard) {
        if (isAgyStreamingCard) {
            while (true) {
                delay(450)
                session.refreshScreenNow(screenOnly = true)
            }
        }
    }

    LaunchedEffect(session.address, snapshot.agyState?.activeBtwCard) {
        onSyncLiveBtwCard(snapshot.agyState?.activeBtwCard)
    }

    val blocks = snapshot.blocks
    val quickActions = snapshot.quickActions
    val isAgentTui = remember(snapshot.dialect, summary.agentApp) {
        snapshot.dialect in setOf(
            TerminalDialect.ANTIGRAVITY,
            TerminalDialect.CODEX,
            TerminalDialect.CLAUDE,
            TerminalDialect.PI
        ) || summary.agentApp?.lowercase()?.trim() in setOf(
            "antigravity", "agy", "codex", "claude", "claude-code", "pi", "gemini"
        )
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onUploadImages(uris)
        }
    }

    val shouldShowPendingReceipt = remember(dispatchedReceipt, blocks, summary.turn) {
        val receipt = dispatchedReceipt ?: return@remember false
        if (summary.turn > receipt.sentAtTurn) return@remember false
        val tailRunningGroup = (blocks.lastOrNull() as? TerminalSemanticBlock.ToolGroupBlock)
            ?.summaryLabel?.endsWith("· 进行中") == true
        val normReceipt = receipt.text.replace(attachmentTagRegex, "").trim().replace(whitespaceRegex, "")
        if (normReceipt.isEmpty()) {
            val echoedMedia = blocks.takeLast(8).any { b ->
                b is TerminalSemanticBlock.UserPromptBlock && "[📎" in b.text
            }
            return@remember !echoedMedia && !tailRunningGroup
        }
        val alreadyEchoedInBlocks = blocks.takeLast(8).any { b ->
            if (b is TerminalSemanticBlock.UserPromptBlock) {
                val body = splitHcPrompt(b.text).second.trim().replace(whitespaceRegex, "")
                body == normReceipt ||
                    (normReceipt.length >= 4 && (body.contains(normReceipt) || normReceipt.contains(body)))
            } else {
                false
            }
        }
        !alreadyEchoedInBlocks && !tailRunningGroup
    }

    val effectiveStatus = if (shouldShowPendingReceipt) {
        AgentStateWord.WORKING
    } else if (hasInitialSnapshot) {
        snapshot.liveStatus
    } else {
        summary.agentStatus
    }

    LaunchedEffect(session.address, effectiveStatus, summary.turn) {
        onSyncLiveAgentStatus(effectiveStatus, summary.turn)
    }

    var workingElapsedSec by remember(session.address) { mutableIntStateOf(0) }
    LaunchedEffect(session.address, effectiveStatus == AgentStateWord.WORKING) {
        if (effectiveStatus == AgentStateWord.WORKING) {
            workingElapsedSec = 0
            while (true) {
                delay(1000)
                workingElapsedSec++
            }
        } else {
            workingElapsedSec = 0
        }
    }

    val switchList = remember(allLiveTerminals, acknowledgedDoneTurns, summary.address) {
        allLiveTerminals.sortedWith(
            compareBy<TerminalSummary> { t ->
                when {
                    t.agentStatus == AgentStateWord.BLOCKED || t.agentStatus == AgentStateWord.ERROR -> 0
                    t.agentStatus == AgentStateWord.DONE && acknowledgedDoneTurns[t.address] != t.turn -> 1
                    t.agentStatus == AgentStateWord.WORKING -> 2
                    else -> 3
                }
            }.thenBy { it.address }
        )
    }
    val othersWaiting = remember(allLiveTerminals, summary.address) {
        allLiveTerminals.count {
            it.address != summary.address &&
                (it.agentStatus == AgentStateWord.BLOCKED || it.agentStatus == AgentStateWord.ERROR)
        }
    }

    val activePreset = HerdrColors.currentPalette.preset
    var styledScreen by remember(session.address) { mutableStateOf(AnnotatedString("")) }
    LaunchedEffect(session.address, rawScreen, activePreset, readerMode) {
        if (!readerMode) {
            styledScreen = withContext(Dispatchers.Default) {
                AnsiTerminalParser.parseToAnnotatedString(rawScreen, mobileReflow = false)
            }
        }
    }

    val readerListState = rememberLazyListState()
    val rawVScroll = rememberScrollState()
    val rawHScroll = rememberScrollState()
    var followBottom by remember(session.address) { mutableStateOf(true) }
    var isProgrammaticScroll by remember(session.address) { mutableStateOf(false) }
    var lastProgrammaticTarget by remember(session.address) { mutableIntStateOf(0) }
    var collapsedTurnIndices by remember(session.address) { mutableStateOf(emptySet<Int>()) }

    val sessionHeader = blocks.firstOrNull { it is TerminalSemanticBlock.SessionHeaderBlock }
        as? TerminalSemanticBlock.SessionHeaderBlock
    val turns = snapshot.turns
    val hasCwdHeader = summary.cwd.isNotBlank()
    val hasPendingReceiptItem = shouldShowPendingReceipt && dispatchedReceipt != null
    val liveWorkingActivity = if (effectiveStatus == AgentStateWord.WORKING) {
        snapshot.liveWorkingActivity
    } else {
        null
    }

    val (turnStartItemIndices, readerAnchorIndex) = remember(
        hasCwdHeader,
        sessionHeader,
        turns,
        blocks.size,
        collapsedTurnIndices,
        liveWorkingActivity,
        hasPendingReceiptItem,
        queuedPrompts.size
    ) {
        val startMap = LinkedHashMap<Int, Int>(turns.size)
        var count = 0
        if (hasCwdHeader) count++
        if (sessionHeader != null) count++
        if (turns.isEmpty()) {
            count++ // empty hint
        } else {
            val showTurnHeaders = turns.size >= 2
            for (turn in turns) {
                startMap[turn.turnIndex] = count
                val isCollapsed = turn.turnIndex in collapsedTurnIndices &&
                    !turn.hasInteractiveCard &&
                    !turn.isRunning
                if (isCollapsed) {
                    count += 1
                } else {
                    if (showTurnHeaders) count += 1
                    if (turn.userPrompt != null) count += 1
                    count += turn.responseBlocks.size
                }
            }
        }
        if (liveWorkingActivity != null) count++
        if (hasPendingReceiptItem) count++
        count += queuedPrompts.size
        startMap to count
    }

    LaunchedEffect(readerListState) {
        snapshotFlow { Pair(readerListState.distanceFromBottomPx(), readerListState.isScrollInProgress) }
            .collect { (distPx, inProgress) ->
                if (isProgrammaticScroll) return@collect
                if (inProgress) {
                    followBottom = distPx <= 28
                }
            }
    }

    LaunchedEffect(rawVScroll) {
        snapshotFlow { Triple(rawVScroll.value, rawVScroll.maxValue, rawVScroll.isScrollInProgress) }
            .collect { (value, maxValue, inProgress) ->
                if (maxValue <= 0 || isProgrammaticScroll) return@collect
                val distFromBottom = (maxValue - value).coerceAtLeast(0)
                if (inProgress) {
                    followBottom = distFromBottom <= 24
                    if (followBottom) lastProgrammaticTarget = value
                } else if (followBottom && value < lastProgrammaticTarget - 24 && distFromBottom > 24) {
                    followBottom = false
                }
            }
    }

    LaunchedEffect(snapshot, hasInitialSnapshot, readerMode, readerAnchorIndex, dispatchedReceipt?.timestampMs) {
        if (!readerMode || !hasInitialSnapshot || readerAnchorIndex < 0) return@LaunchedEffect
        if (followBottom && !readerListState.isScrollInProgress) {
            isProgrammaticScroll = true
            try {
                readerListState.scrollToItem(readerAnchorIndex)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    LaunchedEffect(styledScreen, readerMode, rawVScroll.maxValue, dispatchedReceipt?.timestampMs) {
        if (readerMode) return@LaunchedEffect
        val max = rawVScroll.maxValue
        if (max <= 0) return@LaunchedEffect
        if (followBottom && !rawVScroll.isScrollInProgress) {
            isProgrammaticScroll = true
            try {
                rawVScroll.scrollTo(max)
                lastProgrammaticTarget = rawVScroll.value
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    val isScrolledUp by remember(readerMode) {
        derivedStateOf {
            if (followBottom) {
                false
            } else if (readerMode) {
                readerListState.distanceFromBottomPx() > 32
            } else {
                rawVScroll.maxValue > 32 && (rawVScroll.maxValue - rawVScroll.value) > 32
            }
        }
    }
    val canRawScrollHorizontally by remember {
        derivedStateOf { rawHScroll.maxValue > 4 }
    }
    val showRightHint by remember {
        derivedStateOf { rawHScroll.maxValue > 28 && (rawHScroll.maxValue - rawHScroll.value) > 20 }
    }

    fun dispatch(action: QuickReplyAction) {
        followBottom = true
        if (action.isSpecialKey) {
            if (action.payload == "ctrl+c") onInterrupt() else onSendSpecialKey(action.payload)
        } else {
            onSendLine(action.payload, false)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(HerdrColors.Canvas)) {
    val isCompactViewport = maxHeight < 580.dp
    Column(modifier = Modifier.fillMaxSize()) {
        // ---------------- Header ----------------
        Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    HerdrIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回牧群", onNavigateBack, tint = HerdrColors.Foreground)
                    if (othersWaiting > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 2.dp)
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(HerdrColors.Blocked),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$othersWaiting", fontSize = 10.sp, color = HerdrColors.Canvas, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    OneLine(workbenchTitleOf(summary), HerdrType.Heading, HerdrColors.Foreground)
                    val isAckedDone = effectiveStatus == AgentStateWord.DONE &&
                        acknowledgedDoneTurns[summary.address] == summary.turn
                    val tone = statusToneOf(effectiveStatus, isAckedDone)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(tone.color, 6.dp, tone.pulsing)
                        Spacer(Modifier.width(6.dp))
                        val meta = buildList {
                            add(tone.label)
                            add(summary.address.substringAfterLast('/'))
                            summary.name?.takeIf { it.isNotBlank() && it != workbenchTitleOf(summary) }?.let { add(it) }
                            add(agentAppLabel(summary.agentApp))
                            if (summary.turn > 0) add("${summary.turn} 轮")
                        }.joinToString(" · ")
                        OneLine(meta, HerdrType.Caption, HerdrColors.Muted)
                    }
                }
                Spacer(Modifier.width(8.dp))
                HerdrSegmented(
                    options = listOf("阅读", "终端"),
                    selectedIndex = if (readerMode) 0 else 1,
                    onSelect = {
                        readerMode = it == 0
                        followBottom = true
                    }
                )
            }

            if (switchList.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    switchList.forEach { t ->
                        val current = t.address == summary.address
                        val tone = statusToneOf(t.agentStatus, acknowledgedDoneTurns[t.address] == t.turn)
                        Row(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(HerdrShapes.Pill)
                                .background(if (current) HerdrColors.Raised else Color.Transparent)
                                .clickable(enabled = !current) { onSwitchTerminal(t) }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusDot(tone.color, 6.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                t.address.substringAfterLast('/'),
                                style = HerdrType.Caption,
                                color = if (current) HerdrColors.Foreground else HerdrColors.Muted,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }

            AnimatedVisibility(visible = !readerMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${curCols}×${curRows}", style = HerdrType.Caption, color = HerdrColors.Dim, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(2.dp))
                    listOf(56, 68, 80).forEach { cols ->
                        RawToolChip("${cols} 列", selected = curCols == cols) {
                            fontZoomDeltaSp = 0f
                            scope.launch { rawHScroll.scrollTo(0) }
                            onTakeoverPtyWidth(cols)
                        }
                    }
                    if (isMobilePtyLocked) {
                        val orig = desktopOrigDims?.first ?: appSettings.defaultDesktopFallbackCols
                        RawToolChip("还原 $orig 列", selected = false, tint = HerdrColors.Working) {
                            fontZoomDeltaSp = 0f
                            onReleasePtyWidth()
                        }
                    }
                    RawToolChip("A−", selected = false) { fontZoomDeltaSp = (fontZoomDeltaSp - 0.75f).coerceAtLeast(-3.5f) }
                    RawToolChip("A+", selected = false) { fontZoomDeltaSp = (fontZoomDeltaSp + 0.75f).coerceAtMost(5f) }
                    RawToolChip("折行", selected = softWrapLines) { softWrapLines = !softWrapLines }
                }
            }
            HerdrDivider()
        }

        // ---------------- Content ----------------
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (readerMode) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (turns.size >= 2) {
                        TurnQuickJumpBar(
                            turns = turns,
                            collapsedTurnIndices = collapsedTurnIndices,
                            onJumpToTurn = { turnIdx ->
                                collapsedTurnIndices = collapsedTurnIndices - turnIdx
                                val targetItem = turnStartItemIndices[turnIdx] ?: 0
                                followBottom = turnIdx == turns.last().turnIndex
                                scope.launch {
                                    isProgrammaticScroll = true
                                    try {
                                        readerListState.animateScrollToItem(targetItem)
                                    } finally {
                                        isProgrammaticScroll = false
                                    }
                                }
                            },
                            onToggleCollapseOlder = {
                                val older = turns.dropLast(1)
                                    .filter { !it.hasInteractiveCard && !it.isRunning }
                                    .map { it.turnIndex }
                                    .toSet()
                                collapsedTurnIndices = if (older.isNotEmpty() && older.all { it in collapsedTurnIndices }) {
                                    emptySet()
                                } else {
                                    older
                                }
                            }
                        )
                        HerdrDivider()
                    }
                    LazyColumn(
                        state = readerListState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (hasCwdHeader) {
                            item(key = "cwd", contentType = "cwd") {
                                OneLine(summary.cwd, HerdrType.Caption.copy(fontFamily = FontFamily.Monospace), HerdrColors.Dim)
                            }
                        }
                        if (sessionHeader != null) {
                            item(key = "session_header", contentType = "session_header") {
                                ReaderBlock(
                                    block = sessionHeader,
                                    stateKey = "${session.address}#header",
                                    isSequenceBusy = isSequenceBusy,
                                    onSubmitAgyAskAnswer = onSubmitAgyAskAnswer,
                                    onNavigateAgyAsk = onNavigateAgyAsk,
                                    onCopy = { clipboard.setText(AnnotatedString(it)) }
                                )
                            }
                        }
                        if (turns.isEmpty()) {
                            item(key = "empty", contentType = "empty") {
                                val emptyHint = summary.agentMessage?.takeIf { it.isNotBlank() }
                                    ?: if (hasInitialSnapshot && rawScreen.isNotBlank()) {
                                        if (isAgentTui) "终端已就绪，在下方输入首条指令" else "终端空闲"
                                    } else {
                                        "正在同步画面…"
                                    }
                                Text(
                                    emptyHint,
                                    style = HerdrType.Body,
                                    color = HerdrColors.Muted
                                )
                            }
                        } else {
                            val showTurnHeaders = turns.size >= 2
                            turns.forEach { turn ->
                                val isCollapsed = turn.turnIndex in collapsedTurnIndices &&
                                    !turn.hasInteractiveCard &&
                                    !turn.isRunning
                                if (isCollapsed) {
                                    item(
                                        key = "${session.address}#turn_summary#${turn.turnIndex}",
                                        contentType = "turn_summary"
                                    ) {
                                        TurnSummaryCard(
                                            turn = turn,
                                            onExpand = {
                                                collapsedTurnIndices = collapsedTurnIndices - turn.turnIndex
                                            }
                                        )
                                    }
                                } else {
                                    if (showTurnHeaders) {
                                        item(
                                            key = "${session.address}#turn_hdr#${turn.turnIndex}",
                                            contentType = "turn_header"
                                        ) {
                                            TurnDividerHeader(
                                                turn = turn,
                                                canCollapse = !turn.hasInteractiveCard && !turn.isRunning,
                                                onCollapse = {
                                                    collapsedTurnIndices = collapsedTurnIndices + turn.turnIndex
                                                }
                                            )
                                        }
                                    }
                                    if (turn.userPrompt != null) {
                                        item(
                                            key = "${session.address}#turn#${turn.turnIndex}#prompt",
                                            contentType = TerminalSemanticBlock.UserPromptBlock::class
                                        ) {
                                            ReaderBlock(
                                                block = turn.userPrompt,
                                                stateKey = "${session.address}#t${turn.turnIndex}#prompt",
                                                isSequenceBusy = isSequenceBusy,
                                                onSubmitAgyAskAnswer = onSubmitAgyAskAnswer,
                                                onNavigateAgyAsk = onNavigateAgyAsk,
                                                onCopy = { clipboard.setText(AnnotatedString(it)) }
                                            )
                                        }
                                    }
                                    itemsIndexed(
                                        items = turn.responseBlocks,
                                        key = { bIdx, _ -> "${session.address}#t${turn.turnIndex}#$bIdx" },
                                        contentType = { _, block -> block::class }
                                    ) { bIdx, block ->
                                        val showTurnChangedFilesFooter =
                                            bIdx == turn.responseBlocks.lastIndex &&
                                                turn.changedFiles.isNotEmpty() &&
                                                !turn.isRunning &&
                                                block !is TerminalSemanticBlock.ToolGroupBlock &&
                                                block !is TerminalSemanticBlock.AskCardBlock
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ReaderBlock(
                                                block = block,
                                                stateKey = "${session.address}#t${turn.turnIndex}#$bIdx",
                                                isSequenceBusy = isSequenceBusy,
                                                onSubmitAgyAskAnswer = onSubmitAgyAskAnswer,
                                                onNavigateAgyAsk = onNavigateAgyAsk,
                                                onCopy = { clipboard.setText(AnnotatedString(it)) }
                                            )
                                            if (showTurnChangedFilesFooter) {
                                                TurnChangedFilesFooter(
                                                    files = turn.changedFiles,
                                                    onCopy = { clipboard.setText(AnnotatedString(it)) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (liveWorkingActivity != null) {
                            item(key = "live_working", contentType = "live_working") {
                                val expandPayload = if (snapshot.dialect == TerminalDialect.CODEX) "ctrl+t" else "ctrl+o"
                                LiveWorkingActivityCard(
                                    activity = liveWorkingActivity,
                                    elapsedSec = workingElapsedSec,
                                    expandLabel = if (snapshot.dialect == TerminalDialect.CODEX) "展开 ^T" else "详情 ^O",
                                    onExpand = { onSendSpecialKey(expandPayload) }
                                )
                            }
                        }
                        if (hasPendingReceiptItem) {
                            item(key = "pending_receipt", contentType = "receipt") {
                                dispatchedReceipt?.let { SentBubble(it.text) }
                            }
                        }
                        itemsIndexed(
                            items = queuedPrompts,
                            key = { _, item -> "queued_bubble#${item.id}" },
                            contentType = { _, _ -> "queued_bubble" }
                        ) { idx, item ->
                            QueuedPromptBubble(index = idx + 1, item = item)
                        }
                        item(key = "bottom_anchor", contentType = "anchor") {
                            Spacer(Modifier.height(1.dp))
                        }
                    }
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val innerWidthDp = (maxWidth - 24.dp).value.coerceAtLeast(240f)
                    val baseFont = if (curCols in 1..92) (innerWidthDp / (curCols * 0.675f)).coerceIn(7.2f, 15f) else 10.5f
                    val fontSp = (baseFont + fontZoomDeltaSp).coerceIn(6.8f, 18f)
                    val scrollMod = if (softWrapLines) {
                        Modifier.fillMaxSize().verticalScroll(rawVScroll).padding(horizontal = 12.dp, vertical = 10.dp)
                    } else {
                        Modifier.fillMaxSize().verticalScroll(rawVScroll)
                            .horizontalScroll(rawHScroll, enabled = canRawScrollHorizontally)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    }
                    Box(modifier = scrollMod) {
                        SelectionContainer {
                            Text(
                                text = styledScreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSp.sp,
                                lineHeight = (fontSp * 1.32f).sp,
                                softWrap = softWrapLines
                            )
                        }
                    }
                }
                if (!softWrapLines && showRightHint) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 12.dp)
                            .clip(HerdrShapes.Pill)
                            .background(HerdrColors.Raised)
                            .clickable {
                                scope.launch { rawHScroll.animateScrollTo((rawHScroll.value + 320).coerceAtMost(rawHScroll.maxValue)) }
                            }
                            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("右侧还有内容", style = HerdrType.Caption, color = HerdrColors.Muted)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = HerdrColors.Muted, modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (isScrolledUp) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(HerdrShapes.Pill)
                        .background(HerdrColors.Hover)
                        .clickable {
                            followBottom = true
                            scope.launch {
                                isProgrammaticScroll = true
                                try {
                                    if (readerMode) {
                                        val firstVisible = readerListState.firstVisibleItemIndex
                                        if (readerAnchorIndex - firstVisible > 18) {
                                            readerListState.scrollToItem(readerAnchorIndex)
                                        } else {
                                            readerListState.animateScrollToItem(readerAnchorIndex)
                                        }
                                    } else {
                                        rawVScroll.animateScrollTo(rawVScroll.maxValue)
                                        lastProgrammaticTarget = rawVScroll.value
                                    }
                                } finally {
                                    isProgrammaticScroll = false
                                }
                            }
                        }
                        .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = HerdrColors.Foreground, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("最新", style = HerdrType.Caption, color = HerdrColors.Foreground, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ---------------- Agy Sequence Strip & Modal Host + Composer ----------------
        val activeAgyState = snapshot.agyState
        val isAgyDialect = snapshot.dialect == TerminalDialect.ANTIGRAVITY ||
            summary?.agentApp.equals("antigravity", ignoreCase = true) ||
            summary?.agentApp.equals("agy", ignoreCase = true) ||
            activeAgyState != null
        val activeModal = activeAgyState?.activeSlashModal
        val activeBtw = activeAgyState?.activeBtwCard
        val showBtwCard = activeBtw != null || (isViewingBtwHistory && btwHistory.isNotEmpty())
        if (isSequenceBusy || showBtwCard || activeModal != null || queuedPrompts.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (queuedPrompts.isNotEmpty()) {
                    QueuedPromptsTray(
                        items = queuedPrompts,
                        liveStatus = effectiveStatus,
                        onSendNow = onSendQueuedPromptNow,
                        onEdit = onEditQueuedPrompt,
                        onMoveToTop = onMoveQueuedPromptToTop,
                        onRemove = onRemoveQueuedPrompt
                    )
                }
                if (sequenceStatus != null && sequenceStatus.address == session.address) {
                    SequenceStatusStrip(
                        status = sequenceStatus,
                        onCancel = onCancelSequence
                    )
                }
                if (showBtwCard) {
                    AgyBtwCardComposable(
                        liveCard = activeBtw,
                        historyTurns = btwHistory,
                        isHistoryPreviewOnly = activeBtw == null && isViewingBtwHistory,
                        isBusy = isSequenceBusy,
                        compactViewport = isCompactViewport,
                        onFollowUpFocusChanged = { btwFollowUpFocused = it },
                        onCopyText = { text ->
                            clipboard.setText(AnnotatedString(text))
                        },
                        onToggleExpand = {
                            followBottom = true
                            onToggleAgyBtwExpand()
                        },
                        onFollowUp = { q ->
                            followBottom = true
                            onSendAgyBtwQuestion(q)
                        },
                        onDismiss = onDismissAgyBtw
                    )
                }
                if (activeModal != null) {
                    AgySlashModalHostCard(
                        modal = activeModal,
                        isBusy = isSequenceBusy,
                        onSelectItem = { index, confirmEnter, title ->
                            onSelectAgyModalItem(activeModal.kind, index, confirmEnter, title)
                        },
                        onStepValue = { index, moveRight, label ->
                            onStepAgyModalValue(activeModal.kind, index, moveRight, label)
                        },
                        onSwitchTab = { forward ->
                            onSwitchAgyModalTab(activeModal.kind, forward)
                        },
                        onFilter = { query ->
                            onFilterAgyModal(activeModal.kind, query)
                        },
                        onSendRawKey = { rawKey ->
                            onSendAgyModalKey(activeModal.kind, rawKey, true)
                        },
                        onClose = onCloseAgyModal
                    )
                }
            }
        }
        HerdrDivider()
        val collapseAuxComposerRows = isCompactViewport && (showBtwCard || activeModal != null)
        val compactComposerPadding = isCompactViewport && (showBtwCard || btwFollowUpFocused)
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                start = 12.dp,
                end = 12.dp,
                top = if (compactComposerPadding) 4.dp else 10.dp,
                bottom = if (compactComposerPadding) 4.dp else 10.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (compactComposerPadding) 4.dp else 8.dp)
        ) {
            if (!collapseAuxComposerRows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    quickActions.forEach { action ->
                        QuickChip(action) { dispatch(action) }
                    }
                }
                if (isAgyDialect) {
                    Spacer(Modifier.width(6.dp))
                    val modeTag = activeAgyState?.agentMode?.label
                    Row(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(HerdrShapes.Pill)
                            .background(if (showSlashCenter) HerdrColors.AccentSubtle else HerdrColors.Raised)
                            .clickable { showSlashCenter = true }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (modeTag != null) "/ · $modeTag" else "/ 命令",
                            style = HerdrType.Caption,
                            color = if (showSlashCenter) HerdrColors.Accent else HerdrColors.Foreground,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(HerdrShapes.Pill)
                        .background(if (showKeys) HerdrColors.Raised else Color.Transparent)
                        .clickable { showKeys = !showKeys }
                        .padding(start = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("按键", style = HerdrType.Caption, color = HerdrColors.Muted, fontWeight = FontWeight.Medium)
                    Icon(
                        if (showKeys) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        null,
                        tint = HerdrColors.Dim,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            AnimatedVisibility(visible = showKeys) {
                val row1 = listOf("left" to "←", "up" to "↑", "down" to "↓", "right" to "→", "space" to "␣", "enter" to "⏎")
                val row2 = listOf("esc" to "Esc", "tab" to "Tab", "shift+tab" to "⇧Tab", "ctrl+o" to "^O", "ctrl+t" to "^T", "ctrl+c" to "^C")
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row1.forEach { (wire, label) ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(HerdrColors.Raised)
                                    .clickable {
                                        followBottom = true
                                        onSendSpecialKey(wire)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    style = HerdrType.Body,
                                    fontFamily = FontFamily.Monospace,
                                    color = HerdrColors.Foreground,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row2.forEach { (wire, label) ->
                            val danger = wire == "ctrl+c"
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (danger) HerdrColors.BlockedSubtle else HerdrColors.Raised)
                                    .clickable {
                                        followBottom = true
                                        if (danger) onInterrupt() else onSendSpecialKey(wire)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    style = HerdrType.Caption,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (danger) HerdrColors.Blocked else HerdrColors.Foreground,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
            }

            WorkbenchComposer(
                sessionAddress = session.address,
                targetLabel = summary.address.substringAfterLast('/'),
                initialDraft = draftText,
                stagedAttachments = stagedAttachments,
                focusRequester = inputFocusRequester,
                isAgyDialect = isAgyDialect,
                isSequenceBusy = isSequenceBusy,
                isAgentWorking = (effectiveStatus == AgentStateWord.WORKING && isAgentTui),
                hasQueuedPrompts = queuedPrompts.isNotEmpty(),
                onRunAgySlashCommand = { cmdText, expectModal ->
                    followBottom = true
                    onRunAgySlashCommand(cmdText, expectModal)
                },
                onSendAgyBtwQuestion = { q ->
                    followBottom = true
                    onSendAgyBtwQuestion(q)
                },
                onUpdateDraft = onUpdateDraft,
                onPickImages = {
                    imagePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemoveAttachment = onRemoveAttachment,
                onSubmitLine = { text ->
                    followBottom = true
                    onSendLine(text, false)
                },
                onSubmitWithAttachments = { text, submitWithEnter ->
                    followBottom = true
                    onSendSubmission(text, isAgentTui, submitWithEnter)
                },
                onEnqueuePrompt = { text, mode ->
                    followBottom = true
                    onEnqueuePrompt(text, isAgentTui, mode)
                },
                onSubmitEmpty = {
                    followBottom = true
                    onSendSpecialKey("enter")
                }
            )
        }
    }
    AgySlashCommandCenterSheet(
        visible = showSlashCenter,
        agyState = snapshot.agyState,
        isBusy = isSequenceBusy,
        btwHistoryCount = btwHistory.size,
        onOpenBtwHistory = onOpenBtwHistory,
        onDismiss = { showSlashCenter = false },
        onCycleModeTo = { targetMode ->
            showSlashCenter = false
            onCycleAgyModeTo(targetMode)
        },
        onRunCommand = { cmdText, expectModal ->
            showSlashCenter = false
            followBottom = true
            onRunAgySlashCommand(cmdText, expectModal)
        }
    )
    }
}

@Composable
private fun WorkbenchComposer(
    sessionAddress: String,
    targetLabel: String,
    initialDraft: String,
    stagedAttachments: List<StagedImageAttachment>,
    focusRequester: FocusRequester,
    isAgyDialect: Boolean,
    isSequenceBusy: Boolean,
    isAgentWorking: Boolean = false,
    hasQueuedPrompts: Boolean = false,
    onRunAgySlashCommand: (String, AgySlashModalKind?) -> Unit,
    onSendAgyBtwQuestion: (String) -> Unit,
    onUpdateDraft: (String) -> Unit,
    onPickImages: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSubmitLine: (String) -> Unit,
    onSubmitWithAttachments: (String, Boolean) -> Unit,
    onEnqueuePrompt: (String, QueuedDispatchMode) -> Unit,
    onSubmitEmpty: () -> Unit
) {
    var localDraft by remember(sessionAddress) { mutableStateOf(initialDraft) }
    val latestDraft by rememberUpdatedState(localDraft)
    val latestOnUpdateDraft by rememberUpdatedState(onUpdateDraft)

    LaunchedEffect(sessionAddress, initialDraft) {
        if (initialDraft.isNotEmpty() && localDraft.isEmpty()) {
            localDraft = initialDraft
        }
    }

    LaunchedEffect(sessionAddress, localDraft) {
        delay(250)
        latestOnUpdateDraft(localDraft)
    }

    DisposableEffect(sessionAddress) {
        onDispose {
            latestOnUpdateDraft(latestDraft)
        }
    }

    val anyUploading = stagedAttachments.any { it.uploading }
    val hasReadyAttachments = stagedAttachments.any { !it.remotePath.isNullOrBlank() }

    fun matchedSlashSpec(rawText: String): AgySlashCatalog.SlashCommandSpec? {
        val trimmed = rawText.trim()
        if (!isAgyDialect || !trimmed.startsWith("/") || '\n' in trimmed) return null
        val cmdToken = trimmed.substringBefore(' ')
        return AgySlashCatalog.allCommands.firstOrNull { it.cmd.equals(cmdToken, ignoreCase = true) }
    }

    val isQueueableInput = (localDraft.isNotBlank() || hasReadyAttachments) &&
        !anyUploading &&
        matchedSlashSpec(localDraft) == null

    fun submitImmediate() {
        if (anyUploading) {
            onSubmitWithAttachments(localDraft, true)
            return
        }
        val currentText = localDraft
        if (hasReadyAttachments) {
            localDraft = ""
            onUpdateDraft("")
            onSubmitWithAttachments(currentText, true)
        } else if (currentText.isNotBlank()) {
            val trimmed = currentText.trim()
            val matchedSpec = matchedSlashSpec(currentText)
            if (matchedSpec != null && matchedSpec.cmd.equals("/btw", ignoreCase = true)) {
                val btwQuestion = trimmed.drop(4).trim()
                if (btwQuestion.isEmpty()) {
                    localDraft = "/btw "
                    onUpdateDraft("/btw ")
                    onSendAgyBtwQuestion("")
                } else {
                    localDraft = ""
                    onUpdateDraft("")
                    onSendAgyBtwQuestion(btwQuestion)
                }
                return
            }
            localDraft = ""
            onUpdateDraft("")
            if (matchedSpec != null && !isSequenceBusy) {
                val expectModal = if (' ' !in trimmed) matchedSpec.modalKind else null
                onRunAgySlashCommand(trimmed, expectModal)
            } else {
                onSubmitLine(currentText)
            }
        } else {
            onSubmitEmpty()
        }
    }

    fun submitEnqueue(mode: QueuedDispatchMode = QueuedDispatchMode.FOLLOW_UP) {
        val textToQueue = localDraft
        localDraft = ""
        onUpdateDraft("")
        onEnqueuePrompt(textToQueue, mode)
    }

    fun submit() {
        if (isAgentWorking && isQueueableInput) {
            submitEnqueue(QueuedDispatchMode.FOLLOW_UP)
        } else {
            submitImmediate()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isAgyDialect && localDraft.startsWith("/") && '\n' !in localDraft) {
            val firstWord = localDraft.trimStart().substringBefore(' ')
            val exactSpec = remember(firstWord) {
                AgySlashCatalog.allCommands.firstOrNull { it.cmd.equals(firstWord, ignoreCase = true) }
            }
            val matchedCommands = remember(firstWord) {
                AgySlashCatalog.allCommands
                    .filter { it.cmd.startsWith(firstWord, ignoreCase = true) }
                    .take(8)
            }
            if (matchedCommands.isNotEmpty() || exactSpec != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (exactSpec != null && exactSpec.cmd.equals("/btw", ignoreCase = true)) {
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(HerdrShapes.Pill)
                                .background(HerdrColors.AccentSubtle)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "/btw <问题> · 补全问题后点击发送",
                                style = HerdrType.Caption,
                                color = HerdrColors.Accent
                            )
                        }
                    } else if (exactSpec != null && exactSpec.cmd.equals("/model", ignoreCase = true)) {
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(HerdrShapes.Pill)
                                .background(HerdrColors.AccentSubtle)
                                .clickable(enabled = !isSequenceBusy) {
                                    localDraft = ""
                                    onUpdateDraft("")
                                    onRunAgySlashCommand(exactSpec.cmd, exactSpec.modalKind)
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "/model 打开模型面板",
                                style = HerdrType.Caption,
                                color = HerdrColors.Accent
                            )
                        }
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(HerdrShapes.Pill)
                                .background(HerdrColors.Raised)
                                .clickable(enabled = !isSequenceBusy) {
                                    if (!localDraft.startsWith("/model ")) {
                                        localDraft = "/model "
                                    }
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "/model 指定别名…",
                                style = HerdrType.Caption,
                                color = HerdrColors.Foreground
                            )
                        }
                    } else if (exactSpec != null && exactSpec.quickParams.isNotEmpty()) {
                        if (exactSpec.modalKind != null) {
                            Row(
                                modifier = Modifier
                                    .height(28.dp)
                                    .clip(HerdrShapes.Pill)
                                    .background(HerdrColors.AccentSubtle)
                                    .clickable(enabled = !isSequenceBusy) {
                                        localDraft = ""
                                        onUpdateDraft("")
                                        onRunAgySlashCommand(exactSpec.cmd, exactSpec.modalKind)
                                    }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${exactSpec.cmd} 面板",
                                    style = HerdrType.Caption,
                                    color = HerdrColors.Accent
                                )
                            }
                        }
                        exactSpec.quickParams.forEach { param ->
                            Row(
                                modifier = Modifier
                                    .height(28.dp)
                                    .clip(HerdrShapes.Pill)
                                    .background(HerdrColors.Raised)
                                    .clickable(enabled = !isSequenceBusy) {
                                        localDraft = ""
                                        onUpdateDraft("")
                                        onRunAgySlashCommand("${exactSpec.cmd} $param", null)
                                    }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${exactSpec.cmd} $param",
                                    style = HerdrType.Caption,
                                    color = HerdrColors.Foreground
                                )
                            }
                        }
                    } else {
                        matchedCommands.forEach { spec ->
                            val directModal = spec.modalKind != null &&
                                spec.quickParams.isEmpty() &&
                                spec.cmd != "/btw" &&
                                (!spec.acceptsFreeTextArg || spec.cmd == "/model") &&
                                !spec.requiresConfirm
                            Row(
                                modifier = Modifier
                                    .height(28.dp)
                                    .clip(HerdrShapes.Pill)
                                    .background(HerdrColors.Raised)
                                    .clickable(enabled = !isSequenceBusy) {
                                        if (directModal) {
                                            localDraft = ""
                                            onUpdateDraft("")
                                            onRunAgySlashCommand(spec.cmd, spec.modalKind)
                                        } else {
                                            localDraft = if (spec.requiresConfirm) spec.cmd else "${spec.cmd} "
                                        }
                                    }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = spec.cmd,
                                    style = HerdrType.Caption,
                                    color = HerdrColors.Accent
                                )
                                Text(
                                    text = spec.title,
                                    style = HerdrType.Caption,
                                    color = HerdrColors.Muted
                                )
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = stagedAttachments.isNotEmpty()) {
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
                    stagedAttachments.forEach { att ->
                        val bg = if (att.error != null) HerdrColors.BlockedSubtle else HerdrColors.Raised
                        val fg = if (att.error != null) HerdrColors.Blocked else HerdrColors.Foreground
                        Row(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(HerdrShapes.Pill)
                                .background(bg)
                                .padding(start = 10.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (att.uploading) {
                                StatusDot(HerdrColors.Working, 6.dp, pulsing = true)
                            }
                            val label = when {
                                att.uploading -> "上传中 ${att.displayName}"
                                att.error != null -> "失败 ${att.displayName}"
                                else -> "📎 ${att.displayName} · ${(att.sizeBytes / 1024).coerceAtLeast(1)}K"
                            }
                            Text(
                                text = label,
                                style = HerdrType.Caption,
                                color = fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .clickable { onRemoveAttachment(att.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, "移除附件", tint = HerdrColors.Muted, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
                if (hasReadyAttachments && !anyUploading) {
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(HerdrShapes.Pill)
                            .background(HerdrColors.AccentSubtle)
                            .clickable {
                                val textToFill = localDraft
                                if (textToFill.isNotEmpty()) {
                                    localDraft = ""
                                    onUpdateDraft("")
                                }
                                onSubmitWithAttachments(textToFill, false)
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "仅填入",
                            style = HerdrType.Caption,
                            color = HerdrColors.Accent,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = isQueueableInput && (isAgentWorking || hasQueuedPrompts)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isAgentWorking) {
                    Text(
                        "Agent 运行中 · 发送将加入续发队列",
                        style = HerdrType.Caption,
                        color = HerdrColors.Working,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(HerdrShapes.Pill)
                            .background(HerdrColors.AccentSubtle)
                            .clickable { submitImmediate() }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "⚡ 立即插话发送",
                            style = HerdrType.Caption,
                            color = HerdrColors.Accent,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                } else if (hasQueuedPrompts) {
                    Box(
                        modifier = Modifier
                            .clip(HerdrShapes.Pill)
                            .background(HerdrColors.WorkingSubtle)
                            .clickable { submitEnqueue(QueuedDispatchMode.FOLLOW_UP) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "⏳ 加入待发队列",
                            style = HerdrType.Caption,
                            color = HerdrColors.Working,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(HerdrColors.Raised)
                    .clickable(onClick = onPickImages),
                contentAlignment = Alignment.Center
            ) {
                Icon(PhotoAttachIcon, "上传图片", tint = HerdrColors.Muted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(HerdrColors.Raised)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (localDraft.isEmpty()) {
                    val placeholder = when {
                        hasReadyAttachments -> "补充图片说明，或直接点发送"
                        isAgentWorking -> "预设下一条指令（本轮结束后自动发送）"
                        else -> "给 $targetLabel 下达指令"
                    }
                    OneLine(placeholder, HerdrType.Body.copy(lineHeight = 20.sp), HerdrColors.Dim)
                }
                BasicTextField(
                    value = localDraft,
                    onValueChange = { localDraft = it },
                    singleLine = true,
                    textStyle = HerdrType.Body.copy(color = HerdrColors.Foreground, lineHeight = 20.sp),
                    cursorBrush = SolidColor(HerdrColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
            }
            Spacer(Modifier.width(8.dp))
            val canSend = localDraft.isNotBlank() || hasReadyAttachments
            val queueSendStyle = canSend && isAgentWorking && isQueueableInput
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            queueSendStyle -> HerdrColors.Working
                            canSend -> HerdrColors.Accent
                            else -> HerdrColors.Raised
                        }
                    )
                    .clickable { submit() },
                contentAlignment = Alignment.Center
            ) {
                if (queueSendStyle) {
                    Text("⏳", fontSize = 17.sp, color = HerdrColors.OnAccent)
                } else if (canSend) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = HerdrColors.OnAccent, modifier = Modifier.size(20.dp))
                } else {
                    Text("⏎", color = HerdrColors.Muted, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun RawToolChip(label: String, selected: Boolean, tint: Color? = null, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(HerdrShapes.Pill)
            .background(if (selected) HerdrColors.AccentSubtle else HerdrColors.Raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = HerdrType.Caption,
            color = tint ?: if (selected) HerdrColors.Accent else HerdrColors.Foreground,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun QuickChip(action: QuickReplyAction, onClick: () -> Unit) {
    val (bg, fg) = when {
        action.isPrimary -> HerdrColors.AccentSubtle to HerdrColors.Accent
        action.isDanger -> HerdrColors.BlockedSubtle to HerdrColors.Blocked
        else -> HerdrColors.Raised to HerdrColors.Foreground
    }
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(HerdrShapes.Pill)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            action.label,
            style = HerdrType.Label,
            color = fg,
            fontWeight = if (action.isPrimary) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun SentBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Spacer(Modifier.width(48.dp))
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .background(HerdrColors.Raised)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, null, tint = HerdrColors.Done, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("已发送 · 等待 Agent 响应", style = HerdrType.Caption, color = HerdrColors.Done)
            }
            Spacer(Modifier.height(4.dp))
            Text(text, style = HerdrType.Body, color = HerdrColors.Foreground)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderBlock(
    block: TerminalSemanticBlock,
    stateKey: String,
    isSequenceBusy: Boolean,
    onSubmitAgyAskAnswer: (questionIndex: Int, selectedNumbers: Set<Int>, writeInText: String?) -> Unit,
    onNavigateAgyAsk: (AskNavDirection, Int) -> Unit,
    onCopy: (String) -> Unit
) {
    when (block) {
        is TerminalSemanticBlock.SessionHeaderBlock -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HerdrShapes.Block)
                    .background(HerdrColors.Surface)
                    .border(1.dp, HerdrColors.Border, HerdrShapes.Block)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(HerdrColors.Done, 7.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = block.agentTitle,
                        style = HerdrType.Label,
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(HerdrShapes.Pill)
                            .background(HerdrColors.DoneSubtle)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "已就绪",
                            style = HerdrType.Caption,
                            color = HerdrColors.Done,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
                if (!block.modelOrRole.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(HerdrShapes.Small)
                            .background(HerdrColors.Raised)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = block.modelOrRole,
                            style = HerdrType.Caption,
                            color = HerdrColors.Foreground,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!block.accountOrPlan.isNullOrBlank()) {
                    OneLine(block.accountOrPlan, HerdrType.Caption, HerdrColors.Muted)
                }
                block.details.forEach { detail ->
                    OneLine(detail, HerdrType.Caption, HerdrColors.Muted)
                }
                val hint = if (block.agentTitle.contains("Antigravity", ignoreCase = true)) {
                    "在下方输入首条指令，或点「/」调用斜杠命令"
                } else {
                    "在下方输入首条指令即可开始对话"
                }
                Text(hint, style = HerdrType.Caption, color = HerdrColors.Dim)
            }
        }
        is TerminalSemanticBlock.AskCardBlock -> {
            AgyAskCardComposable(
                card = block.card,
                isBusy = isSequenceBusy,
                onSubmitAnswer = onSubmitAgyAskAnswer,
                onNavigate = onNavigateAgyAsk
            )
        }
        is TerminalSemanticBlock.UserPromptBlock -> {
            val (from, body) = remember(block.text) { splitHcPrompt(block.text) }
            val lineCount = remember(body) { body.count { it == '\n' } + 1 }
            val isLongPrompt = remember(body, lineCount) { body.length > 600 || lineCount > 10 }
            var promptExpanded by remember(stateKey, body) { mutableStateOf(false) }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                Spacer(Modifier.width(48.dp))
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                        .background(HerdrColors.AccentSubtle)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { if (isLongPrompt) promptExpanded = !promptExpanded },
                            onLongClick = { onCopy(body) }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (from != null) {
                        Text("来自 $from", style = HerdrType.Caption, color = HerdrColors.Accent, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        text = body,
                        style = HerdrType.Body,
                        color = HerdrColors.Foreground,
                        maxLines = if (!isLongPrompt || promptExpanded) Int.MAX_VALUE else 8,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isLongPrompt) {
                        Text(
                            text = if (promptExpanded) "收起长文本 ▴" else "展开全文 ($lineCount 行) ▾",
                            style = HerdrType.Caption,
                            color = if (promptExpanded) HerdrColors.Dim else HerdrColors.Accent,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        is TerminalSemanticBlock.AgentProseBlock -> {
            val copyMod = Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { onCopy(block.text) }
            )
            when {
                block.text.startsWith("▎ ") -> {
                    val quoteBody = block.text.removePrefix("▎ ")
                    val barColor = HerdrColors.Accent
                    Box(
                        modifier = copyMod
                            .fillMaxWidth()
                            .clip(HerdrShapes.Small)
                            .background(HerdrColors.Surface)
                            .drawBehind {
                                drawRect(color = barColor, size = Size(3.dp.toPx(), size.height))
                            }
                            .padding(start = 13.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                    ) {
                        Text(formatBtwInline(quoteBody), style = HerdrType.Body, color = HerdrColors.Foreground)
                    }
                }
                block.isHeading -> Text(
                    formatBtwInline(block.text),
                    style = HerdrType.Heading,
                    color = HerdrColors.Foreground,
                    modifier = copyMod.padding(top = 6.dp)
                )
                block.isBullet -> {
                    val (marker, body) = remember(block.text) {
                        val ordered = orderedBulletPrefixRegex.find(block.text)
                        val mk = ordered?.value?.trim() ?: "•"
                        val bd = if (ordered != null) block.text.substring(ordered.value.length) else block.text
                        mk to bd
                    }
                    Row(modifier = copyMod.fillMaxWidth().padding(start = (block.indentLevel * 16).dp)) {
                        Box(modifier = Modifier.widthIn(min = 18.dp).padding(end = 6.dp)) {
                            Text(marker, style = HerdrType.Body, color = HerdrColors.Dim)
                        }
                        Text(formatBtwInline(body), style = HerdrType.Body, color = HerdrColors.Foreground, modifier = Modifier.weight(1f))
                    }
                }
                else -> Text(formatBtwInline(block.text), style = HerdrType.Body, color = HerdrColors.Foreground, modifier = copyMod)
            }
        }

        is TerminalSemanticBlock.ToolGroupBlock -> {
            if (block.isPureThought) {
                ThoughtPillCard(block = block, stateKey = stateKey, onCopy = onCopy)
            } else {
                ToolGroupCard(block = block, stateKey = stateKey, onCopy = onCopy)
            }
        }

        is TerminalSemanticBlock.CodeOrTableBlock -> {
            val parsedTable = remember(block.content, block.kindLabel) {
                if (block.kindLabel == "表格") BtwRichContentParser.parseTableOrNull(block.content) else null
            }
            var showRawTable by remember(stateKey, block.content) { mutableStateOf(false) }
            if (parsedTable != null && !showRawTable) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BtwGridTableBlock(table = parsedTable, onCopyText = onCopy)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            "切换原文",
                            style = HerdrType.Caption,
                            color = HerdrColors.Dim,
                            modifier = Modifier
                                .clip(HerdrShapes.Small)
                                .clickable { showRawTable = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            } else {
                val lines = remember(block.content) { block.content.lines() }
                var copiedCode by remember(stateKey, block.content) { mutableStateOf(false) }
                val isDiffBlock = block.kindLabel == "Diff" ||
                    lines.any { l -> l.startsWith("@@ ") || l.startsWith("diff --git ") }
                val addBg = HerdrColors.DoneSubtle.copy(alpha = 0.7f)
                val delBg = HerdrColors.BlockedSubtle.copy(alpha = 0.7f)
                val addFg = HerdrColors.Done
                val delFg = HerdrColors.Blocked
                val metaFg = HerdrColors.Accent
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(HerdrShapes.Block)
                        .background(HerdrColors.Surface)
                        .border(1.dp, HerdrColors.Border.copy(alpha = 0.5f), HerdrShapes.Block)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(HerdrColors.Raised.copy(alpha = 0.6f))
                            .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val headerLabel = if (!block.kindLabel.isNullOrBlank()) "${block.kindLabel} · ${lines.size} 行" else "${lines.size} 行"
                        Text(headerLabel, style = HerdrType.Caption, color = HerdrColors.Muted, modifier = Modifier.weight(1f))
                        if (parsedTable != null) {
                            Text(
                                "表格视图",
                                style = HerdrType.Caption,
                                color = HerdrColors.Accent,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(HerdrShapes.Small)
                                    .clickable { showRawTable = false }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            if (copiedCode) "✓ 已复制" else "复制代码",
                            style = HerdrType.Caption,
                            color = if (copiedCode) HerdrColors.Done else HerdrColors.Accent,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(HerdrShapes.Small)
                                .clickable {
                                    onCopy(block.content)
                                    copiedCode = true
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    HerdrDivider()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
                    ) {
                        if (isDiffBlock) {
                            val diffAnnotated = remember(block.content, addBg, delBg, addFg, delFg, metaFg) {
                                buildAnnotatedString {
                                    lines.forEachIndexed { idx, l ->
                                        if (idx > 0) append('\n')
                                        val start = length
                                        append(l)
                                        val trimL = l.trimStart()
                                        val isAdd = trimL.startsWith("+") && !trimL.startsWith("+++") ||
                                            trimL.contains(" + ") && trimL.firstOrNull()?.isDigit() == true
                                        val isDel = trimL.startsWith("-") && !trimL.startsWith("---") ||
                                            trimL.contains(" - ") && trimL.firstOrNull()?.isDigit() == true
                                        val isHunk = trimL.startsWith("@@") || trimL.startsWith("diff --git")
                                        when {
                                            isAdd -> addStyle(SpanStyle(background = addBg, color = addFg), start, length)
                                            isDel -> addStyle(SpanStyle(background = delBg, color = delFg), start, length)
                                            isHunk -> addStyle(SpanStyle(color = metaFg, fontWeight = FontWeight.SemiBold), start, length)
                                        }
                                    }
                                }
                            }
                            Text(diffAnnotated, style = HerdrType.Mono, color = HerdrColors.Foreground, softWrap = false)
                        } else {
                            Text(block.content, style = HerdrType.Mono, color = HerdrColors.Foreground, softWrap = false)
                        }
                    }
                }
            }
        }

        is TerminalSemanticBlock.UrgentAlertBlock -> {
            val color = if (block.isError) HerdrColors.Blocked else HerdrColors.Working
            val tint = if (block.isError) HerdrColors.BlockedSubtle else HerdrColors.WorkingSubtle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HerdrShapes.Block)
                    .background(tint)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = { onCopy(if (block.detail.isNotBlank()) "${block.title}\n${block.detail}" else block.title) }
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(block.title, style = HerdrType.Label, color = color, fontWeight = FontWeight.SemiBold)
                if (block.detail.isNotBlank() && block.detail != block.title) {
                    Spacer(Modifier.height(4.dp))
                    Text(block.detail, style = HerdrType.Body, color = HerdrColors.Foreground)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThoughtPillCard(
    block: TerminalSemanticBlock.ToolGroupBlock,
    stateKey: String,
    onCopy: (String) -> Unit
) {
    var expanded by remember(stateKey, block.defaultExpanded) { mutableStateOf(block.defaultExpanded) }
    val label = block.thoughtSummaryLabel ?: block.summaryLabel
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(HerdrShapes.Control)
                .background(HerdrColors.Surface)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = { onCopy(block.steps.joinToString("\n")) }
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("💡", fontSize = 12.sp)
            Text(
                text = label,
                style = HerdrType.Caption,
                color = HerdrColors.Muted,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            val preview = block.thoughtPreview?.takeIf { it.isNotBlank() && !expanded }
            if (preview != null) {
                OneLine(preview, HerdrType.Caption, HerdrColors.Dim, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = HerdrColors.Dim,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            val railColor = HerdrColors.Accent.copy(alpha = 0.45f)
            val thoughtText = remember(block.entries, block.steps) {
                if (block.entries.isNotEmpty()) {
                    block.entries.joinToString("\n") { e ->
                        val details = if (e.detailLines.isNotEmpty()) "\n  " + e.detailLines.joinToString("\n  ") else ""
                        "${e.title}$details"
                    }
                } else {
                    block.steps.joinToString("\n")
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp)
                    .drawBehind {
                        drawRect(color = railColor, size = Size(2.dp.toPx(), size.height))
                    }
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = thoughtText,
                    style = HerdrType.Caption.copy(lineHeight = 18.sp),
                    color = HerdrColors.Muted
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolGroupCard(
    block: TerminalSemanticBlock.ToolGroupBlock,
    stateKey: String,
    onCopy: (String) -> Unit
) {
    var expanded by remember(stateKey, block.defaultExpanded) { mutableStateOf(block.defaultExpanded) }
    val primaryLabel = block.actionBreakdownLabel ?: block.summaryLabel
    val pillBg = when {
        block.hasError -> HerdrColors.BlockedSubtle
        block.isRunning -> HerdrColors.WorkingSubtle
        else -> HerdrColors.Surface
    }
    val pillFg = when {
        block.hasError -> HerdrColors.Blocked
        block.isRunning -> HerdrColors.Working
        else -> HerdrColors.Muted
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(HerdrShapes.Control)
                .background(pillBg)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = { onCopy(block.steps.joinToString("\n")) }
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (block.isRunning) {
                StatusDot(HerdrColors.Working, 6.dp, pulsing = true)
            } else {
                Text("🛠️", fontSize = 12.sp)
            }
            Text(
                text = primaryLabel,
                style = HerdrType.Caption,
                color = pillFg,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!block.thoughtSummaryLabel.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .clip(HerdrShapes.Pill)
                        .background(HerdrColors.Raised)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "💡 ${block.thoughtSummaryLabel}",
                        style = HerdrType.Caption.copy(fontSize = 10.5.sp),
                        color = HerdrColors.Dim,
                        maxLines = 1
                    )
                }
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = HerdrColors.Dim,
                modifier = Modifier.size(16.dp)
            )
        }
        if (block.changedFiles.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                block.changedFiles.take(8).forEach { file ->
                    Row(
                        modifier = Modifier
                            .clip(HerdrShapes.Pill)
                            .background(HerdrColors.Raised)
                            .clickable { onCopy(file) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("📝", fontSize = 10.sp)
                        Text(
                            file,
                            style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            color = HerdrColors.Foreground,
                            maxLines = 1
                        )
                    }
                }
                if (block.changedFiles.size > 8) {
                    Text(
                        "+${block.changedFiles.size - 8}",
                        style = HerdrType.Caption,
                        color = HerdrColors.Dim,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            val railColor = HerdrColors.Border
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp)
                    .drawBehind {
                        drawRect(color = railColor, size = Size(2.dp.toPx(), size.height))
                    }
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (block.entries.isNotEmpty()) {
                    block.entries.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val (tagBg, tagFg) = when (entry.category) {
                                    ToolActionCategory.EDIT -> HerdrColors.DoneSubtle to HerdrColors.Done
                                    ToolActionCategory.COMMAND -> HerdrColors.AccentSubtle to HerdrColors.Accent
                                    ToolActionCategory.THOUGHT -> HerdrColors.Raised to HerdrColors.Muted
                                    else -> HerdrColors.Raised to HerdrColors.Dim
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (entry.hasError) HerdrColors.BlockedSubtle else tagBg)
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        entry.category.badgeText,
                                        style = HerdrType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                        color = if (entry.hasError) HerdrColors.Blocked else tagFg,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    entry.title,
                                    style = HerdrType.Mono.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                                    color = if (entry.hasError) HerdrColors.Blocked else HerdrColors.Foreground,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (entry.detailLines.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(start = 8.dp)
                                ) {
                                    Text(
                                        entry.detailLines.joinToString("\n"),
                                        style = HerdrType.Mono.copy(fontSize = 11.sp, lineHeight = 16.sp),
                                        color = HerdrColors.Dim,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        block.steps.joinToString("\n"),
                        style = HerdrType.Mono.copy(fontSize = 12.sp, lineHeight = 18.sp),
                        color = HerdrColors.Muted
                    )
                }
            }
        }
    }
}

@Composable
private fun TurnQuickJumpBar(
    turns: List<ConversationTurn>,
    collapsedTurnIndices: Set<Int>,
    onJumpToTurn: (Int) -> Unit,
    onToggleCollapseOlder: () -> Unit
) {
    val olderCollapsibleCount = remember(turns) {
        turns.dropLast(1).count { !it.hasInteractiveCard && !it.isRunning }
    }
    val allOlderCollapsed = remember(turns, collapsedTurnIndices) {
        val older = turns.dropLast(1).filter { !it.hasInteractiveCard && !it.isRunning }
        older.isNotEmpty() && older.all { it.turnIndex in collapsedTurnIndices }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HerdrColors.Surface.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "共 ${turns.size} 轮",
            style = HerdrType.Caption,
            color = HerdrColors.Muted,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            turns.forEach { turn ->
                val rawPrompt = turn.userPrompt?.text?.let { splitHcPrompt(it).second }?.trim().orEmpty()
                val shortPrompt = if (rawPrompt.length > 8) rawPrompt.take(8) + "…" else rawPrompt
                val pillLabel = if (shortPrompt.isNotEmpty()) "#${turn.turnIndex} $shortPrompt" else "#${turn.turnIndex}"
                val isCurrent = turn.turnIndex == turns.last().turnIndex
                Row(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(HerdrShapes.Pill)
                        .background(if (isCurrent) HerdrColors.AccentSubtle else HerdrColors.Raised)
                        .clickable { onJumpToTurn(turn.turnIndex) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (turn.isRunning) {
                        StatusDot(HerdrColors.Working, 5.dp, pulsing = true)
                    } else if (turn.hasError) {
                        StatusDot(HerdrColors.Blocked, 5.dp)
                    }
                    Text(
                        text = pillLabel,
                        style = HerdrType.Caption.copy(fontSize = 11.sp),
                        color = if (isCurrent) HerdrColors.Accent else HerdrColors.Foreground,
                        maxLines = 1
                    )
                }
            }
        }
        if (olderCollapsibleCount > 0) {
            Box(
                modifier = Modifier
                    .height(26.dp)
                    .clip(HerdrShapes.Pill)
                    .background(HerdrColors.Raised)
                    .clickable(onClick = onToggleCollapseOlder)
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (allOlderCollapsed) "展开全部" else "折叠旧轮次",
                    style = HerdrType.Caption.copy(fontSize = 11.sp),
                    color = HerdrColors.Accent,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TurnDividerHeader(
    turn: ConversationTurn,
    canCollapse: Boolean,
    onCollapse: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(HerdrShapes.Pill)
                .background(HerdrColors.Raised)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                "第 ${turn.turnIndex} 轮",
                style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                color = HerdrColors.Muted
            )
        }
        val meta = buildList {
            if (turn.toolOperationCount > 0) add("${turn.toolOperationCount} 步操作")
            if (turn.changedFiles.isNotEmpty()) add("改 ${turn.changedFiles.size} 文件")
            turn.durationLabel?.let { add(it) }
        }.joinToString(" · ")
        if (meta.isNotEmpty()) {
            OneLine(meta, HerdrType.Caption, HerdrColors.Dim, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (canCollapse) {
            Text(
                "收起 ▴",
                style = HerdrType.Caption,
                color = HerdrColors.Dim,
                modifier = Modifier
                    .clip(HerdrShapes.Small)
                    .clickable(onClick = onCollapse)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun TurnSummaryCard(
    turn: ConversationTurn,
    onExpand: () -> Unit
) {
    val promptText = turn.userPrompt?.text?.let { splitHcPrompt(it).second }?.trim().orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Block)
            .background(HerdrColors.Surface)
            .border(1.dp, HerdrColors.Border.copy(alpha = 0.6f), HerdrShapes.Block)
            .clickable(onClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(HerdrShapes.Pill)
                    .background(HerdrColors.AccentSubtle)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    "#${turn.turnIndex}",
                    style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    color = HerdrColors.Accent
                )
            }
            val meta = buildList {
                if (turn.toolOperationCount > 0) add("${turn.toolOperationCount} 工具")
                if (turn.changedFiles.isNotEmpty()) add("${turn.changedFiles.size} 改动")
                turn.durationLabel?.let { add(it) }
            }.joinToString(" · ")
            if (meta.isNotEmpty()) {
                OneLine(meta, HerdrType.Caption, HerdrColors.Dim, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (turn.hasError) {
                Text("含异常", style = HerdrType.Caption, color = HerdrColors.Blocked, fontWeight = FontWeight.Medium)
            }
            Text("展开 ▾", style = HerdrType.Caption, color = HerdrColors.Accent, fontWeight = FontWeight.Medium)
        }
        if (promptText.isNotEmpty()) {
            Text(
                text = "Input: $promptText",
                style = HerdrType.Caption,
                color = HerdrColors.Foreground,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (turn.answerPreview.isNotBlank()) {
            Text(
                text = "Output: ${turn.answerPreview}",
                style = HerdrType.Caption,
                color = HerdrColors.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiveWorkingActivityCard(
    activity: LiveWorkingActivity,
    elapsedSec: Int,
    expandLabel: String,
    onExpand: () -> Unit
) {
    val timerText = activity.elapsedHint?.takeIf { it.isNotBlank() } ?: run {
        if (elapsedSec < 60) "${elapsedSec}s" else "${elapsedSec / 60}m ${elapsedSec % 60}s"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Block)
            .background(HerdrColors.WorkingSubtle)
            .border(1.dp, HerdrColors.Working.copy(alpha = 0.4f), HerdrShapes.Block)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusDot(HerdrColors.Working, 7.dp, pulsing = true)
            Text(
                "正在执行",
                style = HerdrType.Caption,
                color = HerdrColors.Working,
                fontWeight = FontWeight.SemiBold
            )
            Box(
                modifier = Modifier
                    .clip(HerdrShapes.Pill)
                    .background(HerdrColors.Surface)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    timerText,
                    style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace),
                    color = HerdrColors.Foreground
                )
            }
            if (activity.totalStepsInTurn > 0) {
                Text(
                    "· 已跑 ${activity.totalStepsInTurn} 步",
                    style = HerdrType.Caption,
                    color = HerdrColors.Muted
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                expandLabel,
                style = HerdrType.Caption,
                color = HerdrColors.Accent,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(HerdrShapes.Small)
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            text = activity.title,
            style = HerdrType.Label,
            color = HerdrColors.Foreground,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (activity.tailLines.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HerdrShapes.Small)
                    .background(HerdrColors.Canvas.copy(alpha = 0.65f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = activity.tailLines.joinToString("\n"),
                    style = HerdrType.Mono.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = HerdrColors.Muted,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun QueuedPromptBubble(
    index: Int,
    item: QueuedPromptItem
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Spacer(Modifier.width(48.dp))
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .background(HerdrColors.WorkingSubtle)
                .border(1.dp, HerdrColors.Working.copy(alpha = 0.4f), RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(HerdrColors.Working, 6.dp, pulsing = true)
                Text("队列 #$index · ${item.mode.badgeText}", style = HerdrType.Caption, color = HerdrColors.Working, fontWeight = FontWeight.Medium)
            }
            if (item.text.isNotBlank()) {
                Text(item.text, style = HerdrType.Body, color = HerdrColors.Foreground.copy(alpha = 0.88f))
            }
            if (item.attachmentNames.isNotEmpty()) {
                Text(
                    item.attachmentNames.joinToString(" ") { "[📎 $it]" },
                    style = HerdrType.Caption,
                    color = HerdrColors.Muted
                )
            }
        }
    }
}

@Composable
private fun QueuedPromptsTray(
    items: List<QueuedPromptItem>,
    liveStatus: AgentStateWord,
    onSendNow: (String) -> Unit,
    onEdit: (String) -> Unit,
    onMoveToTop: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    val pausedForDecision = liveStatus == AgentStateWord.BLOCKED || liveStatus == AgentStateWord.ERROR
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Block)
            .background(HerdrColors.Surface)
            .border(
                1.dp,
                (if (pausedForDecision) HerdrColors.Blocked else HerdrColors.Working).copy(alpha = 0.42f),
                HerdrShapes.Block
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusDot(if (pausedForDecision) HerdrColors.Blocked else HerdrColors.Working, 6.dp, pulsing = !pausedForDecision)
            Text(
                text = if (pausedForDecision) {
                    "待发队列 (${items.size}) · 等待当前确认后恢复自动发送"
                } else {
                    "待发队列 (${items.size}) · 本轮结束后按顺序自动发送"
                },
                style = HerdrType.Caption,
                color = if (pausedForDecision) HerdrColors.Blocked else HerdrColors.Working,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        items.forEachIndexed { idx, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HerdrShapes.Small)
                    .background(HerdrColors.Raised)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "#${idx + 1}",
                    style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    color = HerdrColors.Accent
                )
                Column(modifier = Modifier.weight(1f)) {
                    val preview = buildList {
                        if (item.text.isNotBlank()) add(item.text)
                        if (item.attachmentNames.isNotEmpty()) add("📎 ${item.attachmentNames.size} 图")
                    }.joinToString(" · ")
                    OneLine(preview, HerdrType.Caption, HerdrColors.Foreground)
                }
                Box(
                    modifier = Modifier
                        .clip(HerdrShapes.Pill)
                        .background(HerdrColors.AccentSubtle)
                        .clickable { onSendNow(item.id) }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text("⚡ 插话", style = HerdrType.Caption.copy(fontSize = 11.sp), color = HerdrColors.Accent, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .clip(HerdrShapes.Pill)
                        .background(HerdrColors.Surface)
                        .clickable { onEdit(item.id) }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text("编辑", style = HerdrType.Caption.copy(fontSize = 11.sp), color = HerdrColors.Foreground)
                }
                if (idx > 0) {
                    Box(
                        modifier = Modifier
                            .clip(HerdrShapes.Pill)
                            .background(HerdrColors.Surface)
                            .clickable { onMoveToTop(item.id) }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text("↑", style = HerdrType.Caption.copy(fontSize = 11.sp), color = HerdrColors.Muted)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable { onRemove(item.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "移除排队消息", tint = HerdrColors.Dim, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@Composable
private fun TurnChangedFilesFooter(
    files: List<String>,
    onCopy: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "本轮变更 (${files.size})",
            style = HerdrType.Caption,
            color = HerdrColors.Dim,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        files.take(10).forEach { file ->
            Row(
                modifier = Modifier
                    .clip(HerdrShapes.Pill)
                    .background(HerdrColors.Raised)
                    .clickable { onCopy(file) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("📝", fontSize = 10.sp)
                Text(
                    text = file,
                    style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = HerdrColors.Foreground,
                    maxLines = 1
                )
            }
        }
        if (files.size > 10) {
            Text(
                text = "+${files.size - 10}",
                style = HerdrType.Caption,
                color = HerdrColors.Dim
            )
        }
    }
}
