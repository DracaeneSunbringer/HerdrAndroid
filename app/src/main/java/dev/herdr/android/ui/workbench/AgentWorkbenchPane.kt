package dev.herdr.android.ui.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import dev.herdr.android.ui.terminal.QuickReplyAction
import dev.herdr.android.ui.terminal.StructuredTerminalReader
import dev.herdr.android.ui.terminal.TerminalSemanticBlock
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
    requestInputFocusSignal: Long,
    appSettings: HerdrAppSettings,
    savedDesktopDims: Map<String, Pair<Int, Int>>,
    mobilePtyTargetCols: Int,
    onNavigateBack: () -> Unit,
    onSwitchTerminal: (TerminalSummary) -> Unit,
    onUpdateDraft: (String) -> Unit,
    onTakeoverPtyWidth: (Int) -> Unit,
    onReleasePtyWidth: () -> Unit,
    onSendLine: (String, Boolean) -> Unit,
    onSendSpecialKey: (String) -> Unit,
    onInterrupt: () -> Unit,
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
            StructuredTerminalReader.analyzeWorkbench(rawScreen, summary)
        }
        snapshot = computed
        hasInitialSnapshot = true
    }

    val blocks = snapshot.blocks
    val quickActions = snapshot.quickActions

    val shouldShowPendingReceipt = remember(dispatchedReceipt, blocks, summary.turn) {
        val receipt = dispatchedReceipt ?: return@remember false
        if (summary.turn > receipt.sentAtTurn) return@remember false
        val normReceipt = receipt.text.trim().replace(whitespaceRegex, "")
        if (normReceipt.isEmpty()) return@remember false
        val alreadyEchoedInBlocks = blocks.takeLast(8).any { b ->
            if (b is TerminalSemanticBlock.UserPromptBlock) {
                val body = splitHcPrompt(b.text).second.trim().replace(whitespaceRegex, "")
                body == normReceipt ||
                    (normReceipt.length >= 4 && (body.contains(normReceipt) || normReceipt.contains(body)))
            } else {
                false
            }
        }
        val tailRunningGroup = (blocks.lastOrNull() as? TerminalSemanticBlock.ToolGroupBlock)
            ?.summaryLabel?.endsWith("· 进行中") == true
        !alreadyEchoedInBlocks && !tailRunningGroup
    }

    val effectiveStatus = if (shouldShowPendingReceipt) {
        AgentStateWord.WORKING
    } else if (hasInitialSnapshot) {
        snapshot.liveStatus
    } else {
        summary.agentStatus
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

    val hasCwdHeader = summary.cwd.isNotBlank()
    val hasPendingReceiptItem = shouldShowPendingReceipt && dispatchedReceipt != null
    val readerAnchorIndex = (if (hasCwdHeader) 1 else 0) +
        (if (blocks.isEmpty()) 1 else blocks.size) +
        (if (hasPendingReceiptItem) 1 else 0)

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

    Column(modifier = Modifier.fillMaxSize().background(HerdrColors.Canvas)) {
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
                LazyColumn(
                    state = readerListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (hasCwdHeader) {
                        item(key = "cwd", contentType = "cwd") {
                            OneLine(summary.cwd, HerdrType.Caption.copy(fontFamily = FontFamily.Monospace), HerdrColors.Dim)
                        }
                    }
                    if (blocks.isEmpty()) {
                        item(key = "empty", contentType = "empty") {
                            Text(
                                summary.agentMessage?.takeIf { it.isNotBlank() } ?: "正在同步画面…",
                                style = HerdrType.Body,
                                color = HerdrColors.Muted
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = blocks,
                            key = { idx, _ -> "${session.address}#$idx" },
                            contentType = { _, block -> block::class }
                        ) { idx, block ->
                            ReaderBlock(
                                block = block,
                                stateKey = "${session.address}#$idx",
                                onCopy = { clipboard.setText(AnnotatedString(it)) }
                            )
                        }
                    }
                    if (hasPendingReceiptItem && dispatchedReceipt != null) {
                        item(key = "pending_receipt", contentType = "receipt") {
                            SentBubble(dispatchedReceipt.text)
                        }
                    }
                    item(key = "bottom_anchor", contentType = "anchor") {
                        Spacer(Modifier.height(1.dp))
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

        // ---------------- Composer ----------------
        HerdrDivider()
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                val keys = listOf("esc" to "Esc", "tab" to "Tab", "up" to "↑", "down" to "↓", "ctrl+o" to "^O", "ctrl+t" to "^T", "enter" to "⏎", "ctrl+c" to "^C")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    keys.forEach { (wire, label) ->
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

            WorkbenchComposer(
                sessionAddress = session.address,
                targetLabel = summary.address.substringAfterLast('/'),
                initialDraft = draftText,
                focusRequester = inputFocusRequester,
                onUpdateDraft = onUpdateDraft,
                onSubmitLine = { text ->
                    followBottom = true
                    onSendLine(text, false)
                },
                onSubmitEmpty = {
                    followBottom = true
                    onSendSpecialKey("enter")
                }
            )
        }
    }
}

@Composable
private fun WorkbenchComposer(
    sessionAddress: String,
    targetLabel: String,
    initialDraft: String,
    focusRequester: FocusRequester,
    onUpdateDraft: (String) -> Unit,
    onSubmitLine: (String) -> Unit,
    onSubmitEmpty: () -> Unit
) {
    var localDraft by remember(sessionAddress) { mutableStateOf(initialDraft) }
    val latestDraft by rememberUpdatedState(localDraft)
    val latestOnUpdateDraft by rememberUpdatedState(onUpdateDraft)

    LaunchedEffect(sessionAddress, localDraft) {
        delay(250)
        latestOnUpdateDraft(localDraft)
    }

    DisposableEffect(sessionAddress) {
        onDispose {
            latestOnUpdateDraft(latestDraft)
        }
    }

    fun submit() {
        val trimmedCheck = localDraft
        if (trimmedCheck.isNotBlank()) {
            localDraft = ""
            onUpdateDraft("")
            onSubmitLine(trimmedCheck)
        } else {
            onSubmitEmpty()
        }
    }

    Row(verticalAlignment = Alignment.Bottom) {
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
                OneLine("给 $targetLabel 下达指令", HerdrType.Body.copy(lineHeight = 20.sp), HerdrColors.Dim)
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
        val hasText = localDraft.isNotBlank()
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (hasText) HerdrColors.Accent else HerdrColors.Raised)
                .clickable { submit() },
            contentAlignment = Alignment.Center
        ) {
            if (hasText) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = HerdrColors.OnAccent, modifier = Modifier.size(20.dp))
            } else {
                Text("⏎", color = HerdrColors.Muted, fontSize = 18.sp)
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
private fun ReaderBlock(block: TerminalSemanticBlock, stateKey: String, onCopy: (String) -> Unit) {
    when (block) {
        is TerminalSemanticBlock.UserPromptBlock -> {
            val (from, body) = remember(block.text) { splitHcPrompt(block.text) }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                Spacer(Modifier.width(48.dp))
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                        .background(HerdrColors.AccentSubtle)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onLongClick = { onCopy(body) }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (from != null) {
                        Text("来自 $from", style = HerdrType.Caption, color = HerdrColors.Accent, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(body, style = HerdrType.Body, color = HerdrColors.Foreground, maxLines = 12, overflow = TextOverflow.Ellipsis)
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
                block.isHeading -> Text(
                    block.text,
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
                        Text(body, style = HerdrType.Body, color = HerdrColors.Foreground, modifier = Modifier.weight(1f))
                    }
                }
                else -> Text(block.text, style = HerdrType.Body, color = HerdrColors.Foreground, modifier = copyMod)
            }
        }

        is TerminalSemanticBlock.ToolGroupBlock -> {
            var expanded by remember(stateKey, block.defaultExpanded) { mutableStateOf(block.defaultExpanded) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .clip(HerdrShapes.Small)
                        .combinedClickable(
                            onClick = { expanded = !expanded },
                            onLongClick = { onCopy(block.steps.joinToString("\n")) }
                        )
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        tint = HerdrColors.Dim,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        block.summaryLabel,
                        style = HerdrType.Caption,
                        color = if (block.hasError) HerdrColors.Blocked else HerdrColors.Dim,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    val railColor = HerdrColors.Border
                    val dimColor = HerdrColors.Dim
                    val mutedColor = HerdrColors.Muted
                    val joinedSteps = remember(block.steps, dimColor, mutedColor) {
                        buildAnnotatedString {
                            block.steps.forEachIndexed { i, line ->
                                if (i > 0) append('\n')
                                val start = length
                                append(line)
                                addStyle(
                                    SpanStyle(color = if (line.startsWith(" ")) dimColor else mutedColor),
                                    start,
                                    length
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 9.dp, top = 6.dp)
                            .drawBehind {
                                drawRect(color = railColor, size = Size(2.dp.toPx(), size.height))
                            }
                            .padding(start = 14.dp)
                            .horizontalScroll(rememberScrollState())
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                                onLongClick = { onCopy(block.steps.joinToString("\n")) }
                            )
                    ) {
                        Text(
                            text = joinedSteps,
                            style = HerdrType.Mono.copy(fontSize = 12.sp, lineHeight = 18.sp),
                            softWrap = false
                        )
                    }
                }
            }
        }

        is TerminalSemanticBlock.CodeOrTableBlock -> {
            val lines = remember(block.content) { block.content.lines().size }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HerdrShapes.Block)
                    .background(HerdrColors.Surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val headerLabel = if (!block.kindLabel.isNullOrBlank()) "${block.kindLabel} · $lines 行" else "$lines 行"
                    Text(headerLabel, style = HerdrType.Caption, color = HerdrColors.Dim, modifier = Modifier.weight(1f))
                    Text(
                        "复制",
                        style = HerdrType.Caption,
                        color = HerdrColors.Muted,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(HerdrShapes.Small)
                            .clickable { onCopy(block.content) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp)
                ) {
                    Text(block.content, style = HerdrType.Mono, color = HerdrColors.Foreground, softWrap = false)
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
