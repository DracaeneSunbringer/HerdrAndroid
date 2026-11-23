package dev.herdr.android.ui.workbench

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.ui.components.HerdrButton
import dev.herdr.android.ui.components.HerdrButtonKind
import dev.herdr.android.ui.components.HerdrDivider
import dev.herdr.android.ui.components.HerdrShapes
import dev.herdr.android.ui.components.OneLine
import dev.herdr.android.ui.components.StatusDot
import dev.herdr.android.ui.terminal.AgyAgentMode
import dev.herdr.android.ui.terminal.AgyAskCardState
import dev.herdr.android.ui.terminal.AgyBtwCardState
import dev.herdr.android.ui.terminal.AgyBtwTurn
import dev.herdr.android.ui.terminal.AgySlashCatalog
import dev.herdr.android.ui.terminal.AgySlashModalKind
import dev.herdr.android.ui.terminal.AgySlashModalState
import dev.herdr.android.ui.terminal.AgyTerminalState
import dev.herdr.android.ui.terminal.AskNavDirection
import dev.herdr.android.ui.terminal.BtwContentSegment
import dev.herdr.android.ui.terminal.BtwRichContentParser
import dev.herdr.android.ui.terminal.BtwTableAlign
import dev.herdr.android.ui.theme.HerdrColors
import dev.herdr.android.ui.theme.HerdrType

/**
 * Compact live progress strip docked above the composer while a closed-loop key/command
 * sequence or reconnect re-anchoring is in flight.
 */
