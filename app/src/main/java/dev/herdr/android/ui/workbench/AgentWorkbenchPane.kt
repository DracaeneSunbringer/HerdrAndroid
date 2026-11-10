package dev.herdr.android.ui.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import dev.herdr.android.ui.theme.HerdrColors
import dev.herdr.android.ui.theme.HerdrType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val hcWrapperRegex = Regex("^<hc\\b[^>]*?(?:from=\"([^\"]*)\")?[^>]*>([\\s\\S]*?)(?:</hc>)?\\s*$")

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

    val blocks = remember(rawScreen, summary.agentApp) {
        StructuredTerminalReader.parseBlocks(rawScreen, summary.agentApp)
    }
    val quickActions = remember(rawScreen, summary) { StructuredTerminalReader.extractQuickActions(rawScreen, summary) }

    val shouldShowPendingReceipt = remember(dispatchedReceipt, blocks, summary.turn) {
        val receipt = dispatchedReceipt ?: return@remember false
        if (summary.turn > receipt.sentAtTurn) return@remember false
        val normReceipt = receipt.text.trim().replace(Regex("\\s+"), "")
        if (normReceipt.isEmpty()) return@remember false
        val alreadyEchoedInBlocks = blocks.takeLast(8).any { b ->
            if (b is TerminalSemanticBlock.UserPromptBlock) {
                val body = splitHcPrompt(b.text).second.trim().replace(Regex("\\s+"), "")
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

    val effectiveStatus = remember(rawScreen, summary, blocks, shouldShowPendingReceipt) {
        if (shouldShowPendingReceipt) {
            AgentStateWord.WORKING
        } else {
            StructuredTerminalReader.resolveLiveStatus(rawScreen, summary, blocks)
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
    val styledScreen = remember(rawScreen, activePreset, readerMode) {
        if (readerMode) AnnotatedString("") else AnsiTerminalParser.parseToAnnotatedString(rawScreen, mobileReflow = false)
    }

    val readerScroll = rememberScrollState()
    val rawVScroll = rememberScrollState()
    val rawHScroll = rememberScrollState()
    var followBottom by remember(session.address) { mutableStateOf(true) }
    var isProgrammaticScroll by remember(session.address) { mutableStateOf(false) }
    var lastProgrammaticTarget by remember(session.address) { mutableIntStateOf(0) }
    val activeVScroll = if (readerMode) readerScroll else rawVScroll

    LaunchedEffect(activeVScroll) {
        snapshotFlow { Triple(activeVScroll.value, activeVScroll.maxValue, activeVScroll.isScrollInProgress) }
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

    LaunchedEffect(rawScreen, readerMode, activeVScroll.maxValue, dispatchedReceipt?.timestampMs) {
        val max = activeVScroll.maxValue
        if (max <= 0) return@LaunchedEffect
        if (followBottom && !activeVScroll.isScrollInProgress) {
            isProgrammaticScroll = true
            try {
                activeVScroll.scrollTo(max)
                lastProgrammaticTarget = activeVScroll.value
            } finally {
                isProgrammaticScroll = false
            }
        }
    }
    val isScrolledUp = !followBottom && activeVScroll.maxValue > 32 && (activeVScroll.maxValue - activeVScroll.value) > 32

    fun dispatch(action: QuickReplyAction) {
        followBottom = true
        if (action.isSpecialKey) {
            if (action.payload == "ctrl+c") onInterrupt() else onSendSpecialKey(action.payload)
        } else {
            onSendLine(action.payload, false)
        }
    }

    fun submitDraft() {
        followBottom = true
        if (draftText.isNotBlank()) {
            val toSend = draftText
            onUpdateDraft("")
            onSendLine(toSend, false)
        } else {
            onSendSpecialKey("enter")
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
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(readerScroll)
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (summary.cwd.isNotBlank()) {
                            OneLine(summary.cwd, HerdrType.Caption.copy(fontFamily = FontFamily.Monospace), HerdrColors.Dim)
                        }
                        if (blocks.isEmpty()) {
                            Text(
                                summary.agentMessage?.takeIf { it.isNotBlank() } ?: "正在同步画面…",
                                style = HerdrType.Body,
                                color = HerdrColors.Muted
                            )
                        } else {
                            blocks.forEachIndexed { idx, block ->
                                ReaderBlock(
                                    block = block,
                                    stateKey = "${session.address}#$idx",
                                    onCopy = { clipboard.setText(AnnotatedString(it)) }
                                )
                            }
                        }
                        if (shouldShowPendingReceipt && dispatchedReceipt != null) {
                            SentBubble(dispatchedReceipt.text)
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
                            .horizontalScroll(rawHScroll, enabled = rawHScroll.maxValue > 4)
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
                if (!softWrapLines && rawHScroll.maxValue > 28 && (rawHScroll.maxValue - rawHScroll.value) > 20) {
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
                                    activeVScroll.animateScrollTo(activeVScroll.maxValue)
                                    lastProgrammaticTarget = activeVScroll.value
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
                    if (draftText.isEmpty()) {
                        val target = summary.address.substringAfterLast('/')
                        OneLine("给 $target 下达指令", HerdrType.Body.copy(lineHeight = 20.sp), HerdrColors.Dim)
                    }
                    BasicTextField(
                        value = draftText,
                        onValueChange = onUpdateDraft,
                        singleLine = true,
                        textStyle = HerdrType.Body.copy(color = HerdrColors.Foreground, lineHeight = 20.sp),
                        cursorBrush = SolidColor(HerdrColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitDraft() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(inputFocusRequester)
                    )
                }
                Spacer(Modifier.width(8.dp))
                val hasText = draftText.isNotBlank()
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (hasText) HerdrColors.Accent else HerdrColors.Raised)
                        .clickable { submitDraft() },
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

        is TerminalSemanticBlock.AgentProseBlock -> when {
            block.isHeading -> Text(
                block.text,
                style = HerdrType.Heading,
                color = HerdrColors.Foreground,
                modifier = Modifier.padding(top = 6.dp)
            )
            block.isBullet -> {
                val ordered = Regex("^(\\d{1,2}[.)、]|[（(]\\d{1,2}[)）])\\s*").find(block.text)
                val marker = ordered?.value?.trim() ?: "•"
                val body = if (ordered != null) block.text.substring(ordered.value.length) else block.text
                Row(modifier = Modifier.fillMaxWidth().padding(start = (block.indentLevel * 16).dp)) {
                    Box(modifier = Modifier.widthIn(min = 18.dp).padding(end = 6.dp)) {
                        Text(marker, style = HerdrType.Body, color = HerdrColors.Dim)
                    }
                    Text(body, style = HerdrType.Body, color = HerdrColors.Foreground, modifier = Modifier.weight(1f))
                }
            }
            else -> Text(block.text, style = HerdrType.Body, color = HerdrColors.Foreground)
        }

        is TerminalSemanticBlock.ToolGroupBlock -> {
            var expanded by remember(stateKey) { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .clip(HerdrShapes.Small)
                        .clickable { expanded = !expanded }
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 9.dp, top = 6.dp)
                            .drawBehind {
                                drawRect(color = railColor, size = Size(2.dp.toPx(), size.height))
                            }
                            .padding(start = 14.dp)
                            .horizontalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        block.steps.forEach { line ->
                            Text(
                                line,
                                style = HerdrType.Mono.copy(fontSize = 12.sp),
                                color = if (line.startsWith(" ")) HerdrColors.Dim else HerdrColors.Muted,
                                softWrap = false
                            )
                        }
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