@Composable
fun SequenceStatusStrip(
    status: SequenceExecutionStatus,
    onCancel: () -> Unit
) {
    val accent = if (status.reconnecting) HerdrColors.Blocked else HerdrColors.Working
    val tint = if (status.reconnecting) HerdrColors.BlockedSubtle else HerdrColors.WorkingSubtle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Control)
            .background(tint)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusDot(accent, 7.dp, pulsing = true)
        Column(modifier = Modifier.weight(1f)) {
            OneLine(
                "${status.label} · 步骤 ${status.stepIndex}/${status.totalSteps}",
                HerdrType.Caption.copy(fontWeight = FontWeight.SemiBold),
                accent
            )
            OneLine(
                status.currentStepDescription,
                HerdrType.Caption,
                HerdrColors.Foreground
            )
        }
        Box(
            modifier = Modifier
                .clip(HerdrShapes.Pill)
                .background(HerdrColors.Surface)
                .clickable(onClick = onCancel)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text("中止", style = HerdrType.Caption, color = HerdrColors.Foreground, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Native in-stream card rendered inside Reader Mode whenever Antigravity (`agy`) opens an
 * interactive `AskQuestion` modal (single-select, multi-select checkboxes, Write-in, multi-question).
 */
@Composable
fun AgyAskCardComposable(
    card: AgyAskCardState,
    isBusy: Boolean,
    onSubmitAnswer: (questionIndex: Int, selectedNumbers: Set<Int>, writeInText: String?) -> Unit,
    onNavigate: (AskNavDirection, Int) -> Unit
) {
    val resetKey = remember(card.questionIndex, card.totalQuestions, card.questionText) {
        "${card.questionIndex}:${card.totalQuestions}:${card.questionText.hashCode()}"
    }
    var localSelected by remember(resetKey) {
        mutableStateOf(
            card.options
                .filter { it.isChecked && !it.isWriteIn }
                .map { it.number }
                .toSet()
        )
    }
    var writeInText by remember(resetKey) {
        mutableStateOf(
            card.currentWriteInDraft.ifBlank {
                card.options.firstOrNull { it.isWriteIn }?.writeInPreview.orEmpty()
            }
        )
    }

    val writeInOpt = remember(card.options) { card.options.firstOrNull { it.isWriteIn } }
    val predefinedOptions = remember(card.options) { card.options.filterNot { it.isWriteIn } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Card)
            .background(HerdrColors.Surface)
            .border(1.dp, HerdrColors.Blocked.copy(alpha = 0.45f), HerdrShapes.Card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header badges + Multi-question navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(HerdrShapes.Pill)
                    .background(HerdrColors.BlockedSubtle)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("待你回答", style = HerdrType.Caption, color = HerdrColors.Blocked, fontWeight = FontWeight.SemiBold)
            }
            if (card.totalQuestions > 1) {
                Box(
                    modifier = Modifier
                        .clip(HerdrShapes.Pill)
                        .background(HerdrColors.Raised)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        "问题 ${card.questionIndex}/${card.totalQuestions}",
                        style = HerdrType.Caption,
                        color = HerdrColors.Foreground,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(HerdrShapes.Pill)
                    .background(HerdrColors.Raised)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    if (card.isMultiSelect) "多选" else "单选",
                    style = HerdrType.Caption,
                    color = HerdrColors.Muted
                )
            }
            Spacer(Modifier.weight(1f))
            if (card.totalQuestions > 1) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(HerdrColors.Raised)
                        .clickable(enabled = !isBusy && card.questionIndex > 1) {
                            onNavigate(AskNavDirection.PREV, card.questionIndex)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        "上一题",
                        tint = if (card.questionIndex > 1) HerdrColors.Foreground else HerdrColors.Dim,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(HerdrColors.Raised)
                        .clickable(enabled = !isBusy && card.questionIndex < card.totalQuestions) {
                            onNavigate(AskNavDirection.NEXT, card.questionIndex)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        "下一题",
                        tint = if (card.questionIndex < card.totalQuestions) HerdrColors.Foreground else HerdrColors.Dim,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(HerdrColors.Raised)
                    .clickable(enabled = !isBusy) { onNavigate(AskNavDirection.CANCEL, card.questionIndex) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, "取消问答", tint = HerdrColors.Muted, modifier = Modifier.size(15.dp))
            }
        }

        // Question prompt text
        Text(
            text = card.questionText,
            style = HerdrType.Body.copy(fontWeight = FontWeight.Medium),
            color = HerdrColors.Foreground
        )

        // All predefined options rendered cleanly without truncation
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            predefinedOptions.forEach { opt ->
                val active = if (card.isMultiSelect) opt.number in localSelected else opt.isCursor || opt.isChecked
                val bg = if (active) HerdrColors.AccentSubtle else HerdrColors.Raised
                val borderCol = if (active) HerdrColors.Accent.copy(alpha = 0.65f) else HerdrColors.Border.copy(alpha = 0.4f)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(HerdrShapes.Control)
                        .background(bg)
                        .border(1.dp, borderCol, HerdrShapes.Control)
                        .clickable(enabled = !isBusy) {
                            if (card.isMultiSelect) {
                                localSelected = if (opt.number in localSelected) {
                                    localSelected - opt.number
                                } else {
                                    localSelected + opt.number
                                }
                            } else {
                                onSubmitAnswer(card.questionIndex, setOf(opt.number), null)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (card.isMultiSelect) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (active) HerdrColors.Accent else HerdrColors.Surface)
                                .border(1.dp, if (active) HerdrColors.Accent else HerdrColors.Muted, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (active) {
                                Icon(Icons.Default.Check, null, tint = HerdrColors.OnAccent, modifier = Modifier.size(14.dp))
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (opt.isCursor) HerdrColors.Accent else HerdrColors.Surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${opt.number}",
                                style = HerdrType.Caption,
                                fontWeight = FontWeight.Bold,
                                color = if (opt.isCursor) HerdrColors.OnAccent else HerdrColors.Foreground
                            )
                        }
                    }
                    Text(
                        text = opt.label,
                        style = HerdrType.Body,
                        color = HerdrColors.Foreground,
                        modifier = Modifier.weight(1f)
                    )
                    if (!card.isMultiSelect && opt.isChecked) {
                        Text("已选", style = HerdrType.Caption, color = HerdrColors.Done, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Write-in (自定义回答) box
        if (writeInOpt != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .clip(HerdrShapes.Control)
                        .background(HerdrColors.Raised)
                        .border(
                            1.dp,
                            if (card.isWriteInFocused || writeInText.isNotBlank()) HerdrColors.Accent.copy(alpha = 0.6f)
                            else HerdrColors.Border.copy(alpha = 0.5f),
                            HerdrShapes.Control
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (writeInText.isEmpty()) {
                        OneLine("其他 / 输入自定义回答…", HerdrType.Body, HerdrColors.Dim)
                    }
                    BasicTextField(
                        value = writeInText,
                        onValueChange = { writeInText = it },
                        singleLine = true,
                        enabled = !isBusy,
                        textStyle = HerdrType.Body.copy(color = HerdrColors.Foreground),
                        cursorBrush = SolidColor(HerdrColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val trimmed = writeInText.trim()
                                if (trimmed.isNotEmpty() || (card.isMultiSelect && localSelected.isNotEmpty())) {
                                    onSubmitAnswer(card.questionIndex, localSelected, trimmed.ifEmpty { null })
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!card.isMultiSelect) {
                    val canSendWriteIn = !isBusy && writeInText.isNotBlank()
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(HerdrShapes.Control)
                            .background(if (canSendWriteIn) HerdrColors.Accent else HerdrColors.Raised)
                            .clickable(enabled = canSendWriteIn) {
                                onSubmitAnswer(card.questionIndex, emptySet(), writeInText.trim())
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            "提交自定义回答",
                            tint = if (canSendWriteIn) HerdrColors.OnAccent else HerdrColors.Dim,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Multi-select confirmation bar
        if (card.isMultiSelect) {
            val canConfirm = !isBusy && (localSelected.isNotEmpty() || writeInText.isNotBlank())
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val summaryHint = buildList {
                    if (localSelected.isNotEmpty()) add("已勾 ${localSelected.size} 项")
                    if (writeInText.isNotBlank()) add("含自定义输入")
                }.joinToString(" · ").ifEmpty { "请至少选择一项" }
                Text(summaryHint, style = HerdrType.Caption, color = HerdrColors.Muted, modifier = Modifier.weight(1f))
                HerdrButton(
                    text = if (card.questionIndex < card.totalQuestions) "确认并下一题" else "确认提交全部",
                    onClick = {
                        onSubmitAnswer(card.questionIndex, localSelected, writeInText.trim().ifEmpty { null })
                    },
                    enabled = canConfirm,
                    kind = HerdrButtonKind.Primary,
                    compact = true
                )
            }
        }
    }
}

/**
 * Native docked card for Antigravity `/btw` side-questions, supporting:
 * - Left/right history paging (`‹ 1 / N ›` + horizontal swipe) across all Q&A turns in the session
 * - Structured rendering for tables (borderless `│`, box `┌┬┐`, Markdown `|...|`), code blocks, lists & prose
 * - Live thinking/tool progress, copy & Tab expand actions, follow-up `/btw`, and one-tap Esc cancel/close.
 */
@Composable
fun AgyBtwCardComposable(
    liveCard: AgyBtwCardState?,
    historyTurns: List<AgyBtwTurn>,
    isHistoryPreviewOnly: Boolean,
    isBusy: Boolean,
    compactViewport: Boolean = false,
    onFollowUpFocusChanged: (Boolean) -> Unit = {},
    onCopyText: (String) -> Unit,
    onToggleExpand: () -> Unit,
    onFollowUp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val followUpFocusRequester = remember { FocusRequester() }
    DisposableEffect(Unit) {
        onDispose { onFollowUpFocusChanged(false) }
    }
    val turns = remember(liveCard, historyTurns) {
        when {
            historyTurns.isNotEmpty() -> historyTurns
            liveCard == null -> emptyList()
            liveCard.turns.isNotEmpty() -> liveCard.turns
            else -> listOf(
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
    }
    if (turns.isEmpty()) return

    var pageIndex by remember { mutableIntStateOf(turns.lastIndex) }
    LaunchedEffect(turns.size) {
        pageIndex = turns.lastIndex
    }
    val safePage = pageIndex.coerceIn(0, turns.lastIndex)
    if (safePage != pageIndex) {
        pageIndex = safePage
    }
    val currentTurn = turns[safePage]
    val isLatestPage = safePage == turns.lastIndex
    val latestRunning = turns.last().isRunning

    var followUpText by remember(currentTurn.question, safePage) { mutableStateOf("") }
    var copiedOnce by remember(currentTurn.answerText, safePage) { mutableStateOf(false) }
    var toolsExpanded by remember(currentTurn.question, safePage) { mutableStateOf(false) }

    val showFatalError = !currentTurn.isRunning &&
        currentTurn.isError &&
        (currentTurn.answerText.isBlank() ||
            currentTurn.answerText.startsWith("Error:", ignoreCase = true) ||
            currentTurn.answerText.startsWith("Got an error:", ignoreCase = true))

    val borderTone = when {
        currentTurn.isRunning -> HerdrColors.Working
        showFatalError -> HerdrColors.Blocked
        else -> HerdrColors.Accent
    }
    val statusBadgeBg = when {
        currentTurn.isRunning -> HerdrColors.WorkingSubtle
        showFatalError -> HerdrColors.BlockedSubtle
        isHistoryPreviewOnly && isLatestPage -> HerdrColors.Raised
        else -> HerdrColors.DoneSubtle
    }
    val statusBadgeFg = when {
        currentTurn.isRunning -> HerdrColors.Working
        showFatalError -> HerdrColors.Blocked
        isHistoryPreviewOnly && isLatestPage -> HerdrColors.Muted
        else -> HerdrColors.Done
    }
    val statusBadgeText = when {
        currentTurn.isRunning -> "思考与检索中"
        showFatalError -> "异常"
        isHistoryPreviewOnly || !isLatestPage -> "历史记录"
        else -> "已完成"
    }

    val dragThresholdPx = with(LocalDensity.current) { 44.dp.toPx() }
    val swipePagerModifier = if (turns.size > 1) {
        Modifier.pointerInput(turns.size) {
            var dragDx = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragDx = 0f },
                onHorizontalDrag = { _, delta -> dragDx += delta },
                onDragEnd = {
                    if (dragDx > dragThresholdPx && pageIndex > 0) {
                        pageIndex -= 1
                    } else if (dragDx < -dragThresholdPx && pageIndex < turns.lastIndex) {
                        pageIndex += 1
                    }
                    dragDx = 0f
                }
            )
        }
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Block)
            .background(HerdrColors.Surface)
            .border(1.dp, borderTone.copy(alpha = 0.48f), HerdrShapes.Block)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header + Question region supports left/right swipe across Q&A history without blocking table horizontal scroll
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(swipePagerModifier),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(HerdrShapes.Pill)
                        .background(HerdrColors.AccentSubtle)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        "/btw",
                        style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                        color = HerdrColors.Accent
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(HerdrShapes.Pill)
                        .background(statusBadgeBg)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (currentTurn.isRunning) {
                        StatusDot(HerdrColors.Working, 6.dp, pulsing = true)
                    }
                    Text(statusBadgeText, style = HerdrType.Caption, color = statusBadgeFg, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                if (turns.size > 1) {
                    Row(
                        modifier = Modifier
                            .clip(HerdrShapes.Pill)
                            .background(HerdrColors.Raised)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val canPrev = safePage > 0
                        val canNext = safePage < turns.lastIndex
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .clickable(enabled = canPrev) { pageIndex = (safePage - 1).coerceAtLeast(0) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "上一条快问",
                                tint = if (canPrev) HerdrColors.Foreground else HerdrColors.Dim,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "${safePage + 1}/${turns.size}",
                            style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                            color = if (!isLatestPage) HerdrColors.Accent else HerdrColors.Foreground,
                            modifier = Modifier
                                .clip(HerdrShapes.Small)
                                .clickable(enabled = !isLatestPage) { pageIndex = turns.lastIndex }
                                .padding(horizontal = 4.dp)
                        )
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .clickable(enabled = canNext) { pageIndex = (safePage + 1).coerceAtMost(turns.lastIndex) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "下一条快问",
                                tint = if (canNext) HerdrColors.Foreground else HerdrColors.Dim,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (currentTurn.answerText.isNotBlank()) {
                    ModalMiniChip(if (copiedOnce) "已复制" else "复制", enabled = true, primary = copiedOnce) {
                        onCopyText(currentTurn.answerText)
                        copiedOnce = true
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(HerdrShapes.Pill)
                        .background(if (currentTurn.isRunning && !isHistoryPreviewOnly) HerdrColors.BlockedSubtle else HerdrColors.Raised)
                        .clickable(enabled = !isBusy, onClick = onDismiss)
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    val closeLabel = when {
                        isHistoryPreviewOnly -> "关闭"
                        currentTurn.isRunning -> "取消 Esc"
                        else -> "关闭 Esc"
                    }
                    Text(
                        text = closeLabel,
                        style = HerdrType.Caption,
                        color = if (currentTurn.isRunning && !isHistoryPreviewOnly) HerdrColors.Blocked else HerdrColors.Foreground,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }

            if (!isLatestPage && latestRunning) {
                ModalMiniChip("最新快问正在思考 · 点击查看 ›", enabled = true, primary = true) {
                    pageIndex = turns.lastIndex
                }
            }

            Text(
                text = "Q: ${currentTurn.question}",
                style = HerdrType.Label.copy(fontWeight = FontWeight.SemiBold),
                color = HerdrColors.Foreground,
                maxLines = if (compactViewport) 2 else 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Tool steps & live spinner status summary (auto-collapsed once answer is present to keep card height compact)
        if (currentTurn.toolSteps.isNotEmpty() || currentTurn.statusSummary.isNotBlank()) {
            val collapsibleSteps = currentTurn.toolSteps.isNotEmpty() &&
                (currentTurn.answerText.isNotBlank() || (!currentTurn.isRunning && currentTurn.toolSteps.size > 3))
            val showStepsList = currentTurn.toolSteps.isNotEmpty() && (!collapsibleSteps || toolsExpanded)
            val stepsScroll = rememberScrollState()
            LaunchedEffect(currentTurn.toolSteps.size, currentTurn.isRunning) {
                if (currentTurn.isRunning && currentTurn.toolSteps.isNotEmpty()) {
                    stepsScroll.scrollTo(stepsScroll.maxValue)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HerdrShapes.Small)
                    .background(HerdrColors.Raised)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val summaryHeader = currentTurn.statusSummary.ifBlank {
                    if (currentTurn.toolSteps.isNotEmpty()) "工具检索（${currentTurn.toolSteps.size} 步）" else ""
                }
                if (summaryHeader.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (collapsibleSteps) {
                                    Modifier
                                        .clip(HerdrShapes.Small)
                                        .clickable { toolsExpanded = !toolsExpanded }
                                } else {
                                    Modifier
                                }
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (currentTurn.isRunning) {
                            StatusDot(HerdrColors.Working, 6.dp, pulsing = true)
                        }
                        Text(
                            text = summaryHeader,
                            style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace),
                            color = if (currentTurn.isRunning) HerdrColors.Working else HerdrColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (collapsibleSteps) {
                            Text(
                                text = if (toolsExpanded) "收起" else "展开 (${currentTurn.toolSteps.size})",
                                style = HerdrType.Caption,
                                color = HerdrColors.Accent,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
                if (showStepsList) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                max = when {
                                    compactViewport -> 56.dp
                                    currentTurn.isRunning -> 104.dp
                                    else -> 140.dp
                                }
                            )
                            .verticalScroll(stepsScroll),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        currentTurn.toolSteps.forEach { step ->
                            val stepColor = when {
                                step.startsWith("✓") -> HerdrColors.Done
                                step.startsWith("✗") -> HerdrColors.Blocked
                                else -> HerdrColors.Muted
                            }
                            Text(
                                text = step,
                                style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace),
                                color = stepColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Rich answer body (tables, code blocks, lists, headings, prose) or waiting notice
        if (currentTurn.answerText.isNotBlank()) {
            BtwRichAnswerBody(
                answerText = currentTurn.answerText,
                maxHeight = if (compactViewport) 118.dp else 280.dp,
                onCopyText = onCopyText
            )
        } else {
            val placeholder = when {
                currentTurn.isRunning -> "正在后台思考与检索，不打断主对话上下文…"
                currentTurn.toolSteps.isNotEmpty() -> "工具检索已完成，等待生成回复…"
                else -> "等待回复内容…"
            }
            Text(placeholder, style = HerdrType.Caption, color = HerdrColors.Muted)
        }

        // Footer controls: follow-up input (enabled whenever latest turn is not running) + optional Tab expand
        if (!latestRunning) {
            val canToggleExpand = !isHistoryPreviewOnly && isLatestPage && liveCard != null && (
                "expand" in liveCard.footerHint.lowercase() ||
                    "collapse" in liveCard.footerHint.lowercase() ||
                    currentTurn.toolSteps.isNotEmpty()
                )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(HerdrShapes.Small)
                        .background(HerdrColors.Raised)
                        .clickable(
                            enabled = !isBusy,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            followUpFocusRequester.requestFocus()
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = followUpText,
                        onValueChange = { followUpText = it },
                        singleLine = true,
                        enabled = !isBusy,
                        textStyle = HerdrType.Caption.copy(color = HerdrColors.Foreground),
                        cursorBrush = SolidColor(HerdrColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            val q = followUpText.trim()
                            if (q.isNotEmpty() && !isBusy) {
                                followUpText = ""
                                onFollowUp(q)
                            }
                        }),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (followUpText.isEmpty()) {
                                    OneLine("继续追问 /btw ...", HerdrType.Caption, HerdrColors.Dim)
                                }
                                innerTextField()
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(followUpFocusRequester)
                            .onFocusChanged { onFollowUpFocusChanged(it.isFocused) }
                    )
                }
                val canSendFollowUp = !isBusy && followUpText.isNotBlank()
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(HerdrShapes.Small)
                        .background(if (canSendFollowUp) HerdrColors.Accent else HerdrColors.Raised)
                        .clickable(enabled = canSendFollowUp) {
                            val q = followUpText.trim()
                            followUpText = ""
                            onFollowUp(q)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送追问",
                        tint = if (canSendFollowUp) HerdrColors.OnAccent else HerdrColors.Dim,
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (canToggleExpand) {
                    val liveCardNonNull = liveCard!!
                    ModalMiniChip(if (liveCardNonNull.isExpanded) "收起 Tab" else "详情 Tab", !isBusy, onClick = onToggleExpand)
                }
            }
        }
    }
}

@Composable
private fun BtwRichAnswerBody(
    answerText: String,
    maxHeight: Dp = 280.dp,
    onCopyText: (String) -> Unit
) {
    val segments = remember(answerText) { BtwRichContentParser.parse(answerText) }
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(HerdrShapes.Small)
                .background(HerdrColors.Raised)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (segments.isEmpty()) {
                Text(
                    text = answerText,
                    style = HerdrType.Body,
                    color = HerdrColors.Foreground
                )
            } else {
                segments.forEach { seg ->
                    when (seg) {
                        is BtwContentSegment.Table -> BtwGridTableBlock(seg, onCopyText)
                        is BtwContentSegment.CodeBlock -> BtwCodeSegmentBlock(seg, onCopyText)
                        is BtwContentSegment.Heading -> Text(
                            text = formatBtwInline(seg.text),
                            style = when (seg.level) {
                                1 -> HerdrType.Heading
                                else -> HerdrType.Label.copy(fontWeight = FontWeight.Bold)
                            },
                            color = HerdrColors.Foreground
                        )
                        is BtwContentSegment.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            seg.items.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = (item.indentLevel * 12).dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = item.marker,
                                        style = HerdrType.Body.copy(fontWeight = FontWeight.SemiBold),
                                        color = HerdrColors.Accent,
                                        modifier = Modifier.width(18.dp)
                                    )
                                    Text(
                                        text = formatBtwInline(item.text),
                                        style = HerdrType.Body,
                                        color = HerdrColors.Foreground,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        is BtwContentSegment.Paragraph -> Text(
                            text = formatBtwInline(seg.text),
                            style = HerdrType.Body,
                            color = HerdrColors.Foreground
                        )
                    }
                }
            }
        }
    }
}

private class GridDrawCache {
    var colWidths: IntArray = IntArray(0)
    var rowHeights: IntArray = IntArray(0)
}

@Composable
private fun BtwGridTableBlock(
    table: BtwContentSegment.Table,
    onCopyText: (String) -> Unit
) {
    var copiedTable by remember(table.rawText) { mutableStateOf(false) }
    val allRows = remember(table.headers, table.rows) {
        if (table.headers.isNotEmpty()) listOf(table.headers) + table.rows else table.rows
    }
    val colCount = remember(table.headers, table.rows) {
        maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0, 1)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Small)
            .background(HerdrColors.Canvas)
            .border(1.dp, HerdrColors.Border.copy(alpha = 0.7f), HerdrShapes.Small)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HerdrColors.Surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "表格 · ${table.rows.size} 行",
                style = HerdrType.Caption,
                color = HerdrColors.Muted,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (copiedTable) "已复制" else "复制",
                style = HerdrType.Caption,
                color = if (copiedTable) HerdrColors.Done else HerdrColors.Accent,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(HerdrShapes.Small)
                    .clickable {
                        onCopyText(table.rawText)
                        copiedTable = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        HerdrDivider()
        if (allRows.isNotEmpty()) {
            val drawCache = remember(allRows, colCount) { GridDrawCache() }
            val hasHeader = table.headers.isNotEmpty()
            val headerBg = HerdrColors.AccentSubtle.copy(alpha = 0.55f)
            val gridLineColor = HerdrColors.Border.copy(alpha = 0.7f)
            val density = LocalDensity.current
            val minColPx = with(density) { 52.dp.roundToPx() }
            val maxColPx = with(density) { 195.dp.roundToPx() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Layout(
                    modifier = Modifier.drawBehind {
                        val cw = drawCache.colWidths
                        val rh = drawCache.rowHeights
                        if (cw.isEmpty() || rh.isEmpty()) return@drawBehind
                        if (hasHeader && rh[0] > 0) {
                            drawRect(
                                color = headerBg,
                                topLeft = Offset.Zero,
                                size = Size(size.width, rh[0].toFloat())
                            )
                        }
                        val stroke = 1.dp.toPx()
                        var y = 0f
                        for (r in 0 until rh.size - 1) {
                            y += rh[r].toFloat()
                            drawLine(
                                color = gridLineColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = stroke
                            )
                        }
                        var x = 0f
                        for (c in 0 until cw.size - 1) {
                            x += cw[c].toFloat()
                            drawLine(
                                color = gridLineColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = stroke
                            )
                        }
                    },
                    content = {
                        allRows.forEachIndexed { r, rowCells ->
                            val isHeader = hasHeader && r == 0
                            for (c in 0 until colCount) {
                                val cellText = rowCells.getOrElse(c) { "" }
                                val align = when (table.alignments.getOrElse(c) { BtwTableAlign.START }) {
                                    BtwTableAlign.START -> TextAlign.Start
                                    BtwTableAlign.CENTER -> TextAlign.Center
                                    BtwTableAlign.END -> TextAlign.End
                                }
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    contentAlignment = when (align) {
                                        TextAlign.Center -> Alignment.Center
                                        TextAlign.End -> Alignment.CenterEnd
                                        else -> Alignment.CenterStart
                                    }
                                ) {
                                    Text(
                                        text = formatBtwInline(cellText),
                                        style = HerdrType.Caption.copy(
                                            fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                                            lineHeight = 17.sp
                                        ),
                                        color = if (isHeader) HerdrColors.Accent else HerdrColors.Foreground,
                                        textAlign = align
                                    )
                                }
                            }
                        }
                    }
                ) { measurables, constraints ->
                    val rowCount = allRows.size
                    val cellConstraints = Constraints(minWidth = minColPx, maxWidth = maxColPx)
                    val placeables = measurables.map { it.measure(cellConstraints) }
                    val colWidths = IntArray(colCount) { minColPx }
                    val rowHeights = IntArray(rowCount)
                    for (r in 0 until rowCount) {
                        var maxH = 0
                        for (c in 0 until colCount) {
                            val idx = r * colCount + c
                            if (idx < placeables.size) {
                                val p = placeables[idx]
                                if (p.width > colWidths[c]) colWidths[c] = p.width
                                if (p.height > maxH) maxH = p.height
                            }
                        }
                        rowHeights[r] = maxH
                    }
                    drawCache.colWidths = colWidths
                    drawCache.rowHeights = rowHeights

                    val totalW = colWidths.sum().coerceIn(constraints.minWidth, constraints.maxWidth)
                    val totalH = rowHeights.sum().coerceIn(constraints.minHeight, constraints.maxHeight)
                    layout(totalW, totalH) {
                        var y = 0
                        for (r in 0 until rowCount) {
                            var x = 0
                            for (c in 0 until colCount) {
                                val idx = r * colCount + c
                                if (idx < placeables.size) {
                                    val p = placeables[idx]
                                    val cw = colWidths[c]
                                    val dx = when (table.alignments.getOrElse(c) { BtwTableAlign.START }) {
                                        BtwTableAlign.CENTER -> ((cw - p.width) / 2).coerceAtLeast(0)
                                        BtwTableAlign.END -> (cw - p.width).coerceAtLeast(0)
                                        BtwTableAlign.START -> 0
                                    }
                                    val dy = ((rowHeights[r] - p.height) / 2).coerceAtLeast(0)
                                    p.placeRelative(x + dx, y + dy)
                                }
                                x += colWidths[c]
                            }
                            y += rowHeights[r]
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BtwCodeSegmentBlock(
    segment: BtwContentSegment.CodeBlock,
    onCopyText: (String) -> Unit
) {
    var copiedCode by remember(segment.code) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Small)
            .background(HerdrColors.Canvas)
            .border(1.dp, HerdrColors.Border.copy(alpha = 0.7f), HerdrShapes.Small)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HerdrColors.Surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = segment.language?.takeIf { it.isNotBlank() } ?: "代码",
                style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace),
                color = HerdrColors.Muted,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (copiedCode) "已复制" else "复制",
                style = HerdrType.Caption,
                color = if (copiedCode) HerdrColors.Done else HerdrColors.Accent,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(HerdrShapes.Small)
                    .clickable {
                        onCopyText(segment.code)
                        copiedCode = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        HerdrDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = segment.code,
                style = HerdrType.Mono.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = HerdrColors.Foreground,
                softWrap = false
            )
        }
    }
}

private val btwInlineTokenRegex = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*")

@Composable
private fun formatBtwInline(text: String): AnnotatedString {
    val codeBg = HerdrColors.Canvas
    val codeFg = HerdrColors.Accent
    return remember(text, codeBg, codeFg) {
        buildAnnotatedString {
            var pos = 0
            for (m in btwInlineTokenRegex.findAll(text)) {
                if (m.range.first > pos) {
                    append(text.substring(pos, m.range.first))
                }
                val codeContent = m.groups[1]?.value
                val boldContent = m.groups[2]?.value
                val start = length
                when {
                    codeContent != null -> {
                        append(codeContent)
                        addStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBg,
                                color = codeFg
                            ),
                            start,
                            length
                        )
                    }
                    boldContent != null -> {
                        append(boldContent)
                        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
                    }
                }
                pos = m.range.last + 1
            }
            if (pos < text.length) {
                append(text.substring(pos))
            }
        }
    }
}

/**
 * Hosted interactive control card docked above the workbench composer when `agy` has a slash
 * modal open (`/model`, `/config`, `/resume`, `/tasks`, `/agents`, `/artifact`, `/diff`, etc.).
 */
@Composable
fun AgySlashModalHostCard(
    modal: AgySlashModalState,
    isBusy: Boolean,
    onSelectItem: (index: Int, confirmEnter: Boolean, title: String) -> Unit,
    onStepValue: (index: Int, moveRight: Boolean, label: String) -> Unit,
    onSwitchTab: (forward: Boolean) -> Unit,
    onFilter: (String) -> Unit,
    onSendRawKey: (String) -> Unit,
    onClose: () -> Unit
) {
    var filterInput by remember(modal.kind, modal.tabs.getOrNull(modal.activeTabIndex)) {
        mutableStateOf(modal.searchQuery.orEmpty())
    }
    val supportsFilter = modal.searchQuery != null || modal.kind in setOf(
        AgySlashModalKind.MODEL,
        AgySlashModalKind.RESUME,
        AgySlashModalKind.PLUGIN,
        AgySlashModalKind.SKILLS,
        AgySlashModalKind.CONFIG
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Block)
            .background(HerdrColors.Surface)
            .border(1.dp, HerdrColors.Accent.copy(alpha = 0.4f), HerdrShapes.Block)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (modal.kind.slashCmd.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(HerdrShapes.Pill)
                        .background(HerdrColors.AccentSubtle)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        modal.kind.slashCmd,
                        style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                        color = HerdrColors.Accent
                    )
                }
            }
            OneLine(
                modal.headerTitle,
                HerdrType.Label.copy(fontWeight = FontWeight.SemiBold),
                HerdrColors.Foreground,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(HerdrShapes.Pill)
                    .background(HerdrColors.Raised)
                    .clickable(enabled = !isBusy, onClick = onClose)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("关闭 Esc", style = HerdrType.Caption, color = HerdrColors.Muted)
            }
        }

        if (modal.isLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HerdrShapes.Small)
                    .background(HerdrColors.WorkingSubtle)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusDot(HerdrColors.Working, 6.dp, pulsing = true)
                Text("正在从终端拉取最新面板数据…", style = HerdrType.Caption, color = HerdrColors.Working)
            }
        }

        // Tabs bar
        if (modal.tabs.isNotEmpty() || modal.supportsTabSwitch) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                modal.tabs.forEachIndexed { idx, tabLabel ->
                    val selected = idx == modal.activeTabIndex
                    Box(
                        modifier = Modifier
                            .clip(HerdrShapes.Pill)
                            .background(if (selected) HerdrColors.AccentSubtle else HerdrColors.Raised)
                            .clickable(enabled = !isBusy && !selected) { onSwitchTab(idx > modal.activeTabIndex) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            tabLabel,
                            style = HerdrType.Caption,
                            color = if (selected) HerdrColors.Accent else HerdrColors.Foreground,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
                if (modal.tabs.isEmpty()) {
                    ModalMiniChip(
                        if (modal.kind == AgySlashModalKind.MODEL) "⇄ Tab 切强度" else "Tab 切换分页",
                        !isBusy
                    ) { onSwitchTab(true) }
                }
            }
        }

        // Model Effort / Artifact / Diff quick toolbar
        if (modal.kind == AgySlashModalKind.MODEL || modal.kind == AgySlashModalKind.EFFORT || modal.effortHint != null) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "推理强度：${modal.effortHint ?: "可调"}",
                    style = HerdrType.Caption,
                    color = HerdrColors.Muted
                )
                ModalMiniChip("← 降低", !isBusy) { onStepValue(modal.selectedIndex, false, "推理强度") }
                ModalMiniChip("提高 →", !isBusy) { onStepValue(modal.selectedIndex, true, "推理强度") }
                ModalMiniChip("⏎ 确认应用", !isBusy, primary = true) { onSendRawKey("enter") }
            }
        } else if (modal.kind == AgySlashModalKind.ARTIFACT || modal.kind == AgySlashModalKind.DIFF) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModalMiniChip("← 上一个", !isBusy) { onSendRawKey("left") }
                ModalMiniChip("下一个 →", !isBusy) { onSendRawKey("right") }
                ModalMiniChip("⏎ 展开/进入", !isBusy, primary = true) { onSendRawKey("enter") }
                if (modal.kind == AgySlashModalKind.ARTIFACT) {
                    ModalMiniChip("✓ 批准 (a)", !isBusy) { onSendRawKey("a") }
                }
            }
        }

        // Filter bar
        if (supportsFilter) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(HerdrShapes.Small)
                        .background(HerdrColors.Raised)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (filterInput.isEmpty()) {
                        OneLine("输入关键字筛选列表…", HerdrType.Caption, HerdrColors.Dim)
                    }
                    BasicTextField(
                        value = filterInput,
                        onValueChange = { filterInput = it },
                        singleLine = true,
                        enabled = !isBusy,
                        textStyle = HerdrType.Caption.copy(color = HerdrColors.Foreground),
                        cursorBrush = SolidColor(HerdrColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (filterInput.isNotBlank()) onFilter(filterInput.trim())
                        }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ModalMiniChip("筛选", !isBusy && filterInput.isNotBlank()) {
                    onFilter(filterInput.trim())
                }
            }
        }

        // Informational / quota / empty-state / changelog body lines
        if (modal.bodyLines.isNotEmpty()) {
            val useMonoBody = modal.kind in setOf(
                AgySlashModalKind.CONTEXT,
                AgySlashModalKind.USAGE,
                AgySlashModalKind.CREDITS,
                AgySlashModalKind.DIFF,
                AgySlashModalKind.HELP,
                AgySlashModalKind.CHANGELOG
            )
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (modal.items.isEmpty()) 250.dp else 108.dp)
                        .clip(HerdrShapes.Small)
                        .background(HerdrColors.Raised)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    modal.bodyLines.forEach { bodyLine ->
                        Text(
                            text = bodyLine,
                            style = if (useMonoBody) {
                                HerdrType.Caption.copy(fontFamily = FontFamily.Monospace)
                            } else {
                                HerdrType.Caption
                            },
                            color = HerdrColors.Foreground
                        )
                    }
                }
            }
        }

        // Background Tasks quick action bar
        if (modal.kind == AgySlashModalKind.TASKS && modal.items.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModalMiniChip("⏎ 查看输出", !isBusy, primary = true) { onSendRawKey("enter") }
                ModalMiniChip("✕ 终止任务 (k)", !isBusy) { onSendRawKey("k") }
                ModalMiniChip("移除记录 (x)", !isBusy) { onSendRawKey("x") }
            }
        }

        // Parsed list rows
        if (modal.items.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 210.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                modal.items.forEach { item ->
                    val isCur = item.index == modal.selectedIndex || item.isSelected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(HerdrShapes.Small)
                            .background(if (isCur) HerdrColors.AccentSubtle else HerdrColors.Raised)
                            .clickable(enabled = !isBusy) {
                                onSelectItem(item.index, !modal.supportsLeftRightAdjust, item.title)
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OneLine(
                                text = item.title,
                                style = HerdrType.Caption.copy(
                                    fontWeight = if (isCur || item.isCurrentActive) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                color = if (isCur) HerdrColors.Accent else HerdrColors.Foreground
                            )
                            if (item.subtitleOrValue.isNotBlank()) {
                                OneLine(
                                    text = item.subtitleOrValue,
                                    style = HerdrType.Caption.copy(fontSize = 11.sp),
                                    color = HerdrColors.Muted
                                )
                            }
                        }
                        if (modal.supportsLeftRightAdjust && modal.kind == AgySlashModalKind.CONFIG) {
                            ModalMiniChip("‹", !isBusy) { onStepValue(item.index, false, item.title) }
                            ModalMiniChip("›", !isBusy) { onStepValue(item.index, true, item.title) }
                        } else if (isCur) {
                            Text("⏎", style = HerdrType.Caption, color = HerdrColors.Accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else if (modal.bodyLines.isEmpty() || modal.kind in setOf(
                AgySlashModalKind.DIFF,
                AgySlashModalKind.HELP,
                AgySlashModalKind.CHANGELOG,
                AgySlashModalKind.RESUME,
                AgySlashModalKind.REWIND,
                AgySlashModalKind.CONFIG,
                AgySlashModalKind.GENERIC
            )
        ) {
            // Contextual directional controls when list rows are empty or paging fullscreen views
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModalMiniChip("←", !isBusy) { onSendRawKey("left") }
                ModalMiniChip("↑", !isBusy) { onSendRawKey("up") }
                ModalMiniChip("↓", !isBusy) { onSendRawKey("down") }
                ModalMiniChip("→", !isBusy) { onSendRawKey("right") }
                ModalMiniChip("Space", !isBusy) { onSendRawKey("space") }
                ModalMiniChip("⏎ 确认", !isBusy, primary = true) { onSendRawKey("enter") }
            }
        } else if (modal.dismissWithEnterAllowed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                ModalMiniChip("⏎ / Esc 关闭面板", !isBusy, primary = true, onClick = onClose)
            }
        }
    }
}

@Composable
private fun ModalMiniChip(
    label: String,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        !enabled -> HerdrColors.Raised
        primary -> HerdrColors.AccentSubtle
        else -> HerdrColors.Raised
    }
    val fg = when {
        !enabled -> HerdrColors.Dim
        primary -> HerdrColors.Accent
        else -> HerdrColors.Foreground
    }
    Box(
        modifier = Modifier
            .clip(HerdrShapes.Pill)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            style = HerdrType.Caption,
            color = fg,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Full-depth Antigravity `/` Slash Command Center overlay sheet providing:
 * 1. Closed-loop Execution Mode switching (`Auto`, `Accept-edits`, `Plan`, `Best-of-N`)
 * 2. Quick-parameter runners (`/effort`, `/thinking`, `/copy`, `/skills`)
 * 3. Parameterized input commands (`/btw`, `/compact`, `/plan`, `/rename`, `/add-dir`, `/clear`)
 * 4. One-tap launch of all interactive `agy` panels
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgySlashCommandCenterSheet(
    visible: Boolean,
    agyState: AgyTerminalState?,
    isBusy: Boolean,
    btwHistoryCount: Int = 0,
    onOpenBtwHistory: () -> Unit = {},
    onDismiss: () -> Unit,
    onCycleModeTo: (AgyAgentMode) -> Unit,
    onRunCommand: (commandText: String, expectModal: AgySlashModalKind?) -> Unit
) {
    if (!visible) return
    BackHandler(enabled = true, onBack = onDismiss)

    var selectedParamSpec by remember { mutableStateOf<AgySlashCatalog.SlashCommandSpec?>(null) }
    var argText by remember(selectedParamSpec?.cmd) { mutableStateOf("") }
    var armedClearConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.56f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(HerdrColors.Canvas)
                .border(
                    width = 1.dp,
                    color = HerdrColors.Border,
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Sheet header + perceived state badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "/ Antigravity 斜杠命令中心",
                        style = HerdrType.Heading,
                        color = HerdrColors.Foreground
                    )
                    val modeTxt = agyState?.agentMode?.label ?: "未知模式"
                    val modelTxt = agyState?.activeModelSummary ?: "默认模型"
                    val effortTxt = agyState?.activeEffort?.let { " · $it" }.orEmpty()
                    OneLine("$modeTxt · $modelTxt$effortTxt", HerdrType.Caption, HerdrColors.Muted)
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(HerdrColors.Raised)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "关闭命令中心", tint = HerdrColors.Muted, modifier = Modifier.size(18.dp))
                }
            }

            HerdrDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Execution Mode Switcher (Shift+Tab closed-loop)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("执行模式（Shift+Tab 闭环切换）", style = HerdrType.Caption, color = HerdrColors.Muted, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AgyAgentMode.entries.forEach { mode ->
                            val isActive = agyState?.agentMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(HerdrShapes.Control)
                                    .background(if (isActive) HerdrColors.AccentSubtle else HerdrColors.Surface)
                                    .border(
                                        1.dp,
                                        if (isActive) HerdrColors.Accent else HerdrColors.Border.copy(alpha = 0.45f),
                                        HerdrShapes.Control
                                    )
                                    .clickable(enabled = !isBusy) {
                                        onCycleModeTo(mode)
                                        onDismiss()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    mode.label,
                                    style = HerdrType.Caption,
                                    color = if (isActive) HerdrColors.Accent else HerdrColors.Foreground,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // 2. Quick Effort & Thinking Strips
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("推理强度与思考显隐（参数直达）", style = HerdrType.Caption, color = HerdrColors.Muted, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ModalMiniChip("/effort 面板", !isBusy) {
                            onRunCommand("/effort", AgySlashModalKind.EFFORT)
                            onDismiss()
                        }
                        listOf("low", "medium", "high", "xhigh", "max").forEach { lvl ->
                            val isCur = agyState?.activeEffort?.equals(lvl, ignoreCase = true) == true
                            ModalMiniChip("/effort $lvl", !isBusy, primary = isCur) {
                                onRunCommand("/effort $lvl", null)
                                onDismiss()
                            }
                        }
                        ModalMiniChip("/thinking on", !isBusy) {
                            onRunCommand("/thinking on", null)
                            onDismiss()
                        }
                        ModalMiniChip("/thinking off", !isBusy) {
                            onRunCommand("/thinking off", null)
                            onDismiss()
                        }
                    }
                }

                // 3. Direct / Argument Slash Commands
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("上下文、旁路快问与便捷指令", style = HerdrType.Caption, color = HerdrColors.Muted, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AgySlashCatalog.allCommands.filter { it.category == AgySlashCatalog.Category.CONTEXT }.forEach { spec ->
                            val isClear = spec.requiresConfirm
                            val label = if (isClear && armedClearConfirm) "⚠ 确认执行 /clear" else "${spec.cmd} · ${spec.title}"
                            Box(
                                modifier = Modifier
                                    .clip(HerdrShapes.Pill)
                                    .background(
                                        when {
                                            isClear && armedClearConfirm -> HerdrColors.BlockedSubtle
                                            selectedParamSpec?.cmd == spec.cmd -> HerdrColors.AccentSubtle
                                            else -> HerdrColors.Surface
                                        }
                                    )
                                    .clickable(enabled = !isBusy) {
                                        if (isClear) {
                                            if (!armedClearConfirm) {
                                                armedClearConfirm = true
                                            } else {
                                                armedClearConfirm = false
                                                onRunCommand(spec.cmd, null)
                                                onDismiss()
                                            }
                                        } else if (spec.acceptsFreeTextArg) {
                                            selectedParamSpec = if (selectedParamSpec?.cmd == spec.cmd) null else spec
                                        } else {
                                            onRunCommand(spec.cmd, spec.modalKind)
                                            onDismiss()
                                        }
                                    }
                                    .padding(horizontal = 11.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    label,
                                    style = HerdrType.Caption,
                                    color = if (isClear && armedClearConfirm) HerdrColors.Blocked else HerdrColors.Foreground,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        ModalMiniChip("/copy btw", !isBusy) {
                            onRunCommand("/copy btw", null)
                            onDismiss()
                        }
                        if (btwHistoryCount > 0) {
                            ModalMiniChip("快问记录 ($btwHistoryCount)", !isBusy, primary = true) {
                                onOpenBtwHistory()
                                onDismiss()
                            }
                        }
                    }

                    AnimatedVisibility(visible = selectedParamSpec != null) {
                        val activeSpec = selectedParamSpec
                        if (activeSpec != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .clip(HerdrShapes.Control)
                                    .background(HerdrColors.Surface)
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OneLine(
                                    "${activeSpec.cmd} — ${activeSpec.description}",
                                    HerdrType.Caption.copy(fontWeight = FontWeight.SemiBold),
                                    HerdrColors.Accent
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 38.dp)
                                            .clip(HerdrShapes.Small)
                                            .background(HerdrColors.Raised)
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (argText.isEmpty()) {
                                            OneLine(activeSpec.argPlaceholder.ifEmpty { "输入命令参数" }, HerdrType.Caption, HerdrColors.Dim)
                                        }
                                        BasicTextField(
                                            value = argText,
                                            onValueChange = { argText = it },
                                            singleLine = true,
                                            textStyle = HerdrType.Body.copy(color = HerdrColors.Foreground),
                                            cursorBrush = SolidColor(HerdrColors.Accent),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    val canRunParam = !isBusy && (!activeSpec.cmd.equals("/btw", true) || argText.isNotBlank())
                                    HerdrButton(
                                        text = "执行",
                                        onClick = {
                                            val arg = argText.trim()
                                            val cmdLine = if (arg.isEmpty()) activeSpec.cmd else "${activeSpec.cmd} $arg"
                                            val expect = if (arg.isEmpty()) activeSpec.modalKind else null
                                            onRunCommand(cmdLine, expect)
                                            selectedParamSpec = null
                                            onDismiss()
                                        },
                                        enabled = canRunParam,
                                        kind = HerdrButtonKind.Primary,
                                        compact = true
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Interactive Modal Panels Launcher
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("交互面板（打开后将自动托管为手机原生卡片）", style = HerdrType.Caption, color = HerdrColors.Muted, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val panelSpecs = AgySlashCatalog.allCommands.filter {
                            it.modalKind != null && it.modalKind != AgySlashModalKind.BTW
                        }
                        panelSpecs.forEach { spec ->
                            Box(
                                modifier = Modifier
                                    .clip(HerdrShapes.Control)
                                    .background(HerdrColors.Surface)
                                    .border(1.dp, HerdrColors.Border.copy(alpha = 0.35f), HerdrShapes.Control)
                                    .clickable(enabled = !isBusy) {
                                        onRunCommand(spec.cmd, spec.modalKind)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Column {
                                    Text(
                                        spec.cmd,
                                        style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                                        color = HerdrColors.Accent
                                    )
                                    Text(spec.title, style = HerdrType.Caption, color = HerdrColors.Foreground)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
